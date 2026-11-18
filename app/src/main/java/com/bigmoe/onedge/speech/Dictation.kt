package com.bigmoe.onedge.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Offline dictation into the message box with Android's on-device recognizer (no audio leaves the device; there is
 * deliberately no fallback to the online recognizer). Partial results show live; after a pause the recognizer
 * delivers the sentence and listening continues until the user taps again or stays silent.
 *
 * Kept independent of the chat so a later conversation mode can reuse it. Use from the main thread.
 */
class Dictation(private val context: Context) {
    var listening by mutableStateOf(false)
        private set
    var issue by mutableStateOf<SpeechIssue?>(null)

    private var recognizer: SpeechRecognizer? = null
    private var read: () -> String = { "" }
    private var write: (String) -> Unit = {}
    /** The message box text when the current utterance started; partial results are shown after it. */
    private var base = ""
    private var heardSomething = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun toggle(currentText: () -> String, onText: (String) -> Unit) {
        if (listening) stop() else start(currentText, onText)
    }

    fun stop() {
        if (!listening) return
        listening = false
        // Delivers the final result of what was said so far through onResults.
        recognizer?.stopListening()
    }

    /** Stops without delivering what is still pending (the message is being sent; nothing may be written back). */
    fun cancel() {
        listening = false
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
    }

    fun release() {
        listening = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun start(currentText: () -> String, onText: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            startOnDevice(currentText, onText)
        } else {
            issue = SpeechIssue.NoOfflineRecognizer
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startOnDevice(currentText: () -> String, onText: (String) -> Unit) {
        SpeechOutput.stop() // do not dictate the read-aloud voice
        read = currentText
        write = onText
        if (recognizer == null) {
            recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
                ?.also { it.setRecognitionListener(listener) }
        }
        if (recognizer == null) {
            issue = SpeechIssue.NoOfflineRecognizer
            return
        }
        listening = true
        listen()
    }

    private fun listen() {
        base = read()
        heardSomething = false
        recognizer?.startListening(intent())
    }

    private fun language(): String = Locale.getDefault().toLanguageTag()

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Google masks swear words with *** unless told otherwise (RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, API 33).
        putExtra("android.speech.extra.MASK_OFFENSIVE_WORDS", false)
    }

    private fun show(spoken: String) {
        val joined = join(base, spoken)
        if (joined != read()) write(joined)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { heardSomething = true }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            first(partialResults)?.let { show(it) }
        }

        override fun onResults(results: Bundle?) {
            first(results)?.let { show(it) }
            // Posted: some recognizers report "busy" when restarted from inside their own callback.
            if (listening) main.postDelayed({ if (listening) listen() }, 150)
        }

        override fun onError(error: Int) {
            val wasListening = listening
            listening = false
            when (error) {
                // Silence: end of dictation, nothing to report.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Unit
                // Follows a stop or a restart; not an error the user caused.
                SpeechRecognizer.ERROR_CLIENT -> Unit
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> issue = SpeechIssue.MicrophoneDenied
                ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> {
                    val requested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        runCatching { recognizer?.triggerModelDownload(intent()) }.isSuccess
                    } else {
                        false
                    }
                    issue = SpeechIssue.LanguagePackMissing(language(), requested)
                }
                else -> {
                    if (wasListening || heardSomething) issue = SpeechIssue.RecognizerError(error)
                }
            }
        }
    }

    private fun first(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (API 31).
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13

        /** Appends [spoken] to [base] with a space; capitalized at the start or after a sentence end. */
        internal fun join(base: String, spoken: String): String {
            val s = spoken.trim()
            if (s.isEmpty()) return base
            val before = base.trimEnd()
            val capital = before.isEmpty() || before.last() in ".!?"
            val segment = if (capital) s.replaceFirstChar { it.titlecase(Locale.getDefault()) } else s
            val separator = if (base.isEmpty() || base.last().isWhitespace()) "" else " "
            return base + separator + segment
        }
    }
}
