package com.bigmoe.onedge.speech

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** Why dictation or read-aloud could not start; shown by [SpeechIssueDialog] with the way to fix it. */
sealed interface SpeechIssue {
    /** No on-device (offline) speech recognizer on this device; [services] = recognition services Android lists. */
    data class NoOfflineRecognizer(val services: List<String>) : SpeechIssue
    /** "Online speech" is on, but there is no speech recognition service at all. */
    data object NoRecognizer : SpeechIssue
    /** The recognizer is there, but not its offline language pack. */
    data class LanguagePackMissing(val language: String, val downloadRequested: Boolean) : SpeechIssue
    data object MicrophoneDenied : SpeechIssue
    data class RecognizerError(val code: Int) : SpeechIssue
    /** No text-to-speech engine at all, or it failed to start. */
    data object NoTtsEngine : SpeechIssue
    /** No installed offline voice for [language]. */
    data class NoOfflineVoice(val language: String, val googleInstalled: Boolean) : SpeechIssue
}

@Composable
fun SpeechIssueDialog(issue: SpeechIssue, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val title: String
    val body: String
    var action: Pair<String, () -> Unit>? = null
    when (issue) {
        is SpeechIssue.NoOfflineRecognizer -> {
            title = "Offline dictation not available"
            val found = if (issue.services.isEmpty()) "none" else issue.services.joinToString(", ")
            body = "Android reports no on-device speech recognizer on this device (recognition services found: $found). " +
                "\"Allow online\" uses the normal recognizer (Google) instead: an installed offline language pack is " +
                "preferred, otherwise the audio goes to Google. Switch it off again in Settings → Chat → Online speech."
            action = "Allow online" to { SpeechPrefs.setAllowOnline(context, true) }
        }
        SpeechIssue.NoRecognizer -> {
            title = "No speech recognition"
            body = "Android lists no speech recognition service on this device. The Google app provides one."
            action = "Play Store" to { openPlayStore(context, SpeechPrefs.GOOGLE_APP) }
        }
        is SpeechIssue.LanguagePackMissing -> {
            title = "Offline language missing"
            body = if (issue.downloadRequested) {
                "The offline language pack for ${languageName(issue.language)} was requested. Try again once it is installed."
            } else {
                "Install the offline language pack for ${languageName(issue.language)} in the speech service's settings."
            }
        }
        SpeechIssue.MicrophoneDenied -> {
            title = "Microphone not allowed"
            body = "Dictation needs the microphone permission. Grant it in Android's app settings for this app."
            action = "App settings" to {
                try {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: ActivityNotFoundException) {
                }
            }
        }
        is SpeechIssue.RecognizerError -> {
            title = "Dictation stopped"
            body = "The speech recognizer reported error ${issue.code}${recognizerErrorName(issue.code)?.let { " ($it)" } ?: ""}."
        }
        SpeechIssue.NoTtsEngine -> {
            title = "No speech output"
            body = "No text-to-speech engine could be started. Speech Services by Google provides one with offline voices."
            action = "Play Store" to { openPlayStore(context, SpeechOutput.GOOGLE_TTS) }
        }
        is SpeechIssue.NoOfflineVoice -> {
            title = "No offline voice"
            if (issue.googleInstalled) {
                body = "There is no installed offline voice for ${languageName(issue.language)}. Install it in Speech Services by Google."
                action = "Install voices" to {
                    val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(SpeechOutput.GOOGLE_TTS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        openPlayStore(context, SpeechOutput.GOOGLE_TTS)
                    }
                }
            } else {
                body = "The speech engine on this device has no offline voice for ${languageName(issue.language)}. " +
                    "Speech Services by Google has offline voices."
                action = "Play Store" to { openPlayStore(context, SpeechOutput.GOOGLE_TTS) }
            }
        }
    }
    val act = action
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            if (act != null) TextButton(onClick = { act.second(); onDismiss() }) { Text(act.first) }
            else TextButton(onClick = onDismiss) { Text("OK") }
        },
        dismissButton = { if (act != null) TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Opens the app's page in the Play Store (not another store that also handles market:// links), else in the browser. */
fun openPlayStore(context: Context, pkg: String) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
        .setPackage("com.android.vending")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(market)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e2: ActivityNotFoundException) {
        }
    }
}

private fun languageName(tag: String): String =
    Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH).ifBlank { tag }

private fun recognizerErrorName(code: Int): String? = when (code) {
    1 -> "network timeout"
    2 -> "network"
    3 -> "audio"
    4 -> "server"
    5 -> "client"
    8 -> "recognizer busy"
    10 -> "too many requests"
    11 -> "server disconnected"
    else -> null
}
