package com.pocketsamp.server.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

class MariaDbEngine(private val context: Context) {

    companion object {
        private const val TAG = "MariaDbEngine"
    }

    private val processRunner = ProcessRunner("MariaDB")

    private val _dbLogs = MutableSharedFlow<String>(replay = 50)
    val dbLogs: SharedFlow<String> = _dbLogs.asSharedFlow()

    val isRunning: Boolean
        get() = processRunner.isRunning

    suspend fun initializeIfNeeded(scope: CoroutineScope): Boolean = withContext(Dispatchers.IO) {
        val mariadbDir = File(context.filesDir, "mariadb")
        val dataDir = File(mariadbDir, "data")
        val systemDb = File(dataDir, "mysql")

        if (systemDb.exists()) {
            return@withContext true
        }

        _dbLogs.emit("[MariaDB] Initializing new system database cluster...")
        dataDir.mkdirs()
        File(mariadbDir, "tmp").mkdirs()

        val installDbBinary = File(mariadbDir, "bin/mariadb-install-db")
        installDbBinary.setExecutable(true, false)

        val cmd = listOf(
            installDbBinary.absolutePath,
            "--datadir=${dataDir.absolutePath}",
            "--basedir=${mariadbDir.absolutePath}",
            "--user=root"
        )

        val env = mapOf(
            "LD_LIBRARY_PATH" to File(mariadbDir, "lib").absolutePath,
            "TMPDIR" to File(mariadbDir, "tmp").absolutePath
        )

        try {
            val process = ProcessBuilder(cmd)
                .directory(mariadbDir)
                .redirectErrorStream(true)
            process.environment().putAll(env)

            val proc = process.start()
            val exitCode = proc.waitFor()
            if (exitCode == 0) {
                _dbLogs.emit("[MariaDB] Database system initialized successfully.")
                true
            } else {
                _dbLogs.emit("[MariaDB Error] Initialization failed with exit code $exitCode")
                false
            }
        } catch (e: Exception) {
            _dbLogs.emit("[MariaDB Error] Exception during install-db: ${e.message}")
            false
        }
    }

    fun start(scope: CoroutineScope) {
        if (isRunning) return

        val mariadbDir = File(context.filesDir, "mariadb")
        val daemonBinary = File(mariadbDir, "bin/mariadbd")
        val cnfFile = File(mariadbDir, "my.cnf")

        daemonBinary.setExecutable(true, false)

        val command = listOf(
            daemonBinary.absolutePath,
            "--defaults-file=${cnfFile.absolutePath}"
        )

        val env = mapOf(
            "LD_LIBRARY_PATH" to File(mariadbDir, "lib").absolutePath,
            "TMPDIR" to File(mariadbDir, "tmp").absolutePath,
            "HOME" to mariadbDir.absolutePath
        )

        processRunner.start(
            command = command,
            workingDir = mariadbDir,
            environment = env,
            scope = scope
        )

        scope.launch {
            processRunner.logs.collect { line ->
                _dbLogs.emit(line)
            }
        }
    }

    fun stop() {
        processRunner.stop()
    }
}
