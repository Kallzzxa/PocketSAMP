package com.pocketsamp.server.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry

class AssetExtractor(private val context: Context) {

    companion object {
        private const val TAG = "AssetExtractor"
        private const val PREF_NAME = "pocketsamp_assets"
        private const val KEY_INSTALLED_VERSION = "assets_installed_version"
        private const val CURRENT_VERSION = 1
    }

    suspend fun isExtracted(): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val installedVersion = prefs.getInt(KEY_INSTALLED_VERSION, 0)
        val rootfsDir = File(context.filesDir, "rootfs_i386")
        val sampDir = File(context.filesDir, "samp_server")
        val mariadbDir = File(context.filesDir, "mariadb")
        
        installedVersion == CURRENT_VERSION && rootfsDir.exists() && sampDir.exists() && mariadbDir.exists()
    }

    suspend fun extractAll(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir

        // 1. Extract rootfs_i386.tar.gz
        onProgress("Extracting x86 C-Runtime...")
        val rootfsDir = File(filesDir, "rootfs_i386")
        if (!rootfsDir.exists()) rootfsDir.mkdirs()
        extractTarGzAsset("rootfs_i386.tar.gz", rootfsDir)

        // 2. Extract samp_base.tar.gz
        onProgress("Setting up SA-MP server files...")
        val sampDir = File(filesDir, "samp_server")
        if (!sampDir.exists()) sampDir.mkdirs()
        extractTarGzAsset("samp_base.tar.gz", sampDir)
        
        // Ensure samp03svr has executable bit set
        File(sampDir, "samp03svr").setExecutable(true, false)
        File(sampDir, "announce").setExecutable(true, false)

        // 3. Extract mariadb_arm64.tar.gz
        onProgress("Deploying MariaDB ARM64 engine...")
        val mariadbDir = File(filesDir, "mariadb")
        if (!mariadbDir.exists()) mariadbDir.mkdirs()
        extractTarGzAsset("mariadb_arm64.tar.gz", mariadbDir)
        
        // Ensure mariadb binaries are executable
        File(mariadbDir, "bin/mariadbd").setExecutable(true, false)
        File(mariadbDir, "bin/mariadb-install-db").setExecutable(true, false)

        // 4. Copy my.cnf template
        onProgress("Configuring low-memory database...")
        copyAssetToFile("my.cnf", File(mariadbDir, "my.cnf"))

        // Save extraction state
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_INSTALLED_VERSION, CURRENT_VERSION).apply()
        onProgress("Initialization complete!")
    }

    private fun extractTarGzAsset(assetName: String, outputDir: File) {
        context.assets.open(assetName).use { inputStream ->
            GZIPInputStream(inputStream).use { gzipStream ->
                TarArchiveInputStream(gzipStream).use { tarInput ->
                    var entry: TarArchiveEntry? = tarInput.nextTarEntry
                    val buffer = ByteArray(8192)
                    while (entry != null) {
                        val outputFile = File(outputDir, entry.name)
                        if (entry.isDirectory) {
                            outputFile.mkdirs()
                        } else {
                            outputFile.parentFile?.mkdirs()
                            if (entry.isSymbolicLink) {
                                try {
                                    java.nio.file.Files.createSymbolicLink(
                                        outputFile.toPath(),
                                        java.nio.file.Paths.get(entry.linkName)
                                    )
                                } catch (e: Exception) {
                                    Log.w(TAG, "Symlink creation skipped: ${entry.name} -> ${entry.linkName}")
                                }
                            } else {
                                FileOutputStream(outputFile).use { fos ->
                                    var count: Int
                                    while (tarInput.read(buffer).also { count = it } != -1) {
                                        fos.write(buffer, 0, count)
                                    }
                                }
                                // Preserve execution bits
                                if ((entry.mode and 0x49) != 0) { // 0111 octal
                                    outputFile.setExecutable(true, false)
                                }
                            }
                        }
                        entry = tarInput.nextTarEntry
                    }
                }
            }
        }
    }

    private fun copyAssetToFile(assetName: String, destination: File) {
        context.assets.open(assetName).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
    }
}
