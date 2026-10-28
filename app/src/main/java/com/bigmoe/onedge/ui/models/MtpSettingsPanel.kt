package com.bigmoe.onedge.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.ModelFile
import com.bigmoe.onedge.core.SpecSource
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.ui.settings.ChoiceSetting
import com.bigmoe.onedge.ui.settings.Hint

/**
 * How the engine guesses ahead. MTP drafts with the drafter GGUF attached to the loaded model when there is one,
 * otherwise with the model's own MTP head.
 */
internal fun guessLabel(spec: SpecSource): String = when (spec) {
    SpecSource.OFF -> "Off"
    SpecSource.MTP -> "MTP"
    SpecSource.NGRAM -> "N-gram"
}

private val DRAFT_MAX = (1..8).toList()
private val NGRAM_MIN = listOf(1, 2, 3, 4, 5, 6, 8, 12)
private val NGRAM_MAX = listOf(3, 4, 6, 8, 12, 16, 24, 32, 48, 64)

/**
 * Guess-ahead settings for the whole app, shown above the model list so it is clear they apply to every model. Only
 * the drafter file is per model (see [DrafterRow]).
 */
@Composable
internal fun GuessAheadCard(settings: EngineSettings, viewModel: ModelViewModel) {
    val mode = settings.spec
    // Speculation cannot run together with these two streaming modes (the engine refuses the combination).
    val blocked = settings.routeAhead != 0 || settings.rowStream
    var expanded by remember { mutableStateOf(mode != SpecSource.OFF) }
    val summary = if (mode == SpecSource.OFF) "Off" else "${guessLabel(mode)} • ${settings.draftMax} tokens/pass"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Guess ahead • all models", style = MaterialTheme.typography.titleSmall)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse guess ahead" else "Expand guess ahead",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            if (expanded) {
                ChoiceSetting(
                    "Mode",
                    listOf(SpecSource.OFF to "Off", SpecSource.MTP to "MTP", SpecSource.NGRAM to "N-gram (repeated text)"),
                    mode,
                    enabled = !blocked
                ) { viewModel.updateSpec(it) }
                if (blocked) Hint("Not available while Route-ahead or row streaming is on (Settings → Engine performance).")
                when (mode) {
                    SpecSource.OFF -> Hint(
                        "Drafts several likely next tokens and verifies them together. MTP uses a trained head, n-gram drafts " +
                            "from text that already appeared and only helps when text repeats."
                    )
                    SpecSource.MTP -> Hint(
                        "A model with an MTP drafter attached in the list below uses that drafter (an MTP-only GGUF); any other " +
                            "model uses its own MTP head. Qwen3.5/3.6 GGUFs usually carry one; a model with neither fails to load with MTP on."
                    )
                    SpecSource.NGRAM -> Unit
                }
                if (mode != SpecSource.OFF) {
                    ChoiceSetting("Tokens drafted per pass", DRAFT_MAX.map { it to it.toString() }, settings.draftMax) {
                        viewModel.updateDraftMax(it)
                    }
                }
                if (mode == SpecSource.MTP) {
                    ChoiceSetting(
                        "Confidence floor",
                        listOf(0 to "Always draft", 40 to "Above 40%", 60 to "Above 60%", 80 to "Above 80%"),
                        settings.mtpPMinPct
                    ) { viewModel.updateMtpPMinPct(it) }
                    Hint("Stops a draft early when the drafter's confidence in its next token is below this. A message with an image is answered without drafting; text-only messages still use MTP. A separate drafter adds its weights to memory use.")
                }
                if (mode == SpecSource.NGRAM) {
                    ChoiceSetting(
                        "Minimum match",
                        NGRAM_MIN.filter { it <= settings.ngramMaxMatch }.map { it to "$it tokens" },
                        settings.ngramMinMatch.coerceAtMost(settings.ngramMaxMatch)
                    ) {
                        viewModel.updateNgramMinMatch(it)
                        if (it > settings.ngramMaxMatch) viewModel.updateNgramMaxMatch(it)
                    }
                    ChoiceSetting(
                        "Maximum match",
                        NGRAM_MAX.filter { it >= settings.ngramMinMatch }.map { it to "$it tokens" },
                        settings.ngramMaxMatch.coerceAtLeast(settings.ngramMinMatch)
                    ) {
                        viewModel.updateNgramMaxMatch(it)
                        if (it < settings.ngramMinMatch) viewModel.updateNgramMinMatch(it)
                    }
                    Hint("Minimum controls how exact a repeated suffix must be before drafting. Maximum bounds the lookup work; long matches are not always more useful.")
                }
                if (mode != SpecSource.OFF) Hint("Guess ahead forces greedy decoding (temperature 0). More tokens per pass only pay off when most of them are accepted.")
            }
        }
    }
}

/** The one per-model MTP setting: which drafter GGUF is attached to this model. */
@Composable
internal fun DrafterRow(
    settings: EngineSettings,
    drafts: List<ModelFile>,
    attachedName: String?,
    onAttachDraft: (String?) -> Unit,
    onPickDraft: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val inUse = settings.spec == SpecSource.MTP
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("MTP drafter", style = MaterialTheme.typography.labelMedium)
            Text(
                when {
                    attachedName == null -> "None attached"
                    inUse -> "$attachedName • in use"
                    else -> "$attachedName • used when Guess ahead is set to MTP"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            OutlinedButton(onClick = { menu = true }) { Text(if (attachedName == null) "Attach" else "Change") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Choose MTP GGUF file…") }, onClick = { menu = false; onPickDraft() })
                drafts.forEach { d ->
                    DropdownMenuItem(text = { Text(d.name) }, onClick = { menu = false; onAttachDraft(d.path) })
                }
                if (attachedName != null) {
                    DropdownMenuItem(text = { Text("Detach") }, onClick = { menu = false; onAttachDraft(null) })
                }
            }
        }
    }
}
