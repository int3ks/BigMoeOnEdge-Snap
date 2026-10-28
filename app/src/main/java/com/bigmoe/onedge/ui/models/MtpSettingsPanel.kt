package com.bigmoe.onedge.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

/** How MTP drafts for this model: not at all, with the head inside the model file, or with a separate drafter GGUF. */
internal enum class MtpMode { OFF, OWN_HEAD, DRAFTER }

internal fun mtpMode(settings: EngineSettings): MtpMode = when {
    settings.spec != SpecSource.MTP -> MtpMode.OFF
    settings.separateMtpDraftEnabled -> MtpMode.DRAFTER
    else -> MtpMode.OWN_HEAD
}

/**
 * Everything about MTP in one place: on/off, which drafter (the model's own head or a separate MTP GGUF attached to
 * this model) and how many tokens it drafts per pass. N-gram guess-ahead stays under Settings → Engine performance.
 * The mode, tokens per pass and confidence floor are app-wide; the drafter file is attached per model.
 */
@Composable
internal fun MtpSettingsPanel(
    settings: EngineSettings,
    viewModel: ModelViewModel,
    drafts: List<ModelFile>,
    attachedName: String?,
    onAttachDraft: (String?) -> Unit,
    onPickDraft: () -> Unit
) {
    val mode = mtpMode(settings)
    // Speculation cannot run together with these two streaming modes (the engine refuses the combination).
    val blocked = settings.routeAhead != 0 || settings.rowStream
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ChoiceSetting(
            "MTP",
            listOf(
                MtpMode.OFF to "Off",
                MtpMode.OWN_HEAD to "On • model's own MTP head",
                MtpMode.DRAFTER to "On • separate drafter (MTP GGUF)"
            ),
            mode,
            enabled = !blocked
        ) { m ->
            when (m) {
                MtpMode.OFF -> if (settings.spec == SpecSource.MTP) viewModel.updateSpec(SpecSource.OFF)
                MtpMode.OWN_HEAD -> { viewModel.updateSeparateMtpDraftEnabled(false); viewModel.updateSpec(SpecSource.MTP) }
                MtpMode.DRAFTER -> { viewModel.updateSeparateMtpDraftEnabled(true); viewModel.updateSpec(SpecSource.MTP) }
            }
        }
        if (blocked) {
            Hint("Not available while Route-ahead or row streaming is on (Settings → Engine performance).")
        } else if (settings.spec == SpecSource.NGRAM) {
            Hint("N-gram guess-ahead is on (Settings → Engine performance). Turning MTP on here replaces it.")
        }

        if (mode == MtpMode.DRAFTER) {
            DrafterPicker(drafts, attachedName, onAttachDraft, onPickDraft)
            if (attachedName == null) {
                Text(
                    "No drafter attached to this model yet: it falls back to its own MTP head, if it has one.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
                )
            }
        }

        if (mode != MtpMode.OFF) {
            ChoiceSetting("Tokens drafted per pass", (1..8).map { it to it.toString() }, settings.draftMax) {
                viewModel.updateDraftMax(it)
            }
            ChoiceSetting(
                "Confidence floor",
                listOf(0 to "Always draft", 40 to "Above 40%", 60 to "Above 60%", 80 to "Above 80%"),
                settings.mtpPMinPct
            ) { viewModel.updateMtpPMinPct(it) }
            Hint("Stops a draft early when the drafter's confidence in its next token is below this. More tokens per pass only pay off when most of them are accepted. MTP forces greedy decoding (temperature 0).")
            Hint("A message with an image is answered without drafting (normal speed); text-only messages still use MTP. A separate drafter adds its weights to memory use.")
        } else {
            Hint("Qwen3.5/3.6 GGUFs usually carry their own MTP head; other models need a separate drafter (an MTP-only GGUF, converted with llama.cpp's --mtp).")
        }
    }
}

@Composable
private fun DrafterPicker(
    drafts: List<ModelFile>,
    attachedName: String?,
    onAttachDraft: (String?) -> Unit,
    onPickDraft: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Drafter for this model", style = MaterialTheme.typography.labelMedium)
            Text(attachedName ?: "None attached", style = MaterialTheme.typography.bodySmall)
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
