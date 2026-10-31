package com.bigmoe.onedge.ui.settings

import androidx.compose.runtime.Composable
import com.bigmoe.onedge.core.SpecSource
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.ui.models.guessLabel

private val DRAFT_MAX = (1..8).toList()
private val NGRAM_MIN = listOf(1, 2, 3, 4, 5, 6, 8, 12)
private val NGRAM_MAX = listOf(3, 4, 6, 8, 12, 16, 24, 32, 48, 64)

/**
 * Guess ahead for all models: the mode a model without its own uses ("Default" on its card) and the tuning that every
 * model's MTP or n-gram uses. Which mode a model runs is chosen on its card on the Models page.
 */
@Composable
internal fun GuessAheadSettings(settings: EngineSettings, viewModel: SettingsViewModel) {
    // Speculation cannot run together with these two streaming modes (the engine refuses the combination).
    val blocked = settings.routeAhead != 0 || settings.rowStream
    Section("Guess ahead")
    Hint("Each model chooses its mode on its card (Models page). These settings apply to all models.")
    ChoiceSetting(
        "Default mode (models set to \"Default\")",
        listOf(SpecSource.OFF, SpecSource.MTP, SpecSource.NGRAM).map { it to guessLabel(it) },
        settings.spec,
        enabled = !blocked
    ) { viewModel.updateSpec(it) }
    if (blocked) Hint("Guess ahead is off while Route-ahead or row streaming is on (Experimental below).")
    ChoiceSetting("Tokens drafted per pass", DRAFT_MAX.map { it to it.toString() }, settings.draftMax) {
        viewModel.updateDraftMax(it)
    }
    Hint("More tokens per pass only pay off when most of them are accepted.")
    ChoiceSetting(
        "MTP confidence floor",
        listOf(0 to "Always draft", 40 to "Above 40%", 60 to "Above 60%", 80 to "Above 80%"),
        settings.mtpPMinPct
    ) { viewModel.updateMtpPMinPct(it) }
    Hint("Stops an MTP draft early when the drafter's confidence in its next token is below this. A message with an image is answered without drafting. A separate drafter adds its weights to memory use.")
    ChoiceSetting(
        "N-gram minimum match",
        NGRAM_MIN.filter { it <= settings.ngramMaxMatch }.map { it to "$it tokens" },
        settings.ngramMinMatch.coerceAtMost(settings.ngramMaxMatch)
    ) {
        viewModel.updateNgramMinMatch(it)
        if (it > settings.ngramMaxMatch) viewModel.updateNgramMaxMatch(it)
    }
    ChoiceSetting(
        "N-gram maximum match",
        NGRAM_MAX.filter { it >= settings.ngramMinMatch }.map { it to "$it tokens" },
        settings.ngramMaxMatch.coerceAtLeast(settings.ngramMinMatch)
    ) {
        viewModel.updateNgramMaxMatch(it)
        if (it < settings.ngramMinMatch) viewModel.updateNgramMinMatch(it)
    }
    Hint("Minimum controls how exact a repeated suffix must be before n-gram drafts; maximum bounds the lookup work. Works with any temperature: guesses are checked by drawing with your sampling settings, so the answer is the same kind of text as without guessing.")
}
