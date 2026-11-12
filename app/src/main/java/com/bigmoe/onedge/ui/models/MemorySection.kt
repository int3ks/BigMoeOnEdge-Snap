package com.bigmoe.onedge.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.CacheMode
import com.bigmoe.onedge.core.MemoryEstimate
import com.bigmoe.onedge.core.MemoryNow
import com.bigmoe.onedge.core.ModelManager
import com.bigmoe.onedge.core.ModelMemoryOptions
import com.bigmoe.onedge.core.ModelMemoryRecord
import com.bigmoe.onedge.data.local.datastore.EngineSettings

private const val MIB = 1024L * 1024L

/**
 * A model's own memory choices (NPU prefill, expert cache) and what the model will take in RAM with them: an estimate
 * from the file before the first load, and what was really seen of the free RAM afterwards. It only warns, it never
 * blocks a load.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemorySection(
    options: ModelMemoryOptions,
    settings: EngineSettings,
    estimate: MemoryEstimate?,
    record: ModelMemoryRecord?,
    now: MemoryNow?,
    deviceRamBytes: Long,
    npuAvailable: Boolean,
    onNpu: (Boolean?) -> Unit,
    onCacheMb: (Int?) -> Unit
) {
    val small = MaterialTheme.typography.bodySmall
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    // Dense models follow Settings → Dense models (prefill / decode device), MoE models the MoE prefill switch.
    // On this card: Default, On (NPU prefill) or Off (everything on the CPU).
    val dense = estimate != null && !estimate.moe
    val denseNpu = settings.denseDecode == 0 && settings.densePrefill == 1
    val npuOn = npuAvailable && (options.npu ?: if (dense) denseNpu else settings.npuPrefill)
    val defaultAccel = if (dense) when {
        settings.denseDecode == 1 -> "OpenCL"
        settings.densePrefill == 1 -> "NPU"
        settings.densePrefill == 2 -> "OpenCL prefill"
        else -> "off"
    } else if (settings.npuPrefill) "on" else "off"
    // The cache this model will load with: its own, else the global one (null = sized automatically at load).
    val cacheMb: Long? = options.cacheMb?.toLong() ?: when (settings.cacheMode) {
        CacheMode.FIXED -> settings.cacheMb.toLong()
        CacheMode.OFF -> 0L
        CacheMode.AUTO -> null
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Memory", style = MaterialTheme.typography.labelMedium)

        if (npuAvailable) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("NPU", style = small, modifier = Modifier.width(92.dp))
                FilterChip(
                    selected = options.npu == null, onClick = { onNpu(null) },
                    label = { Text("Default ($defaultAccel)") }
                )
                FilterChip(selected = options.npu == true, onClick = { onNpu(true) }, label = { Text("On") })
                FilterChip(selected = options.npu == false, onClick = { onNpu(false) }, label = { Text("Off") })
            }
        }

        if (estimate == null || estimate.moe) {
            // The same values as Settings offers, plus "Default" (the global setting).
            val defaultLabel = "Default (" + when (settings.cacheMode) {
                CacheMode.FIXED -> "${settings.cacheMb} MiB"
                CacheMode.AUTO -> "Auto"
                CacheMode.OFF -> "off"
            } + ")"
            com.bigmoe.onedge.ui.settings.ChoiceSetting(
                "Expert cache for this model",
                listOf(0 to defaultLabel) + com.bigmoe.onedge.core.SettingChoices.CACHE_MB.map { it to "$it MiB" },
                selected = options.cacheMb ?: 0
            ) { onCacheMb(it.takeIf { v -> v > 0 }) }
        }
        Text("Both apply the next time this model loads.", style = MaterialTheme.typography.labelSmall, color = dim)

        // ── estimate ──
        if (estimate == null) {
            Text("Reading the model file…", style = small, color = dim)
        } else {
            var sum = 0L
            var sumOpen = false // a part of the sum is not known before the load
            @Composable
            fun line(label: String, value: String, strong: Boolean = false) {
                Row(Modifier.fillMaxWidth()) {
                    Text(label, style = small, fontWeight = if (strong) FontWeight.SemiBold else null, modifier = Modifier.weight(1f))
                    Text(value, style = small, fontWeight = if (strong) FontWeight.SemiBold else null)
                }
            }
            Text("Estimated from the file", style = MaterialTheme.typography.labelSmall, color = dim)
            if (estimate.moe) {
                if (cacheMb == null) {
                    sumOpen = true
                    line("Expert cache", "auto, sized at load")
                } else {
                    sum += cacheMb * MIB
                    line("Expert cache", ModelManager.formatBytes(cacheMb * MIB))
                }
            }
            sum += estimate.denseResidentBytes
            line(if (estimate.moe) "Other weights in RAM" else "Weights in RAM", ModelManager.formatBytes(estimate.denseResidentBytes))
            if (npuOn) {
                sum += estimate.npuBytes
                if (estimate.npuExpertArenaBytes > 0) line("NPU: expert slots (2 layers)", "≈ " + ModelManager.formatBytes(estimate.npuExpertArenaBytes))
                line(
                    if (estimate.moe) "NPU: layer weight slots" else "NPU: layer weights",
                    "≈ " + ModelManager.formatBytes(estimate.npuDenseArenaBytes)
                )
                if (estimate.npuConvertedBytes > 0) line("NPU: converted copies in RAM", "≈ " + ModelManager.formatBytes(estimate.npuConvertedBytes))
            }
            line("Sum", (if (sumOpen) "> " else "≈ ") + ModelManager.formatBytes(sum), strong = true)
            line(
                "This device / free right now",
                ModelManager.formatBytes(deviceRamBytes) + " / " + (now?.let { ModelManager.formatBytes(it.freeMb * MIB) } ?: "?")
            )
            Text(
                "Not in the sum: KV cache / model state and compute buffers (they depend on the context size and batch width), " +
                    "and everything else that runs on the tablet." +
                    (if (npuAvailable && !npuOn) " With the NPU on: + ≈ " + ModelManager.formatBytes(estimate.npuBytes) + "." else ""),
                style = MaterialTheme.typography.labelSmall, color = dim
            )
            if (estimate.onFlashBytes > 0) {
                Text(
                    ModelManager.formatBytes(estimate.onFlashBytes) + " of weights are too big for RAM and stay on flash; they are read " +
                        "through whatever RAM is left free, so leave some.",
                    style = MaterialTheme.typography.labelSmall, color = dim
                )
            }
            if (npuOn && estimate.moe && !estimate.npuTakesExperts) {
                Text(
                    "The experts are stored as ${estimate.expertTypes}, a type the NPU's matmul does not take: with the NPU on they " +
                        "are still computed on the CPU, so the NPU's memory buys little for this file.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (now != null && sum > now.freeMb * MIB) {
                Text(
                    "The sum is more than the RAM that is free right now. Android closes other apps first; if that is not enough " +
                        "the tablet can stall. Only a warning: the model still loads.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
                )
            }
        }

        // ── seen ──
        if (record != null && (record.afterLoadMb > 0 || record.lowestOkMb > 0 || record.lowestAbortedMb > 0)) {
            Text("Seen on this device (free RAM)", style = MaterialTheme.typography.labelSmall, color = dim)
            if (record.afterLoadMb > 0) {
                Text(
                    "Last load (${record.loadConfig}): ${record.beforeLoadMb} MiB free before, ${record.afterLoadMb} MiB after" +
                        (if (record.beforeLoadMb > record.afterLoadMb) ", so it took about ${record.beforeLoadMb - record.afterLoadMb} MiB" else "") +
                        ". Android may have closed other apps in between, then it took more.",
                    style = small
                )
            }
            if (record.lowestOkMb > 0) {
                Text("Lowest in a run that ended normally: ${record.lowestOkMb} MiB (${record.lowestOkConfig}).", style = small)
            }
            if (record.lowestAbortedMb > 0) {
                val whenText = if (record.abortedAt > 0) {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(record.abortedAt))
                } else "?"
                Text(
                    "Lowest before a run that did NOT end normally (freeze, kill, or the app closed while loaded): " +
                        "${record.lowestAbortedMb} MiB (${record.abortedConfig.ifBlank { "still loading" }}), last alive $whenText. " +
                        "Sampled every 2 s, so the real low point may be lower.",
                    style = small, color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (now != null) {
            Text(
                "Free now: ${now.freeMb} MiB. Android calls memory low under ${now.thresholdMb} MiB" + (if (now.low) " (it is low now)." else "."),
                style = MaterialTheme.typography.labelSmall, color = if (now.low) MaterialTheme.colorScheme.error else dim
            )
        }
    }
}
