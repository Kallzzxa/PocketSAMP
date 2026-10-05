package com.pocketsamp.server

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import com.pocketsamp.server.db.DatabaseManager
import com.pocketsamp.server.service.ServerForegroundService
import com.pocketsamp.server.ui.screens.ConfigScreen
import com.pocketsamp.server.ui.screens.ConsoleScreen
import com.pocketsamp.server.ui.screens.CrashLogsScreen
import com.pocketsamp.server.ui.screens.DashboardScreen
import com.pocketsamp.server.ui.screens.DatabaseScreen
import com.pocketsamp.server.ui.theme.*

enum class AppTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Dashboard),
    CONSOLE("Console", Icons.Default.Terminal),
    DATABASE("Database", Icons.Default.Storage),
    CRASHES("Crash Logs", Icons.Default.BugReport),
    CONFIG("Config", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private val dbManager by lazy { DatabaseManager() }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Handled
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            PocketSAMPTheme {
                MainAppLayout()
            }
        }
    }

    @Composable
    private fun MainAppLayout() {
        var currentTab by remember { mutableStateOf(AppTab.DASHBOARD) }

        val serverStatus by ServerForegroundService.serverStatus.collectAsState()
        val isDbRunning by ServerForegroundService.isDbRunning.collectAsState()
        val logs by ServerForegroundService.logFeed.collectAsState()
        val lanIp by ServerForegroundService.lanIp.collectAsState()

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar(
                    containerColor = SurfaceDark,
                    contentColor = TextPrimary
                ) {
                    AppTab.values().forEach { tab ->
                        val selected = tab == currentTab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { currentTab = tab },
                            icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                            label = { Text(text = tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AccentCyan,
                                selectedTextColor = AccentCyan,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                                indicatorColor = SurfaceCard
                            )
                        )
                    }
                }
            }
        ) { paddingValues ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(BgDark)
            ) {
                when (currentTab) {
                    AppTab.DASHBOARD -> DashboardScreen(
                        status = serverStatus,
                        isDbRunning = isDbRunning,
                        lanIp = lanIp,
                        onStartServer = { startServerService() },
                        onStopServer = { stopServerService() }
                    )
                    AppTab.CONSOLE -> ConsoleScreen(
                        logs = logs,
                        onClearLogs = { ServerForegroundService.clearLogs() }
                    )
                    AppTab.DATABASE -> DatabaseScreen(
                        dbManager = dbManager,
                        isDbRunning = isDbRunning
                    )
                    AppTab.CRASHES -> CrashLogsScreen()
                    AppTab.CONFIG -> ConfigScreen()
                }
            }
        }
    }

    private fun startServerService() {
        if (!com.pocketsamp.server.config.ServerDirectoryManager.hasStoragePermission(this)) {
            android.widget.Toast.makeText(
                this,
                "Beri izin Akses Semua File terlebih dahulu agar server dapat membaca gamemode Anda",
                android.widget.Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(com.pocketsamp.server.config.ServerDirectoryManager.getManageStorageIntent(this))
            } catch (ignored: Exception) {
            }
            return
        }

        val intent = Intent(this, ServerForegroundService::class.java).apply {
            action = ServerForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopServerService() {
        val intent = Intent(this, ServerForegroundService::class.java).apply {
            action = ServerForegroundService.ACTION_STOP
        }
        startService(intent)
    }
}
