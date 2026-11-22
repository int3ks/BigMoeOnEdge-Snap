package com.bigmoe.onedge.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.SettingsAdvice
import com.bigmoe.onedge.data.local.datastore.EngineSettings

/**
 * Under an answer with a `bmoe-settings` block: the suggested changes as "current -> suggested", what was left out
 * (unknown setting, value out of range) and Apply. Nothing is written before the tap.
 */
@Composable
fun SuggestedSettingsCard(
    text: String,
    settings: EngineSettings,
    onApply: (List<SettingsAdvice.Change>) -> Unit,
    modifier: Modifier = Modifier
) {
    // Parsed once per answer: applying one change updates the settings, and the applied rows must stay visible (ticked)
    // instead of vanishing from the list.
    val suggestion = remember(text) { SettingsAdvice.parse(text, settings) } ?: return
    val applied = remember(text) { mutableStateListOf<Int>() }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Suggested settings", style = MaterialTheme.typography.titleSmall)
            if (suggestion.changes.isEmpty()) {
                Text(
                    if (suggestion.ignored.isEmpty()) "Nothing to change: every suggested value is already set."
                    else "Nothing that can be applied.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            suggestion.changes.forEachIndexed { i, c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${c.knob.label}: ${c.knob.show(c.current)} → ${c.knob.show(c.value.toString())}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { applied.add(i); onApply(listOf(c)) },
                        enabled = i !in applied
                    ) { Text(if (i in applied) "Applied" else "Apply") }
                }
            }
            suggestion.ignored.forEach { Text("Left out: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (suggestion.changes.isNotEmpty()) {
                Text(
                    "Global settings; a value set on a model's card still wins. They take effect at the next load.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val open = suggestion.changes.filterIndexed { i, _ -> i !in applied }
                if (suggestion.changes.size > 1) {
                    Button(
                        onClick = { suggestion.changes.indices.filter { it !in applied }.also { applied.addAll(it) }; onApply(open) },
                        enabled = open.isNotEmpty()
                    ) { Text(if (open.isEmpty()) "All applied" else "Apply all") }
                }
            }
        }
    }
}
