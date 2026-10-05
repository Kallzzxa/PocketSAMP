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

        val prootBinary = File(nativeLibDir, "libproot.so")
        val qemuBinary = File(nativeLibDir, "libqemu-i386.so")
        val rootfsDir = File(filesDir, "rootfs_i386")
        val sampDir = File(filesDir, "samp_server")

        if (!prootBinary.exists() || !qemuBinary.exists()) {
            scope.launch {
                _serverLogs.emit("[SA-MP Error] Native engines (PRoot/QEMU) not found in $nativeLibDir")
            }
            return
        }

        // PRoot execution parameters
        // -r: rootfs directory
        // -q: QEMU user-mode binary for x86-32 translation
        // -b: bind mounts (/dev, /proc, and the server directory as /server)
        // -w: working directory inside the container
        val command = listOf(
            prootBinary.absolutePath,
            "-r", rootfsDir.absolutePath,
            "-q", qemuBinary.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "${sampDir.absolutePath}:/server",
            "-w", "/server",
            "/server/samp03svr"
        )

        val env = mapOf(
            "LD_LIBRARY_PATH" to "/usr/lib/i386-linux-gnu:/lib/i386-linux-gnu:/usr/lib:/lib",
            "HOME" to "/server",
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

        // Also tail server_log.txt for detailed SA-MP Pawn logs
        startLogTailer(File(sampDir, "server_log.txt"), scope)
    }

    private fun startLogTailer(logFile: File, scope: CoroutineScope) {
        logPollingJob?.cancel()
        logPollingJob = scope.launch(Dispatchers.IO) {
            // Wait for file creation
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
                        // File was truncated / recreated
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
