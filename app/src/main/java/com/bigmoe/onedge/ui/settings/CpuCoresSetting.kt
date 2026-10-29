package com.bigmoe.onedge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

/**
 * The CPU cores the engine's threads run on, picked one by one (as in GameHub). Nothing picked = Android decides.
 * Cores Android does not let the app use are shown but cannot help: the engine only ever uses chosen AND allowed cores.
 */
@Composable
internal fun CpuCoresSetting(mask: Long, allowed: Long, onChange: (Long) -> Unit) {
    val cores = remember { readCores() }
    val fastest = cores.maxOfOrNull { it.maxKhz } ?: 0L
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("CPU cores", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            cores.forEach { core ->
                val bit = 1L shl core.index
                val isAllowed = allowed == 0L || (allowed and bit) != 0L
                val ghz = if (core.maxKhz > 0) String.format(Locale.US, " · %.1f GHz", core.maxKhz / 1_000_000.0) else ""
                val star = if (core.maxKhz > 0 && core.maxKhz == fastest && cores.any { it.maxKhz < fastest }) " ★" else ""
                FilterChip(
                    selected = (mask and bit) != 0L,
                    onClick = { onChange(mask xor bit) },
                    label = { Text("cpu${core.index}$ghz$star" + if (isAllowed) "" else " (blocked)") }
                )
            }
        }
        val usable = java.lang.Long.bitCount(if (allowed == 0L) mask else mask and allowed)
        Text(
            when {
                mask == 0L -> "Automatic: Android places the engine's threads."
                usable == 0 -> "None of the chosen cores is allowed for this app, so Android keeps deciding."
                else -> "The engine runs on $usable chosen core${if (usable == 1) "" else "s"}."
            },
            style = MaterialTheme.typography.bodySmall
        )
        if (mask != 0L) TextButton(onClick = { onChange(0L) }) { Text("Back to automatic") }
        Hint(
            "Pins the engine's threads to these cores (★ = fastest) and uses one compute thread per chosen core. The " +
                "cores apply with the next message, the thread count with the next model load. Android can block cores " +
                "for an app; those are marked blocked and skipped."
        )
    }
}
