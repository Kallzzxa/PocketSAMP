package com.pocketsamp.server.engine

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CompileResult(
    val success: Boolean,
    val output: String
)

/**
 * On-device Pawn compiler (pawncc 3.10.10, cross-compiled for arm64).
 * Extracts the bundled pawncc binary on first use, then runs it via ProcessBuilder.
 */
class PawnCompiler(private val context: Context) {

    companion object {
        private const val TAG = "PawnCompiler"
        private const val ASSET_NAME = "pawncc_arm64"
    }

    private fun binaryFile(): File = File(context.filesDir, "bin_pawn/pawncc")

    /** Extract pawncc from assets if missing, make executable. Returns the binary or null. */
    suspend fun ensureBinary(): File? = withContext(Dispatchers.IO) {
        try {
            val bin = binaryFile()
            if (bin.exists() && bin.canExecute()) return@withContext bin
            bin.parentFile?.mkdirs()
            // Re-extract if missing or not executable (fixes permission denied)
            if (bin.exists()) bin.delete()
            context.assets.open(ASSET_NAME).use { input ->
                bin.outputStream().use { output -> input.copyTo(output) }
            }
            // Os.chmod is more reliable than File.setExecutable on Android
            try {
                Os.chmod(bin.absolutePath, 448) // 0700
            } catch (e: Exception) {
                Log.w(TAG, "Os.chmod failed, trying setExecutable", e)
                bin.setExecutable(true, true)
            }
            if (!bin.canExecute()) {
                // Last resort: chmod via shell
                try {
                    Runtime.getRuntime().exec(arrayOf("chmod", "755", bin.absolutePath)).waitFor()
                } catch (e: Exception) {
                    Log.w(TAG, "shell chmod failed", e)
                }
            }
            Log.i(TAG, "pawncc extracted to ${bin.absolutePath}, executable=${bin.canExecute()}")
            if (!bin.canExecute()) {
                Log.e(TAG, "pawncc is not executable after chmod attempts")
                return@withContext null
            }
            bin
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract pawncc", e)
            null
        }
    }

    /**
     * Compile a .pwn file to .amx.
     * @param pwnFile the source file (must be a real file path, not a content URI)
     * @param includeDirs extra -i include dirs (defaults to the file's parent dir)
     */
    suspend fun compile(pwnFile: File, includeDirs: List<File> = emptyList()): CompileResult =
        withContext(Dispatchers.IO) {
            val bin = ensureBinary()
                ?: return@withContext CompileResult(false, "Failed to deploy pawncc binary")
            if (!pwnFile.exists()) {
                return@withContext CompileResult(false, "File not found: ${pwnFile.absolutePath}")
            }

            val workDir = pwnFile.parentFile ?: context.filesDir
            val amxFile = File(workDir, pwnFile.nameWithoutExtension + ".amx")

            val cmd = mutableListOf(
                bin.absolutePath,
                pwnFile.absolutePath,
                "-o${amxFile.absolutePath}",
                "-i${workDir.absolutePath}"
            )
            includeDirs.filter { it.exists() }.forEach { cmd.add("-i${it.absolutePath}") }
            // Common SA-MP friendly flags: pack, no debug spam
            cmd.add("-d0")

            return@withContext try {
                val proc = ProcessBuilder(cmd)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.bufferedReader().readText()
                val exit = proc.waitFor()
                val success = exit == 0 && amxFile.exists()
                val header = "pawncc ${pwnFile.name} -> ${amxFile.name} (exit=$exit)\n"
                CompileResult(success, header + output.ifBlank { "(no output)" })
            } catch (e: Exception) {
                Log.e(TAG, "Compile failed", e)
                CompileResult(false, "Compile error: ${e.message}")
            }
        }
}
