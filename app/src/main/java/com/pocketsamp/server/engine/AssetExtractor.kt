package com.pocketsamp.server.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

class AssetExtractor(private val context: Context) {

    companion object {
        private const val TAG = "AssetExtractor"
        private const val PREF_NAME = "pocketsamp_assets"
        private const val KEY_INSTALLED_VERSION = "assets_installed_version"
        private const val CURRENT_VERSION = 3
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

        // 1. Extract rootfs_i386
        onProgress("Extracting x86 C-Runtime...")
        val rootfsDir = File(filesDir, "rootfs_i386")
        if (!rootfsDir.exists()) rootfsDir.mkdirs()
        extractArchiveAsset("rootfs_i386", rootfsDir)

        // 2. Extract samp_base
        onProgress("Setting up SA-MP server files...")
        val sampDir = File(filesDir, "samp_server")
        if (!sampDir.exists()) sampDir.mkdirs()
        extractArchiveAsset("samp_base", sampDir)

        // Ensure samp03svr has executable bit set
        File(sampDir, "samp03svr").setExecutable(true, false)
        File(sampDir, "announce").setExecutable(true, false)

        // 3. Extract mariadb_arm64
        onProgress("Deploying MariaDB ARM64 engine...")
        val mariadbDir = File(filesDir, "mariadb")
        if (!mariadbDir.exists()) mariadbDir.mkdirs()
        extractArchiveAsset("mariadb_arm64", mariadbDir)

        // Ensure binaries have executable bit set if present
        File(mariadbDir, "bin/mariadbd").let { if (it.exists()) it.setExecutable(true, false) }
        File(mariadbDir, "bin/mariadb-install-db").let { if (it.exists()) it.setExecutable(true, false) }

        // 4. Copy my.cnf template
        onProgress("Configuring low-memory database...")
        copyAssetToFile("my.cnf", File(mariadbDir, "my.cnf"))

        // Save extraction state
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_INSTALLED_VERSION, CURRENT_VERSION).apply()
        onProgress("Initialization complete!")
    }

    private fun extractArchiveAsset(baseName: String, outputDir: File) {
        // AAPT sometimes strips .gz during APK packaging, leaving .tar
        val candidates = listOf(
            "$baseName.tar.gz",
            "$baseName.tar",
            "$baseName.tgz",
            baseName
        )

        var openedStream: InputStream? = null
        var matchedName: String? = null

        for (candidate in candidates) {
            try {
                openedStream = context.assets.open(candidate)
                matchedName = candidate
                break
            } catch (ignored: Exception) {
            }
        }

        if (openedStream == null) {
            throw IllegalStateException("Asset not found for $baseName (checked: $candidates)")
        }

        Log.i(TAG, "Unpacking asset: $matchedName into ${outputDir.absolutePath}")

        openedStream.use { rawInput ->
            val buffered = BufferedInputStream(rawInput)
            buffered.mark(4)
            val b1 = buffered.read()
            val b2 = buffered.read()
            buffered.reset()

            // Check GZIP magic bytes: 0x1F, 0x8B
            val isGzip = (b1 == 0x1f && b2 == 0x8b)
            val tarStream: InputStream = if (isGzip) {
                GZIPInputStream(buffered)
            } else {
                buffered
            }

            TarArchiveInputStream(tarStream).use { tarInput ->
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
                                java.nio.file.Files.deleteIfExists(outputFile.toPath())
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
                            if ((entry.mode and 0x49) != 0) { // Executable bit (0111 octal)
                                outputFile.setExecutable(true, false)
                            }
                        }
                    }
                    entry = tarInput.nextTarEntry
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
