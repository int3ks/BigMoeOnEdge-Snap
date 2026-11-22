package com.bigmoe.onedge.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.RunInfo
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Under the last answer of a turn: `‹ 2/3 ›` to page through the answers Regenerate kept, the model that wrote the
 * shown one, and (tap) the settings it ran with, for comparing models and settings on the same prompt.
 */
@Composable
fun TurnVariantBar(
    info: VariantInfo,
    runInfo: RunInfo?,
    timestamp: Long,
    enabled: Boolean,
    onShow: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (info.total <= 1 && runInfo == null) return
    var open by rememberSaveable(info.userMessageId, info.shown) { mutableStateOf(false) }
    val secondary = MaterialTheme.colorScheme.secondary
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (info.total > 1) {
                IconButton(onClick = { onShow(info.shown - 1) }, enabled = enabled && info.shown > 0, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous answer")
                }
                Text(
                    text = "${info.shown + 1}/${info.total}" + if (info.shown == info.active) "" else " (view)",
                    style = MaterialTheme.typography.labelMedium,
                    color = secondary
                )
                IconButton(onClick = { onShow(info.shown + 1) }, enabled = enabled && info.shown < info.total - 1, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next answer")
                }
            }
            if (runInfo != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { open = !open }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    val aliases by com.bigmoe.onedge.core.ModelAliases.map.collectAsState()
                    Text(
                        text = aliases[runInfo.model] ?: runInfo.model,
                        style = MaterialTheme.typography.labelSmall,
                        color = secondary,
                        maxLines = if (open) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (open) "Hide run details" else "Show run details",
                        tint = secondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        if (info.shown != info.active && !info.isLast) {
            Text(
                text = "The chat below continues from answer ${info.active + 1}/${info.total}.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        if (open && runInfo != null) {
            Text(
                text = runDetails(runInfo, timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = secondary,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 4.dp)
            )
        }
    }
}

private fun runDetails(r: RunInfo, timestamp: Long): String = buildList<String> {
    if (r.path.isNotBlank()) add("From: ${File(r.path).parent ?: r.path}")
    val engine = buildList<String> {
        r.npu?.let { add("NPU prefill ${if (it) "on" else "off"}") }
        when (r.cacheMode) {
            null -> {}
            "off" -> add("expert cache off")
            "auto" -> add("expert cache auto")
            else -> r.cacheMb?.let { add("expert cache $it MiB") }
        }
        r.threads?.let { add("$it threads") }
        r.ctx?.let { add("ctx $it") }
        r.spec?.takeIf { it != "off" }?.let { add("spec $it") }
    }
    if (engine.isNotEmpty()) add(engine.joinToString(" • "))
    val sampling = buildList<String> {
        r.preset?.let { add("preset $it") }
        r.temperature?.let { add("temp ${fmt(it)}") }
        r.topP?.let { add("top-p ${fmt(it)}") }
        r.topK?.let { add("top-k $it") }
        r.minP?.let { add("min-p ${fmt(it)}") }
        r.repeatPenalty?.let { add("repeat ${fmt(it)}") }
    }
    if (sampling.isNotEmpty()) add(sampling.joinToString(" • "))
    r.systemPrompt?.takeIf { it.isNotBlank() }?.let { add("System prompt: $it") }
    r.minFreeMb?.let { add("Lowest free RAM during the answer: $it MiB") }
    r.maxSwapMb?.let { add("Most of the app in zram during the answer: $it MiB") }
    r.majfltPerToken?.let { add("Major page faults per token: ${String.format(Locale.US, "%.0f", it)} (0 = everything was in RAM)") }
    add(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(timestamp)))
}.joinToString("\n")

private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
