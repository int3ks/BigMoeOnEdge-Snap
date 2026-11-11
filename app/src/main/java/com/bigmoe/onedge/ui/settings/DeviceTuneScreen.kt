package com.bigmoe.onedge.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.DeviceTuneRunner
import com.bigmoe.onedge.core.TuneParam
import com.bigmoe.onedge.core.TuneResult
import java.util.Locale

/**
 * Auto settings: a real test of the settings that depend on this device. Pick a model, Start, and each parameter is
 * measured (current value, then its neighbours, towards the faster side). Each row shows the current and the measured
 * best value with Apply and Retest; Apply all / Retest all at the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceTuneScreen(
    viewModel: DeviceTuneViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ui by viewModel.ui.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshModels() }

    // A test loads a big model for many minutes: keep the screen on, so the app stays in the foreground (a backgrounded
    // app is the first one Android kills when memory is short).
    val view = LocalView.current
    DisposableEffect(ui.running) {
        view.keepScreenOn = ui.running
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AUTO SETTINGS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "Measures the settings that depend on this device: threads for writing and for reading the prompt, the " +
                        "expert cache, parallel I/O lanes, the prompt batch width and the NPU. Every value is really run " +
                        "with the model you pick (twice, the median counts), starting from your current settings and " +
                        "moving towards the faster side. Nothing changes until you apply it. A full test can take half an " +
                        "hour or more; keep the app open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item { ModelChooser(ui, viewModel) }
            ui.message?.let { msg ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(msg, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                        }
                    }
                }
            }
            item { StatusCard(ui, viewModel) }
            ui.report?.driftNote?.let { note ->
                item { Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val any = ui.report?.results?.values?.any { it.best != null } == true
                    Button(onClick = viewModel::applyAll, enabled = any && !ui.running) { Text("Apply all") }
                    OutlinedButton(onClick = viewModel::startAll, enabled = !ui.running && ui.selectedPath != null) {
                        Text(if (ui.report?.results.isNullOrEmpty()) "Start" else "Retest all")
                    }
                }
            }
            items(viewModel.params(), key = { it.name }) { param ->
                ParamRow(
                    param = param,
                    result = ui.report?.results?.get(param),
                    current = ui.current[param],
                    running = ui.running,
                    testingNow = ui.status?.param == param,
                    coresText = viewModel::coresText,
                    onApply = { viewModel.apply(param) },
                    onRetest = { viewModel.retest(param) }
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ModelChooser(ui: DeviceTuneUi, viewModel: DeviceTuneViewModel) {
    var menu by remember { mutableStateOf(false) }
    val name = ui.models.firstOrNull { it.path == ui.selectedPath }?.displayName ?: "No model"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Test model", style = MaterialTheme.typography.labelMedium)
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(
                (if (ui.isMoe) "MoE: cache and I/O lanes are tested too" else "Dense model: no expert cache or I/O lanes to test") +
                    if (ui.npuAvailable) " • NPU available" else " • no NPU",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            OutlinedButton(onClick = { menu = true }, enabled = !ui.running) { Text("Change") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ui.models.forEach { m ->
                    DropdownMenuItem(text = { Text(m.displayName) }, onClick = { menu = false; viewModel.selectModel(m.path) })
                }
            }
        }
    }
}

@Composable
private fun StatusCard(ui: DeviceTuneUi, viewModel: DeviceTuneViewModel) {
    val st = ui.status ?: return
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st.startedAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val elapsed = ((now - st.startedAtMs) / 1000).coerceAtLeast(0)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (st.steps > 0 && st.step > 0) "Testing • step ${st.step} of ${st.steps}" else "Testing",
                style = MaterialTheme.typography.titleSmall
            )
            if (st.steps > 0 && st.step > 0) {
                LinearProgressIndicator(progress = { (st.step - 1).toFloat() / st.steps }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(st.text, style = MaterialTheme.typography.bodyMedium)
            st.last?.let { Text("Last measured: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            Text("%d:%02d elapsed".format(elapsed / 60, elapsed % 60), style = MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick = viewModel::cancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun ParamRow(
    param: TuneParam,
    result: TuneResult?,
    current: Int?,
    running: Boolean,
    testingNow: Boolean,
    coresText: (Int) -> String,
    onApply: () -> Unit,
    onRetest: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val best = result?.best
    val applied = best != null && current == best
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(enabled = result != null) { open = !open }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (result != null) {
                            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                        }
                        Text(param.title, style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        "Now: " + (current?.let { DeviceTuneRunner.display(param, it) } ?: "–") +
                            "  •  Measured: " + when {
                                testingNow -> "testing…"
                                best != null -> DeviceTuneRunner.display(param, best) + gainText(result)
                                result != null -> "no result"
                                else -> "not tested"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (best != null && !applied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "judged by the speed of " + param.metric.label + if (param.live) "" else " • needs a reload per value",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onApply, enabled = best != null && !applied && !running) { Text(if (applied) "Applied" else "Apply") }
                    TextButton(onClick = onRetest, enabled = !running) { Text(if (result == null) "Test" else "Retest") }
                }
            }
            if (open && result != null) {
                Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    result.points.sortedBy { it.value }.forEach { p ->
                        val label = DeviceTuneRunner.display(param, p.value) +
                            if (param == TuneParam.DECODE_CORES || param == TuneParam.PREFILL_CORES) " (${coresText(p.value)})" else ""
                        val speed = when {
                            p.tps != null && p.altTps != null -> "NPU ${fmt(p.tps)} • CPU ${fmt(p.altTps)} tokens/s"
                            p.tps != null -> "${fmt(p.tps)} tokens/s"
                            else -> p.error ?: "failed"
                        }
                        val marks = listOfNotNull(
                            if (p.value == result.startValue) "start" else null,
                            if (p.value == best) "best" else null
                        ).joinToString(", ").let { if (it.isEmpty()) "" else "  [$it]" }
                        Text(
                            "$label: $speed$marks",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (p.value == best) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (p.tps == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (param == TuneParam.NPU_MIN_TOKENS) {
                        Text(
                            "Prompts from this length on go to the NPU: the shortest length from which the NPU was faster than the CPU.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun gainText(r: TuneResult): String {
    if (r.param == TuneParam.NPU_MIN_TOKENS) return ""
    val g = r.gainPct ?: return ""
    if (r.best == r.startValue) return " (already the fastest)"
    return String.format(Locale.US, " (%+.0f %%)", g)
}

private fun fmt(x: Double): String = String.format(Locale.US, "%.1f", x)
