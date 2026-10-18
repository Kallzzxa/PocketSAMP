package com.pocketsamp.server.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

data class QueryResult(
    val columns: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val affectedRows: Int = 0,
    val isSelect: Boolean = false,
    val error: String? = null
)

class DatabaseManager {

    companion object {
        private const val DB_URL_BASE = "jdbc:mariadb://127.0.0.1:3306"
        private const val DB_USER = "root"
        private const val DB_PASS = ""

        init {
            try {
                Class.forName("org.mariadb.jdbc.Driver")
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private fun getConnection(database: String? = null): Connection {
        val url = if (database.isNullOrBlank()) {
            "$DB_URL_BASE?connectTimeout=3000"
        } else {
            "$DB_URL_BASE/$database?connectTimeout=3000"
        }
        return DriverManager.getConnection(url, DB_USER, DB_PASS)
    }

    suspend fun isServerAlive(): Boolean = withContext(Dispatchers.IO) {
        try {
            getConnection().use { conn ->
                conn.isValid(2)
            }
        } catch (e: Throwable) {
            false
        }
    }

    suspend fun listDatabases(): List<String> = withContext(Dispatchers.IO) {
        val dbs = mutableListOf<String>()
        try {
            getConnection().use { conn ->
                val rs: ResultSet = conn.metaData.catalogs
                while (rs.next()) {
                    val dbName = rs.getString(1)
                    if (dbName !in listOf("information_schema", "performance_schema", "mysql", "sys")) {
                        dbs.add(dbName)
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        dbs
    }

    suspend fun listTables(database: String): List<String> = withContext(Dispatchers.IO) {
        val tables = mutableListOf<String>()
        try {
            getConnection(database).use { conn ->
                val rs = conn.metaData.getTables(database, null, "%", arrayOf("TABLE"))
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"))
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        tables
    }

    suspend fun executeQuery(database: String?, sql: String): QueryResult = withContext(Dispatchers.IO) {
        val cleanSql = sql.trim()
        if (cleanSql.isEmpty()) {
            return@withContext QueryResult(error = "Query is empty")
        }

        try {
            getConnection(database).use { conn ->
                conn.createStatement().use { stmt ->
                    val isResultSet = stmt.execute(cleanSql)
                    if (isResultSet) {
                        val rs = stmt.resultSet
                        val meta = rs.metaData
                        val colCount = meta.columnCount
                        val columns = (1..colCount).map { meta.getColumnLabel(it) }
                        val rows = mutableListOf<List<String>>()

                        var count = 0
                        while (rs.next() && count < 200) { // Limit 200 preview rows
                            val row = (1..colCount).map { rs.getString(it) ?: "NULL" }
                            rows.add(row)
                            count++
                        }

                        QueryResult(
                            columns = columns,
                            rows = rows,
                            isSelect = true
                        )
                    } else {
                        QueryResult(
                            affectedRows = stmt.updateCount,
                            isSelect = false
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            QueryResult(error = e.message ?: "Database query error")
        }
    }

    suspend fun importSql(database: String, inputStream: InputStream): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            getConnection(database).use { conn ->
                conn.autoCommit = false
                val reader = BufferedReader(InputStreamReader(inputStream))
                val statementBuilder = StringBuilder()
                var line: String? = reader.readLine()

                conn.createStatement().use { stmt ->
                    while (line != null) {
                        val trimmed = line!!.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("--") && !trimmed.startsWith("/*")) {
                            statementBuilder.append(line).append("\n")
                            if (trimmed.endsWith(";")) {
                                val query = statementBuilder.toString().trim()
                                if (query.isNotEmpty()) {
                                    stmt.execute(query)
                                }
                                statementBuilder.clear()
                            }
                        }
                        line = reader.readLine()
                    }
                }
                conn.commit()
            }
            Pair(true, "Database imported successfully")
        } catch (e: Throwable) {
            Pair(false, e.message ?: "SQL Import failed")
        }
    }

    suspend fun dropDatabase(database: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            getConnection(null).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("DROP DATABASE `${database.replace("`", "")}`")
                }
            }
            Pair(true, "Database '$database' deleted")
        } catch (e: Throwable) {
            Pair(false, e.message ?: "Failed to delete database")
        }
    }

    /** Generate a mysqldump-style SQL dump of the given database. */
    suspend fun exportSql(database: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val safeDb = database.replace("`", "")
            val sb = StringBuilder()
            sb.appendLine("-- PocketSAMP database dump: $safeDb")
            sb.appendLine()
            getConnection(safeDb).use { conn ->
                conn.createStatement().use { stmt ->
                    // Table structures
                    val tables = mutableListOf<String>()
                    stmt.executeQuery("SHOW TABLES").use { rs ->
                        while (rs.next()) tables.add(rs.getString(1))
                    }
                    for (table in tables) {
                        sb.appendLine("-- Table: $table")
                        stmt.executeQuery("SHOW CREATE TABLE `$table`").use { rs ->
                            if (rs.next()) {
                                sb.appendLine(rs.getString(2) + ";")
                            }
                        }
                        sb.appendLine()
                        // Table data
                        val cols = mutableListOf<String>()
                        stmt.executeQuery("SELECT * FROM `$table` LIMIT 0").use { rs ->
                            val meta = rs.metaData
                            for (i in 1..meta.columnCount) cols.add(meta.getColumnName(i))
                        }
                        stmt.executeQuery("SELECT * FROM `$table`").use { rs ->
                            val meta = rs.metaData
                            while (rs.next()) {
                                val values = (1..meta.columnCount).map { i ->
                                    val v = rs.getString(i)
                                    if (v == null) "NULL" else "'" + v.replace("'", "''") + "'"
                                }
                                sb.appendLine("INSERT INTO `$table` (${cols.joinToString(", ") { "`$it`" }}) VALUES (${values.joinToString(", ")});")
                            }
                        }
                        sb.appendLine()
                    }
                }
            }
            Pair(true, sb.toString())
        } catch (e: Throwable) {
            Pair(false, e.message ?: "Export failed")
        }
    }
}
