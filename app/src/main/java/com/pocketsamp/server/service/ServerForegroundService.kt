package com.pocketsamp.server.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pocketsamp.server.MainActivity
import com.pocketsamp.server.R
import com.pocketsamp.server.engine.AssetExtractor
import com.pocketsamp.server.engine.MariaDbEngine
import com.pocketsamp.server.engine.SampEngine
import com.pocketsamp.server.crash.CrashHandler
import com.pocketsamp.server.crash.CrashManager
import com.pocketsamp.server.crash.CrashReport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.PrintWriter
import java.io.StringWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*

enum class ServerStatus {
    STOPPED,
    INITIALIZING,
    STARTING,
    RUNNING,
    STOPPING
}

class ServerForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "pocketsamp_service_channel"
        const val NOTIFICATION_ID = 7777

        const val ACTION_START = "com.pocketsamp.server.ACTION_START"
        const val ACTION_STOP = "com.pocketsamp.server.ACTION_STOP"

        private val _serverStatus = MutableStateFlow(ServerStatus.STOPPED)
        val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

        private val _isDbRunning = MutableStateFlow(false)
        val isDbRunning: StateFlow<Boolean> = _isDbRunning.asStateFlow()

        private val _logFeed = MutableStateFlow<List<String>>(emptyList())
        val logFeed: StateFlow<List<String>> = _logFeed.asStateFlow()

        private val _lanIp = MutableStateFlow("127.0.0.1")
        val lanIp: StateFlow<String> = _lanIp.asStateFlow()

        fun appendLog(line: String) {
            val current = _logFeed.value.toMutableList()
            if (current.size > 500) {
                current.removeAt(0)
            }
            current.add(line)
            _logFeed.value = current
        }

        fun clearLogs() {
            _logFeed.value = emptyList()
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private lateinit var assetExtractor: AssetExtractor
    private lateinit var sampEngine: SampEngine
    private lateinit var mariaDbEngine: MariaDbEngine

    inner class LocalBinder : Binder() {
        fun getService(): ServerForegroundService = this@ServerForegroundService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        assetExtractor = AssetExtractor(this)
        sampEngine = SampEngine(this)
        mariaDbEngine = MariaDbEngine(this)

        // Stream logs from engines
        serviceScope.launch {
            sampEngine.serverLogs.collect { appendLog(it) }
        }
        serviceScope.launch {
            mariaDbEngine.dbLogs.collect { appendLog(it) }
        }

        updateLanIp()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServers()
            ACTION_STOP -> stopServers()
        }
        return START_NOT_STICKY
    }

    private fun startServers() {
        if (_serverStatus.value == ServerStatus.RUNNING || _serverStatus.value == ServerStatus.STARTING) return

        clearLogs()
        _serverStatus.value = ServerStatus.STARTING
        acquireLocks()
        startForeground(NOTIFICATION_ID, buildNotification("Starting servers..."))

        serviceScope.launch {
            try {
                // 1. Ensure assets are unpacked
                if (!assetExtractor.isExtracted()) {
                    _serverStatus.value = ServerStatus.INITIALIZING
                    appendLog("[PocketSAMP] Initializing runtime assets for first boot...")
                    assetExtractor.extractAll { progress ->
                        appendLog("[Setup] $progress")
                        updateNotification("Setup: $progress")
                    }
                }

                // 2. Initialize and start MariaDB
                updateNotification("Starting MariaDB (MySQL)...")
                mariaDbEngine.initializeIfNeeded(this)
                mariaDbEngine.start(this)
                _isDbRunning.value = true
                delay(1000)

                // 3. Start SA-MP server via PRoot + QEMU
                updateNotification("Launching SA-MP Server...")
                sampEngine.start(this)
                delay(1000)

                _serverStatus.value = ServerStatus.RUNNING
                updateLanIp()
                updateNotification("SA-MP Server active on ${_lanIp.value}:7777")
                appendLog("[PocketSAMP] All services active! Connect to ${_lanIp.value}:7777")

            } catch (e: Exception) {
                val errorDetails = e.message ?: e.javaClass.simpleName
                appendLog("[PocketSAMP Error] Failed to start: $errorDetails")
                Log.e("ServerForegroundService", "Startup error", e)

                // Record to CrashManager for in-app tracking
                try {
                    val timestamp = System.currentTimeMillis()
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    val sw = StringWriter()
                    e.printStackTrace(PrintWriter(sw))
                    CrashManager.saveCrash(
                        this@ServerForegroundService,
                        CrashReport(
                            id = UUID.randomUUID().toString().substring(0, 8),
                            timestamp = timestamp,
                            formattedDate = dateFormat.format(Date(timestamp)),
                            exceptionClass = e.javaClass.name,
                            message = e.message,
                            threadName = Thread.currentThread().name,
                            deviceInfo = CrashHandler.getDeviceInfo(this@ServerForegroundService),
                            stackTrace = sw.toString()
                        )
                    )
                } catch (ignored: Exception) {
                }

                _serverStatus.value = ServerStatus.STOPPED
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun stopServers() {
        _serverStatus.value = ServerStatus.STOPPING
        serviceScope.launch {
            appendLog("[PocketSAMP] Shutting down SA-MP server...")
            sampEngine.stop()
            appendLog("[PocketSAMP] Shutting down MariaDB...")
            mariaDbEngine.stop()
            _isDbRunning.value = false
            _serverStatus.value = ServerStatus.STOPPED
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            appendLog("[PocketSAMP] Server successfully stopped.")
        }
    }

    private fun updateLanIp() {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        _lanIp.value = address.hostAddress ?: "127.0.0.1"
                        return
                    }
                }
            }
            _lanIp.value = "127.0.0.1"
        } catch (e: Exception) {
            _lanIp.value = "127.0.0.1"
        }
    }

    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketSAMP::CpuWakeLock").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // 12 hours max safety timeout
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // 1. Wi-Fi Lock with Low Latency on Android 10+
        val wifiMode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(wifiMode, "PocketSAMP::WifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }

        // 2. Multicast Lock - Prevents Android Wi-Fi chipset from dropping incoming UDP query packets
        try {
            multicastLock = wifiManager.createMulticastLock("PocketSAMP::MulticastLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w("ServerForegroundService", "MulticastLock warning: ${e.message}")
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null

        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains SA-MP local server background process"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ServerForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PocketSAMP Server")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        serviceScope.cancel()
        releaseLocks()
        super.onDestroy()
    }
}
