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
        val mysqlDir = File(dataDir, "mysql")
        File(mariadbDir, "tmp").mkdirs()

        if (!mysqlDir.exists()) {
            _dbLogs.emit("[MariaDB] Preparing database directory structure...")
            dataDir.mkdirs()
            mysqlDir.mkdirs()
        }

        true
    }

    fun start(scope: CoroutineScope) {
        if (isRunning) return

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val mariadbDir = File(context.filesDir, "mariadb")
        val daemonBinary = File(mariadbDir, "bin/mariadbd")
        val cnfFile = File(mariadbDir, "my.cnf")
        val dataDir = File(mariadbDir, "data").apply { mkdirs() }
        val tmpDir = File(mariadbDir, "tmp").apply { mkdirs() }
        val prootTmp = File(context.cacheDir, "proot_tmp").apply { mkdirs() }
        val prootBinary = File(nativeLibDir, "libproot.so")

        daemonBinary.setExecutable(true, false)

        val socketFile = File(tmpDir, "mysql.sock")
        val pidFile = File(tmpDir, "mysql.pid")

        // PRoot execution with mandatory PROOT_TMP_DIR and system mount bindings
        val command = if (prootBinary.exists()) {
            listOf(
                prootBinary.absolutePath,
                "-0",
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/system",
                "-b", "/vendor",
                "-b", "${mariadbDir.absolutePath}:/mariadb",
                "-w", "/mariadb",
                "/mariadb/bin/mariadbd",
                "--defaults-file=/mariadb/my.cnf",
                "--datadir=/mariadb/data",
                "--socket=/mariadb/tmp/mysql.sock",
                "--pid-file=/mariadb/tmp/mysql.pid",
                "--tmpdir=/mariadb/tmp",
                "--skip-grant-tables"
            )
        } else {
            listOf(
                daemonBinary.absolutePath,
                "--defaults-file=${cnfFile.absolutePath}",
                "--datadir=${dataDir.absolutePath}",
                "--socket=${socketFile.absolutePath}",
                "--pid-file=${pidFile.absolutePath}",
                "--tmpdir=${tmpDir.absolutePath}",
                "--skip-grant-tables"
            )
        }

        val env = mapOf(
            "PROOT_TMP_DIR" to prootTmp.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "LD_LIBRARY_PATH" to File(mariadbDir, "lib").absolutePath,
            "HOME" to mariadbDir.absolutePath,
            "USER" to "root"
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
