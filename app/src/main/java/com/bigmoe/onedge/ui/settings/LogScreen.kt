package com.bigmoe.onedge.ui.settings

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.bigmoe.onedge.core.EngineNativeBridge
import com.bigmoe.onedge.ui.navigation.MainNavButtons
import com.bigmoe.onedge.ui.navigation.TopTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val LOG_KEEP_LINES = 6000
private const val LOG_COPY_LINES = 3000
private const val LOG_POLL_MS = 400L

/**
 * llama.cpp's and the engine's log (what goes to stderr), kept natively from app start (`nativeLogStart`): follows
 * live, can be filtered, copied, shared and cleared. While recording it is also appended to a file under
 * `<workspace>/.bigmoe/logs` (see [com.bigmoe.onedge.core.LogFiles]). One of the main pages (button in every top bar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val bridge = remember { EngineNativeBridge() }
    val settings by viewModel.settingsState.collectAsState()
    val recording = settings?.logCapture == true
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var follow by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    // Poll for new lines; the ring lives natively, so opening the page again shows everything that is still kept.
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            val chunk = withContext(Dispatchers.IO) { runCatching { bridge.nativeLogRead(last) }.getOrNull() }
            if (chunk != null) {
                val text = String(chunk, Charsets.UTF_8)
                val nl = text.indexOf('\n')
                if (nl > 0) {
                    val newest = text.substring(0, nl).toLongOrNull() ?: last
                    val fresh = text.substring(nl + 1).split('\n').filter { it.isNotEmpty() }
                    if (fresh.isNotEmpty()) lines = (lines + fresh).takeLast(LOG_KEEP_LINES)
                    last = newest
                }
            }
            delay(LOG_POLL_MS)
        }
    }

    val shown = remember(lines, filter) {
        if (filter.isBlank()) lines else lines.filter { it.contains(filter.trim(), ignoreCase = true) }
    }
    LaunchedEffect(shown.size, follow) {
        if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LOG", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold) },
                actions = { MainNavButtons(TopTab.LOG) }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Record log", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Off by default. While on, what llama.cpp and the engine print is kept here and written to a file in the " +
                            "workspace (.bigmoe/logs), so it is still there after a crash. It is cheap, but if the tablet ever stalls " +
                            "with it on, switch it off. Starts with the app, so switch it on before loading a model to see the load.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = recording, onCheckedChange = { viewModel.updateLogCapture(it) })
            }
            val logFile = com.bigmoe.onedge.core.LogFiles.currentPath
            if (recording && logFile != null) {
                Text(
                    "Also written to: $logFile",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(shown.takeLast(LOG_COPY_LINES).joinToString("\n")))
                    android.widget.Toast.makeText(context, "Copied the last ${minOf(shown.size, LOG_COPY_LINES)} lines", android.widget.Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Default.ContentCopy, contentDescription = null); Text("  Copy") }
                OutlinedButton(onClick = { shareLog(context, shown) }) { Icon(Icons.Default.Share, contentDescription = null); Text("  Share") }
                OutlinedButton(onClick = {
                    runCatching { bridge.nativeLogClear() }
                    lines = emptyList()
                }) { Icon(Icons.Default.Delete, contentDescription = null); Text("  Clear") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("Filter") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("Follow") })
            }
            Text(
                "${shown.size} of ${lines.size} lines" + if (lines.size >= LOG_KEEP_LINES) " (newest $LOG_KEEP_LINES kept)" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (!recording && lines.isEmpty()) {
                Text("Nothing recorded. Switch \"Record log\" on, then load or use a model.", style = MaterialTheme.typography.bodyMedium)
            }
            val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(shown.size) { i ->
                        val line = shown[i]
                        val color = when {
                            line.contains("error", ignoreCase = true) || line.contains("failed", ignoreCase = true) -> MaterialTheme.colorScheme.error
                            line.contains("warn", ignoreCase = true) -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Text(line, style = mono, color = color)
                    }
                }
            }
        }
    }
}

/** The shown lines as a text file through the chooser (a file, so a long log does not hit the intent size limit). */
private fun shareLog(context: android.content.Context, lines: List<String>) {
    if (lines.isEmpty()) {
        android.widget.Toast.makeText(context, "The log is empty.", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val dir = File(context.cacheDir, "conversation_exports").apply { mkdirs() } // a temporary-share folder the FileProvider already exposes
    val file = File(dir, "bigmoe-log.txt")
    file.writeText(lines.joinToString("\n") + "\n")
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
