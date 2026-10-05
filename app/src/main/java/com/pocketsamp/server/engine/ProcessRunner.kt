package com.pocketsamp.server.engine

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class ProcessRunner(
    private val tag: String,
    var onExit: ((exitCode: Int) -> Unit)? = null
) {
    private var process: Process? = null
    private var job: Job? = null

    private val _logs = MutableSharedFlow<String>(replay = 50)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    val isRunning: Boolean
        get() = process?.isAlive == true

    fun start(
        command: List<String>,
        workingDir: File,
        environment: Map<String, String> = emptyMap(),
        scope: CoroutineScope
    ) {
        if (isRunning) {
            Log.w(tag, "Process is already running.")
            return
        }

        try {
            val processBuilder = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true)

            processBuilder.environment().putAll(environment)

            val proc = processBuilder.start()
            process = proc

            job = scope.launch(Dispatchers.IO) {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE)
                } catch (ignored: Exception) {
                }

                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String? = reader.readLine()
                        while (line != null && isActive) {
                            _logs.emit("[$tag] $line")
                            line = reader.readLine()
                        }
                    }
                } catch (e: Exception) {
                    _logs.emit("[$tag] Stream closed: ${e.message}")
                } finally {
                    val exitVal = proc.waitFor()
                    _logs.emit("[$tag] Process exited with code $exitVal")
                    onExit?.invoke(exitVal)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start process: ${e.message}", e)
            scope.launch {
                _logs.emit("[$tag] Failed to start: ${e.message}")
            }
        }
    }

    fun stop() {
        job?.cancel()
        process?.let {
            it.destroy()
            try {
                // Give graceful SIGTERM 1 second before SIGKILL
                Thread.sleep(300)
                if (it.isAlive) {
                    it.destroyForcibly()
                }
            } catch (ignored: Exception) {
            }
        }
        process = null
    }
}
