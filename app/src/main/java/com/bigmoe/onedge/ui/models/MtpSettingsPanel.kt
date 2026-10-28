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

/** How the engine guesses ahead: not at all, MTP with the model's own head or a separate drafter, or n-gram. */
internal enum class GuessMode { OFF, MTP_OWN_HEAD, MTP_DRAFTER, NGRAM }

internal fun guessMode(settings: EngineSettings): GuessMode = when (settings.spec) {
    SpecSource.OFF -> GuessMode.OFF
    SpecSource.NGRAM -> GuessMode.NGRAM
    SpecSource.MTP -> if (settings.separateMtpDraftEnabled) GuessMode.MTP_DRAFTER else GuessMode.MTP_OWN_HEAD
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
    val mode = guessMode(settings)
    // Speculation cannot run together with these two streaming modes (the engine refuses the combination).
    val blocked = settings.routeAhead != 0 || settings.rowStream
    var expanded by remember { mutableStateOf(mode != GuessMode.OFF) }
    val summary = when (mode) {
        GuessMode.OFF -> "Off"
        GuessMode.MTP_OWN_HEAD -> "MTP • model's own head • ${settings.draftMax} tokens/pass"
        GuessMode.MTP_DRAFTER -> "MTP • separate drafter • ${settings.draftMax} tokens/pass"
        GuessMode.NGRAM -> "N-gram • ${settings.draftMax} tokens/pass"
    }
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
                    listOf(
                        GuessMode.OFF to "Off",
                        GuessMode.MTP_OWN_HEAD to "MTP • model's own head",
                        GuessMode.MTP_DRAFTER to "MTP • separate drafter (MTP GGUF)",
                        GuessMode.NGRAM to "N-gram • repeated text"
                    ),
                    mode,
                    enabled = !blocked
                ) { m ->
                    when (m) {
                        GuessMode.OFF -> viewModel.updateSpec(SpecSource.OFF)
                        GuessMode.MTP_OWN_HEAD -> { viewModel.updateSeparateMtpDraftEnabled(false); viewModel.updateSpec(SpecSource.MTP) }
                        GuessMode.MTP_DRAFTER -> { viewModel.updateSeparateMtpDraftEnabled(true); viewModel.updateSpec(SpecSource.MTP) }
                        GuessMode.NGRAM -> viewModel.updateSpec(SpecSource.NGRAM)
                    }
                }
                if (blocked) Hint("Not available while Route-ahead or row streaming is on (Settings → Engine performance).")
                when (mode) {
                    GuessMode.OFF -> Hint(
                        "Drafts several likely next tokens and verifies them together. MTP uses a trained head: Qwen3.5/3.6 GGUFs " +
                            "usually carry their own, other models need a separate drafter attached below. N-gram drafts from text " +
                            "that already appeared and only helps when text repeats."
                    )
                    GuessMode.MTP_DRAFTER -> Hint("Attach the drafter (an MTP-only GGUF, converted with llama.cpp's --mtp) to each model in the list below. A model without one falls back to its own MTP head, if it has one.")
                    else -> Unit
                }
                if (mode != GuessMode.OFF) {
                    ChoiceSetting("Tokens drafted per pass", DRAFT_MAX.map { it to it.toString() }, settings.draftMax) {
                        viewModel.updateDraftMax(it)
                    }
                }
                if (mode == GuessMode.MTP_OWN_HEAD || mode == GuessMode.MTP_DRAFTER) {
                    ChoiceSetting(
                        "Confidence floor",
                        listOf(0 to "Always draft", 40 to "Above 40%", 60 to "Above 60%", 80 to "Above 80%"),
                        settings.mtpPMinPct
                    ) { viewModel.updateMtpPMinPct(it) }
                    Hint("Stops a draft early when the drafter's confidence in its next token is below this. A message with an image is answered without drafting; text-only messages still use MTP. A separate drafter adds its weights to memory use.")
                }
                if (mode == GuessMode.NGRAM) {
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
                if (mode != GuessMode.OFF) Hint("Guess ahead forces greedy decoding (temperature 0). More tokens per pass only pay off when most of them are accepted.")
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
    val inUse = guessMode(settings) == GuessMode.MTP_DRAFTER
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("MTP drafter", style = MaterialTheme.typography.labelMedium)
            Text(
                when {
                    attachedName == null -> "None attached"
                    inUse -> "$attachedName • in use"
                    else -> "$attachedName • not used (Guess ahead is not set to a separate drafter)"
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
