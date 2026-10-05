package com.pocketsamp.server.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.RandomAccessFile

class SampEngine(private val context: Context) {

    companion object {
        private const val TAG = "SampEngine"
    }

    private val processRunner = ProcessRunner("SA-MP")
    private var logPollingJob: Job? = null

    private val _serverLogs = MutableSharedFlow<String>(replay = 100)
    val serverLogs: SharedFlow<String> = _serverLogs.asSharedFlow()

    val isRunning: Boolean
        get() = processRunner.isRunning

    fun start(scope: CoroutineScope) {
        if (isRunning) return

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val filesDir = context.filesDir

        val qemuBinary = File(nativeLibDir, "libqemu-i386.so")
        val rootfsDir = File(filesDir, "rootfs_i386")
        val sampDir = AssetExtractor.getServerDirectory(context)
        val serverBinary = File(filesDir, "bin/samp03svr").takeIf { it.exists() }
            ?: File(filesDir, "samp_server/samp03svr").takeIf { it.exists() }
            ?: File(sampDir, "samp03svr")
        val tempDir = File(context.cacheDir, "samp_tmp").apply { mkdirs() }

        if (!qemuBinary.exists()) {
            scope.launch {
                _serverLogs.emit("[SA-MP Error] QEMU binary not found in $nativeLibDir")
            }
            return
        }

        if (!serverBinary.exists()) {
            scope.launch {
                _serverLogs.emit("[SA-MP Error] samp03svr binary not found in ${serverBinary.parent}")
            }
            return
        }

        serverBinary.setExecutable(true, false)

        // Clear historical log file so the console only shows the current session
        val logFile = File(sampDir, "server_log.txt")
        if (logFile.exists()) {
            logFile.delete()
        }

        // Execute SA-MP server via QEMU user-mode translation directly (-L provides the x86 sysroot).
        // This eliminates PRoot ptrace overhead, glue rootfs bugs, and /tmp permission failures.
        val command = listOf(
            qemuBinary.absolutePath,
            "-L", rootfsDir.absolutePath,
            "-E", "LD_LIBRARY_PATH=/usr/lib/i386-linux-gnu:/lib/i386-linux-gnu:/usr/lib:/lib:./plugins:plugins",
            serverBinary.absolutePath
        )

        val env = mapOf(
            "TMPDIR" to tempDir.absolutePath,
            "LD_LIBRARY_PATH" to "${rootfsDir.absolutePath}/usr/lib/i386-linux-gnu:${rootfsDir.absolutePath}/lib/i386-linux-gnu:${sampDir.absolutePath}/plugins",
            "HOME" to sampDir.absolutePath,
            "USER" to "root"
        )

        processRunner.start(
            command = command,
            workingDir = sampDir,
            environment = env,
            scope = scope
        )

        // Relay stdout logs
        scope.launch {
            processRunner.logs.collect { line ->
                _serverLogs.emit(line)
            }
        }

        // Tail server_log.txt for detailed SA-MP Pawn logs
        startLogTailer(File(sampDir, "server_log.txt"), scope)
    }

    private fun startLogTailer(logFile: File, scope: CoroutineScope) {
        logPollingJob?.cancel()
        logPollingJob = scope.launch(Dispatchers.IO) {
            var attempts = 0
            while (!logFile.exists() && attempts < 20 && isActive) {
                delay(250)
                attempts++
            }

            if (!logFile.exists()) return@launch

            var filePointer = 0L
            while (isActive && isRunning) {
                try {
                    val fileLength = logFile.length()
                    if (fileLength > filePointer) {
                        RandomAccessFile(logFile, "r").use { raf ->
                            raf.seek(filePointer)
                            var line: String? = raf.readLine()
                            while (line != null) {
                                _serverLogs.emit(line)
                                line = raf.readLine()
                            }
                            filePointer = raf.filePointer
                        }
                    } else if (fileLength < filePointer) {
                        filePointer = 0L
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Log tailer reading warning: ${e.message}")
                }
                delay(500)
            }
        }
    }

    fun stop() {
        logPollingJob?.cancel()
        processRunner.stop()
    }
}
