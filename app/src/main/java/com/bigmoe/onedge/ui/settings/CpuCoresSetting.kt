package com.bigmoe.onedge.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale

/** One CPU core: its number as the kernel counts it (cpu0, cpu1, ...) and its maximum clock in kHz (0 = unknown). */
private data class CpuCore(val index: Int, val maxKhz: Long)

private fun readCores(): List<CpuCore> {
    val root = File("/sys/devices/system/cpu")
    val count = root.listFiles { f -> f.isDirectory && f.name.matches(Regex("cpu\\d+")) }?.size
        ?.takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors()
    return (0 until count.coerceAtMost(64)).map { i ->
        val khz = runCatching { File(root, "cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrDefault(0L)
        CpuCore(i, khz)
    }
}

private val LABEL_WIDTH = 64.dp
private val CELL_WIDTH = 52.dp

/**
 * The CPU cores the engine computes on, as a table: one column per core, one row for decode (writing the answer,
 * one token at a time) and one for prefill (reading the prompt, many tokens at once). An empty row = the default
 * (first two and last two cores, see CpuTopology).
 * Cores Android does not let the app use are marked and skipped: the engine only ever uses ticked AND allowed cores.
 */
@Composable
internal fun CpuCoresSetting(
    decodeMask: Long,
    prefillMask: Long,
    allowed: Long,
    onDecode: (Long) -> Unit,
    onPrefill: (Long) -> Unit
) {
    val cores = remember { readCores() }
    val fastest = cores.maxOfOrNull { it.maxKhz } ?: 0L
    val mixed = cores.any { it.maxKhz in 1 until fastest }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("CPU cores", style = MaterialTheme.typography.labelMedium)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            // Header: one column per core with its clock in GHz; ★ = fastest, ✕ = not allowed for this app.
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.width(LABEL_WIDTH))
                cores.forEach { core ->
                    val bit = 1L shl core.index
                    val blocked = allowed != 0L && (allowed and bit) == 0L
                    val ghz = if (core.maxKhz > 0) String.format(Locale.US, "%.1f", core.maxKhz / 1_000_000.0) else "?"
                    Text(
                        "cpu${core.index}\n$ghz" + when {
                            blocked -> " ✕"
                            mixed && core.maxKhz == fastest -> " ★"
                            else -> ""
                        },
                        modifier = Modifier.width(CELL_WIDTH),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            CoreRow("Decode", cores, decodeMask, onDecode)
            CoreRow("Prefill", cores, prefillMask, onPrefill)
        }
        Text(
            describe("Decode", decodeMask, allowed) + "\n" + describe("Prefill", prefillMask, allowed),
            style = MaterialTheme.typography.bodySmall
        )
        val default = com.bigmoe.onedge.core.CpuTopology.defaultMask(cores.size)
        if (decodeMask != default || prefillMask != default) {
            TextButton(onClick = { onDecode(0L); onPrefill(0L) }) { Text("Reset to default (first two and last two cores)") }
        }
        Hint(
            "Decode writes the answer one token at a time (this sets the t/s), prefill reads your message before the answer " +
                "starts (with NPU prefill on, the NPU does most of it). Each row pins its threads to the ticked cores " +
                "(★ = fastest, ✕ = blocked by Android) and uses one thread per ticked core. Default: the first two and " +
                "the last two cores. Cores apply with the next message, thread counts with the next model load."
        )
    }
}

@Composable
private fun CoreRow(label: String, cores: List<CpuCore>, mask: Long, onChange: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(LABEL_WIDTH), style = MaterialTheme.typography.bodyMedium)
        cores.forEach { core ->
            val bit = 1L shl core.index
            Box(Modifier.width(CELL_WIDTH), contentAlignment = Alignment.Center) {
                // Unticking the last core falls back to the default (0 is stored as "default").
                Checkbox(checked = (mask and bit) != 0L, onCheckedChange = { onChange(mask xor bit) })
            }
        }
    }
}

private fun describe(label: String, mask: Long, allowed: Long): String {
    val usable = java.lang.Long.bitCount(if (allowed == 0L) mask else mask and allowed)
    return when {
        mask == 0L -> "$label: default cores"
        usable == 0 -> "$label: none of the ticked cores is allowed, so Android decides"
        else -> "$label: $usable core${if (usable == 1) "" else "s"}, $usable thread${if (usable == 1) "" else "s"}"
    }
}
