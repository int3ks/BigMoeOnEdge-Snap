package com.bigmoe.onedge.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bigmoe.onedge.OnEdgeApplication
import com.bigmoe.onedge.core.ModelFile
import com.bigmoe.onedge.core.ModelManager
import com.bigmoe.onedge.core.PerfAnalyzer
import com.bigmoe.onedge.core.PerfLog
import com.bigmoe.onedge.core.SettingsAdvice
import com.bigmoe.onedge.data.local.entity.PerfEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Performance log (core/PerfLog.kt): what was recorded, the computed summary per model, an export for a chat with
 * Claude (intro + summary + CSV) and "Ask a model": a local model gets the summary and suggests settings in a new
 * chat, where they can be applied.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PerfLogScreen(
    models: List<ModelFile>,
    onAsk: (modelPath: String, prompt: String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val container = (context.applicationContext as OnEdgeApplication).container
    val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf<List<PerfEventEntity>>(emptyList()) }
    var about by remember { mutableStateOf<String?>(null) }
    var askOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    // Export: log files of the period waiting for the user's yes/no (only offered while "Write to file" is on).
    var offerLogs by remember { mutableStateOf<List<File>?>(null) }
    val turnCount = remember { PerfLog.counts("turn") ?: kotlinx.coroutines.flow.flowOf(0) }
    val turns by turnCount.collectAsState(initial = 0)

    LaunchedEffect(reloadKey, turns) { events = withContext(Dispatchers.IO) { PerfLog.all() } }
    val names = remember(events) { events.map { it.model }.distinct().sorted() }
    val ram = remember { totalRamMb(context) }
    val report = remember(events, about) { PerfAnalyzer.report(events, about, ram) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PERFORMANCE LOG", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Every model load, answer and crash is recorded with the settings it ran with, the speed, the prompt " +
                    "and context size, free RAM and the device's heat. The summary below is computed from it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = about == null, onClick = { about = null }, label = { Text("All models") })
                names.forEach { n -> FilterChip(selected = about == n, onClick = { about = n }, label = { Text(n, maxLines = 1) }) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { askOpen = true }, enabled = events.any { it.kind == "turn" }) { Text("Ask a model") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val settings = container.settingsDataStore.settingsFlow.first()
                        val since = events.firstOrNull()?.at ?: 0L
                        val logs = if (settings.logToFile) {
                            withContext(Dispatchers.IO) { com.bigmoe.onedge.core.LogFiles.filesSince(since) }
                        } else emptyList()
                        if (logs.isEmpty()) exportReport(context, container, report, events, emptyList()) else offerLogs = logs
                    }
                }, enabled = events.isNotEmpty()) { Text("Export for Claude") }
                TextButton(onClick = { clearOpen = true }, enabled = events.isNotEmpty()) { Text("Clear log") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }

    if (askOpen) {
        AskDialog(
            models = models.filter { !ModelManager.isProjectorName(it.path) },
            about = about,
            onDismiss = { askOpen = false },
            onAsk = { model ->
                askOpen = false
                scope.launch {
                    val settings = container.settingsDataStore.settingsFlow.first()
                    onAsk(model.path, SettingsAdvice.localPrompt(report, settings, about))
                }
            }
        )
    }
    offerLogs?.let { logs ->
        val mb = logs.sumOf { it.length() } / (1024.0 * 1024.0)
        AlertDialog(
            onDismissRequest = { offerLogs = null },
            title = { Text("Include the log file?") },
            text = {
                Text(
                    "${logs.size} log file(s) from this period (${String.format(Locale.US, "%.1f", mb)} MB). The end of each " +
                        "(up to ${LOG_TAIL_KB} KB) goes into the export. Logs can contain parts of your prompts."
                )
            },
            confirmButton = {
                TextButton(onClick = { offerLogs = null; scope.launch { exportReport(context, container, report, events, logs) } }) { Text("Include") }
            },
            dismissButton = {
                TextButton(onClick = { offerLogs = null; scope.launch { exportReport(context, container, report, events, emptyList()) } }) { Text("Without") }
            }
        )
    }
    if (clearOpen) {
        AlertDialog(
            onDismissRequest = { clearOpen = false },
            title = { Text("Clear the performance log?") },
            text = { Text("All recorded loads, answers and crashes are deleted. Chats are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    clearOpen = false
                    scope.launch { withContext(Dispatchers.IO) { PerfLog.clear() }; reloadKey++ }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { clearOpen = false }) { Text("Keep") } }
        )
    }
}

@Composable
private fun AskDialog(models: List<ModelFile>, about: String?, onDismiss: () -> Unit, onAsk: (ModelFile) -> Unit) {
    var picked by remember { mutableStateOf<ModelFile?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which model should answer?") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "It gets the summary " + (about?.let { "for $it" } ?: "for all models") + " and suggests settings in a " +
                        "new chat. Nothing changes until you tap Apply there. A model that is not loaded is loaded first.",
                    style = MaterialTheme.typography.bodySmall
                )
                models.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { picked = m }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = picked == m, onClick = { picked = m })
                        Text(m.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { picked?.let(onAsk) }, enabled = picked != null) { Text("Ask") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private const val LOG_TAIL_KB = 300L

private suspend fun exportReport(
    context: Context,
    container: com.bigmoe.onedge.di.AppContainer,
    report: String,
    events: List<PerfEventEntity>,
    logs: List<File>
) {
    val settings = container.settingsDataStore.settingsFlow.first()
    val text = buildString {
        append(SettingsAdvice.exportIntro(settings)).append("\n\n").append(report)
        append("\n\n## All entries (CSV)\n```csv\n").append(PerfAnalyzer.csv(events)).append("```\n")
        if (logs.isNotEmpty()) {
            append("\n## Log files (engine and llama.cpp; the end of each file)\n")
            withContext(Dispatchers.IO) {
                logs.forEach { f ->
                    append("\n### ${f.name}\n```\n").append(com.bigmoe.onedge.core.LogFiles.tail(f, LOG_TAIL_KB * 1024)).append("\n```\n")
                }
            }
        }
    }
    shareText(context, text)
}

private fun totalRamMb(context: Context): Long {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return -1
    return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / (1024L * 1024L)
}

private suspend fun shareText(context: Context, text: String) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "conversation_exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        File(dir, "bigmoe-perf-log-$stamp.md").apply { writeText(text) }
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share performance log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
