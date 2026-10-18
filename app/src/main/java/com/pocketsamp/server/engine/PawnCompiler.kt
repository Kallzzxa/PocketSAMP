package com.pocketsamp.server.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CompileResult(
    val success: Boolean,
    val output: String
)

/**
 * On-device Pawn compiler via JNI (pawncc 3.10.10).
 * Native bridge adapted from novusr/Pawn-MC (Apache-2.0): the compiler runs
 * in-process through pc_compile(), no binary extraction or exec permission needed.
 */
class PawnCompiler {

    companion object {
        private const val TAG = "PawnCompiler"
        private const val LIB_NAME = "pawncc_jni"

        @Volatile
        private var libLoaded = false

        @Synchronized
        fun ensureLoaded(): Boolean {
            if (libLoaded) return true
            return try {
                System.loadLibrary(LIB_NAME)
                libLoaded = true
                Log.i(TAG, "Loaded native library: $LIB_NAME")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load $LIB_NAME", e)
                false
            }
        }
    }

    // JNI entry points (implemented in app/src/main/cpp/Compiler.cpp)
    private external fun compile(args: Array<String>): String
    private external fun getOutput(): String
    private external fun getErrors(): String

    /**
     * Compile a .pwn file to .amx.
     * @param pwnFile the source file
     * @param includeDirs extra -i include dirs (defaults to the file's parent dir + include/ subfolder)
     */
    suspend fun compile(pwnFile: File, includeDirs: List<File> = emptyList()): CompileResult =
        withContext(Dispatchers.IO) {
            if (!ensureLoaded()) {
                return@withContext CompileResult(false, "Failed to load native pawncc library")
            }
            if (!pwnFile.exists()) {
                return@withContext CompileResult(false, "File not found: ${pwnFile.absolutePath}")
            }

            val workDir = pwnFile.parentFile ?: return@withContext CompileResult(false, "No parent dir")
            val amxFile = File(workDir, pwnFile.nameWithoutExtension + ".amx")

            val args = mutableListOf(
                "pawncc",
                pwnFile.absolutePath,
                "-o${amxFile.absolutePath}",
                "-i${workDir.absolutePath}"
            )
            // auto-add include/ subfolder if present (SA-MP convention)
            val defaultInclude = File(workDir, "include")
            if (defaultInclude.isDirectory) args.add("-i${defaultInclude.absolutePath}")
            includeDirs.filter { it.isDirectory }.forEach { args.add("-i${it.absolutePath}") }
            args.add("-d0")

            return@withContext try {
                val raw = compile(args.toTypedArray())
                // raw = "Exit code: N\n<output>"
                val exitCode = raw.lineSequence().firstOrNull()
                    ?.removePrefix("Exit code:")?.trim()?.toIntOrNull() ?: -1
                val output = raw.substringAfter('\n', "").trim()
                val success = exitCode == 0 && amxFile.exists()
                val header = "pawncc ${pwnFile.name} -> ${amxFile.name} (exit=$exitCode)\n"
                CompileResult(success, header + output.ifBlank { "(no output)" })
            } catch (e: Exception) {
                Log.e(TAG, "Compile failed", e)
                CompileResult(false, "Compile error: ${e.message}")
            }
        }
}
