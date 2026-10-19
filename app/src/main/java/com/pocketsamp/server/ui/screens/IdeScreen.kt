package com.pocketsamp.server.ui.screens

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketsamp.server.engine.PawnCompiler
import com.pocketsamp.server.config.ServerDirectoryManager
import com.pocketsamp.server.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

private val TEXT_EXTENSIONS = setOf("pwn", "inc", "txt", "cfg", "ini", "json", "md", "log")

/**
 * Mini IDE: folder browser + code editor + on-device Pawn compiler.
 * Adapted from the explorer/editor concept of rezzylrn/apk-compiler.
 */
@Composable
fun IdeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val compiler = remember { PawnCompiler() }

    val externalRoot = Environment.getExternalStorageDirectory()
    // Default to the PocketSAMP server directory chosen on the Dashboard
    val serverDir = remember { ServerDirectoryManager.getServerDirectory(context) }
    var currentDir by remember { mutableStateOf(serverDir) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }

    var openFile by remember { mutableStateOf<File?>(null) }
    var editorText by remember { mutableStateOf("") }
    var isDirty by remember { mutableStateOf(false) }

    var compileOutput by remember { mutableStateOf<String?>(null) }
    var isCompiling by remember { mutableStateOf(false) }
    var showOutput by remember { mutableStateOf(false) }

    fun refreshFiles() {
        val list = currentDir.listFiles()?.toList().orEmpty()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        files = list
    }

    LaunchedEffect(currentDir) { refreshFiles() }

    fun openTextFile(f: File) {
        if (f.length() > 512 * 1024) {
            Toast.makeText(context, "File too large (>512 KB)", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            editorText = f.readText(Charsets.UTF_8)
            openFile = f
            isDirty = false
            compileOutput = null
            showOutput = false
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to read file", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveFile() {
        val f = openFile ?: return
        scope.launch {
            try {
                f.writeText(editorText, Charsets.UTF_8)
                isDirty = false
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun compileFile() {
        val f = openFile ?: return
        if (isDirty) saveFile()
        scope.launch {
            isCompiling = true
            showOutput = true
            compileOutput = "Compiling ${f.name}..."
            // Include dirs: file's own folder, ./include subfolder,
            // and <server_dir>/pawno/include (SA-MP convention)
            val includes = mutableListOf<File>()
            val localInclude = File(f.parentFile, "include")
            if (localInclude.isDirectory) includes.add(localInclude)
            val pawnoInclude = File(serverDir, "pawno/include")
            if (pawnoInclude.isDirectory) includes.add(pawnoInclude)
            val result = compiler.compile(f, includes)
            compileOutput = result.output
            isCompiling = false
            if (result.success) {
                Toast.makeText(context, "Compiled: ${f.nameWithoutExtension}.amx", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (openFile != null) "Editor${if (isDirty) " •" else ""}" else "IDE",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = openFile?.absolutePath ?: currentDir.absolutePath,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted,
                    maxLines = 1
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (openFile != null) {
                    // Save
                    IconButton(
                        onClick = { saveFile() },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceCard)
                    ) {
                        Icon(Icons.Default.Save, "Save", tint = AccentCyan, modifier = Modifier.size(18.dp))
                    }
                    // Compile (only for .pwn)
                    if (openFile!!.extension.lowercase() == "pwn") {
                        IconButton(
                            onClick = { compileFile() },
                            enabled = !isCompiling,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceCard)
                        ) {
                            if (isCompiling) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Build, "Compile", tint = StatusGreen, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    // Close editor
                    IconButton(
                        onClick = { openFile = null; compileOutput = null; showOutput = false },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceCard)
                    ) {
                        Icon(Icons.Default.Close, "Close", tint = TextMuted, modifier = Modifier.size(18.dp))
                    }
                } else {
                    // Up one level
                    IconButton(
                        onClick = {
                            currentDir.parentFile?.let {
                                if (it.absolutePath.startsWith(externalRoot.absolutePath)) currentDir = it
                            }
                        },
                        enabled = currentDir.absolutePath != externalRoot.absolutePath,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceCard)
                    ) {
                        Icon(Icons.Default.ArrowUpward, "Up", tint = AccentCyan, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        if (openFile != null) {
            // Code editor
            OutlinedTextField(
                value = editorText,
                onValueChange = { editorText = it; isDirty = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TextPrimary,
                    lineHeight = 18.sp
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentCyan,
                    unfocusedBorderColor = SurfaceCardBorder,
                    focusedContainerColor = SurfaceCard,
                    unfocusedContainerColor = SurfaceCard
                ),
                shape = RoundedCornerShape(12.dp)
            )

            // Compiler output
            if (showOutput && compileOutput != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF07080B))
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = compileOutput!!,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = if (compileOutput!!.contains("exit=0")) StatusGreen else TextSecondary
                    )
                }
            }
        } else {
            // File browser
            if (files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceCard)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Empty folder", color = TextMuted, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(files, key = { it.absolutePath }) { f ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceCard)
                                .clickable {
                                    if (f.isDirectory) {
                                        currentDir = f
                                    } else if (f.extension.lowercase() in TEXT_EXTENSIONS) {
                                        openTextFile(f)
                                    } else {
                                        Toast.makeText(context, "Binary file can't be edited", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = if (f.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                contentDescription = null,
                                tint = if (f.isDirectory) AccentOrange else
                                    if (f.extension.lowercase() == "pwn") StatusGreen else TextMuted,
                                modifier = Modifier.size(22.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(f.name, color = TextPrimary, fontSize = 13.sp, maxLines = 1)
                                Text(
                                    if (f.isDirectory) "${f.listFiles()?.size ?: 0} items"
                                    else "${f.length() / 1024} KB",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            // Hint
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceCard)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.FolderOpen, null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                Text(
                    "Browsing server folder. Tap a .pwn to edit, hit build to compile — includes are read from pawno/include",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}
