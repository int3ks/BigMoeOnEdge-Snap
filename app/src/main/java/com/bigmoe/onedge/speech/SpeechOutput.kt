package com.bigmoe.onedge.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads answers aloud with the device's text-to-speech engine, offline voices only (a voice that needs the network
 * is never chosen). The system default engine is tried first; when it has no offline voice for the language and
 * Speech Services by Google is installed, that engine is used instead (and kept for later calls).
 *
 * Built for chunked input so a later conversation mode can feed sentences while the model still generates.
 * One app-wide instance: only one answer is spoken at a time. Call from the main thread.
 */
object SpeechOutput {
    const val GOOGLE_TTS = "com.google.android.tts"

    private val _speakingKey = MutableStateFlow<String?>(null)
    /** Key of the answer being spoken (see [toggle]), or null. */
    val speakingKey: StateFlow<String?> = _speakingKey

    private val main = Handler(Looper.getMainLooper())
    private val run = AtomicInteger(0)
    private var tts: TextToSpeech? = null
    private var ttsPackage: String? = null // null = the system default engine
    private var ready = false
    private val waiting = mutableListOf<(TextToSpeech?) -> Unit>()

    /** Starts reading [text] aloud, or stops when [key] is the answer being read. */
    fun toggle(context: Context, key: String, text: String, onIssue: (SpeechIssue) -> Unit) {
        if (_speakingKey.value == key) {
            stop()
            return
        }
        stop()
        val chunks = SpeakableText.chunks(text)
        if (chunks.isEmpty()) return
        val device = Locale.getDefault().language
        val languages = listOfNotNull(SpeakableText.guessLanguage(text), device, "de").distinct()
        _speakingKey.value = key
        val myRun = run.get()
        resolve(context.applicationContext, languages) { engine, voice, issue ->
            if (run.get() != myRun) return@resolve // stopped or replaced while the engine started
            if (engine == null || voice == null) {
                _speakingKey.value = null
                onIssue(issue ?: SpeechIssue.NoTtsEngine)
                return@resolve
            }
            engine.setVoice(voice)
            speakChunks(engine, key, chunks, myRun)
        }
    }

    fun stop() {
        run.incrementAndGet()
        _speakingKey.value = null
        tts?.takeIf { ready }?.stop()
    }

    private fun speakChunks(engine: TextToSpeech, key: String, chunks: List<String>, myRun: Int) {
        val lastId = "$myRun#${chunks.size - 1}"
        fun end() {
            if (run.get() == myRun) _speakingKey.compareAndSet(key, null)
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == lastId) end()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = end()
            override fun onError(utteranceId: String?, errorCode: Int) = end()
        })
        chunks.forEachIndexed { i, chunk ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (engine.speak(chunk, mode, null, "$myRun#$i") != TextToSpeech.SUCCESS) {
                end()
                return
            }
        }
    }

    private fun resolve(context: Context, languages: List<String>, then: (TextToSpeech?, Voice?, SpeechIssue?) -> Unit) {
        open(context, ttsPackage) { engine ->
            if (engine == null) {
                then(null, null, SpeechIssue.NoTtsEngine)
                return@open
            }
            pickVoice(engine, languages)?.let {
                then(engine, it, null)
                return@open
            }
            val googleInstalled = runCatching { engine.engines.any { it.name == GOOGLE_TTS } }.getOrDefault(false)
            val usingGoogle = (ttsPackage ?: engine.defaultEngine) == GOOGLE_TTS
            if (googleInstalled && !usingGoogle) {
                open(context, GOOGLE_TTS) { google ->
                    val voice = google?.let { pickVoice(it, languages) }
                    if (google != null && voice != null) then(google, voice, null)
                    else then(null, null, SpeechIssue.NoOfflineVoice(languages.first(), googleInstalled = true))
                }
            } else {
                then(null, null, SpeechIssue.NoOfflineVoice(languages.first(), googleInstalled))
            }
        }
    }

    private fun pickVoice(engine: TextToSpeech, languages: List<String>): Voice? {
        val voices = runCatching { engine.voices }.getOrNull() ?: return null
        val offline = voices.filter { v ->
            !v.isNetworkConnectionRequired && v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        }
        val country = Locale.getDefault().country
        for (language in languages) {
            offline.filter { it.locale.language == language }
                .maxWithOrNull(compareBy<Voice>({ it.locale.country == country }, { it.quality }, { -it.latency }))
                ?.let { return it }
        }
        return null
    }

    /** Starts the engine [pkg] (null = system default) or reuses the running one; [then] gets null when it failed. */
    private fun open(context: Context, pkg: String?, then: (TextToSpeech?) -> Unit) {
        val current = tts
        if (current != null && ttsPackage == pkg) {
            if (ready) then(current) else waiting += then
            return
        }
        current?.shutdown()
        ready = false
        ttsPackage = pkg
        waiting.clear()
        waiting += then
        lateinit var created: TextToSpeech
        // Posted: the listener can run inside the constructor (no engine installed) or on a binder thread.
        val listener = TextToSpeech.OnInitListener { status -> main.post { onInit(created, status) } }
        created = if (pkg == null) TextToSpeech(context, listener) else TextToSpeech(context, listener, pkg)
        tts = created
    }

    private fun onInit(engine: TextToSpeech, status: Int) {
        if (engine !== tts) return // replaced by another engine meanwhile
        ready = status == TextToSpeech.SUCCESS
        val callbacks = waiting.toList()
        waiting.clear()
        if (!ready) {
            engine.shutdown()
            tts = null
            ttsPackage = null
        }
        callbacks.forEach { it(if (ready) engine else null) }
    }
}
