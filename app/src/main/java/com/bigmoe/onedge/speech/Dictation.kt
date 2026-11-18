package com.bigmoe.onedge.speech

import android.content.ComponentName
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
 * Dictation into the message box. The on-device recognizer is used when Android offers one (no audio leaves the
 * device); otherwise only with "Online speech" on, the normal recognizer (Google; offline language packs preferred,
 * else audio goes to Google). Partial results show live; after a pause the recognizer
 * delivers the sentence and listening continues until the user taps again or stays silent.
 *
 * Kept independent of the chat so a later conversation mode can reuse it. Use from the main thread.
 */
class Dictation(private val context: Context) {
    var listening by mutableStateOf(false)
        private set
    var issue by mutableStateOf<SpeechIssue?>(null)

    private var recognizer: SpeechRecognizer? = null
    /** "on-device" or the flattened service the [recognizer] is bound to. */
    private var recognizerKey: String? = null
    private var read: () -> String = { "" }
    private var write: (String) -> Unit = {}
    /** The message box text when the current utterance started; partial results are shown after it. */
    private var base = ""
    private var heardSomething = false
    /** onReadyForSpeech came since this service was started: it works, later errors are no reason to switch. */
    private var readyForSpeech = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Online mode tries the services in order, each with offline preferred first, then each without. */
    private data class Attempt(val service: ComponentName, val preferOffline: Boolean)
    private var attempts: List<Attempt> = emptyList()
    private var attemptIndex = 0
    private val attempt: Attempt? get() = attempts.getOrNull(attemptIndex)
    /** The attempt that worked last; tried first next time (this app run). */
    private var working: Attempt? = null
    private var downloadRequested = false

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
        recognizerKey = null
    }

    private fun start(currentText: () -> String, onText: (String) -> Unit) {
        SpeechOutput.stop() // do not dictate the read-aloud voice
        read = currentText
        write = onText
        downloadRequested = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            attempts = emptyList()
            startOnDevice()
            return
        }
        if (!SpeechPrefs.allowOnline(context)) {
            issue = SpeechIssue.NoOfflineRecognizer(SpeechPrefs.recognitionServices(context).map { it.flattenToShortString() })
            return
        }
        val services = SpeechPrefs.onlineRecognizers(context)
        if (services.isEmpty()) {
            issue = SpeechIssue.NoRecognizer
            return
        }
        val all = services.map { Attempt(it, true) } + services.map { Attempt(it, false) }
        attempts = listOfNotNull(working?.takeIf { it in all }) + all.filter { it != working }
        attemptIndex = 0
        startAttempt()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startOnDevice() {
        bind("on-device") { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
        if (recognizer == null) {
            issue = SpeechIssue.NoOfflineRecognizer(SpeechPrefs.recognitionServices(context).map { it.flattenToShortString() })
            return
        }
        SpeechPrefs.dictationService.value = "on-device recognizer (offline)"
        readyForSpeech = false
        listening = true
        listen()
    }

    private fun startAttempt() {
        val a = attempt ?: return
        bind(a.service.flattenToString()) { SpeechRecognizer.createSpeechRecognizer(context, a.service) }
        if (recognizer == null) {
            nextAttemptOr(SpeechIssue.NoRecognizer)
            return
        }
        readyForSpeech = false
        listening = true
        listen()
    }

    /** Switches to the next attempt (true), or shows [issue] when none is left. */
    private fun nextAttemptOr(fallbackIssue: SpeechIssue): Boolean {
        if (attemptIndex + 1 < attempts.size) {
            attemptIndex++
            main.postDelayed({ startAttempt() }, 150)
            return true
        }
        listening = false
        issue = fallbackIssue
        return false
    }

    private fun bind(key: String, create: () -> SpeechRecognizer) {
        if (recognizer != null && recognizerKey == key) return
        recognizer?.destroy()
        recognizer = runCatching { create() }.getOrNull()?.also { it.setRecognitionListener(listener) }
        recognizerKey = if (recognizer != null) key else null
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
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, attempt?.preferOffline ?: true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Google masks swear words with *** unless told otherwise (RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, API 33).
        putExtra("android.speech.extra.MASK_OFFENSIVE_WORDS", false)
    }

    private fun serviceName(): String = when (val pkg = attempt?.service?.packageName) {
        null -> "The on-device recognizer"
        SpeechPrefs.GOOGLE_APP -> "Google app"
        SpeechOutput.GOOGLE_TTS -> "Speech Services by Google"
        else -> pkg
    }

    private fun serviceLabel(): String {
        val a = attempt ?: return "on-device recognizer (offline)"
        return if (a.preferOffline) "${serviceName()} (offline preferred)" else "${serviceName()} (online)"
    }

    private fun show(spoken: String) {
        val joined = join(base, spoken)
        if (joined != read()) write(joined)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            readyForSpeech = true
            attempt?.let { working = it }
            SpeechPrefs.dictationService.value = serviceLabel()
        }
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
            val languageError = error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE
            if (languageError && !downloadRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Asks the service to fetch the offline pack (may show its own prompt); dictation goes on meanwhile.
                downloadRequested = runCatching { recognizer?.triggerModelDownload(intent()) }.isSuccess
            }
            val packIssue = SpeechIssue.LanguagePackMissing(language(), downloadRequested, serviceLabel())
            // Online mode: a service that fails before it is ready (no language, cannot bind, ...) -> try the next one.
            val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (attempts.isNotEmpty() && wasListening && !readyForSpeech && !silence &&
                error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            ) {
                nextAttemptOr(if (languageError) packIssue else SpeechIssue.RecognizerError(error, serviceLabel()))
                return
            }
            when {
                // Silence: end of dictation, nothing to report.
                silence -> Unit
                // Follows a stop or a restart; not an error the user caused.
                error == SpeechRecognizer.ERROR_CLIENT -> Unit
                // This app's permission is checked before starting, so this is the service's own permission.
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    issue = SpeechIssue.ServiceMicrophoneDenied(serviceName(), attempt?.service?.packageName)
                languageError -> issue = packIssue
                wasListening || heardSomething -> issue = SpeechIssue.RecognizerError(error, serviceLabel())
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
