package com.bigmoe.onedge.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.speech.RecognitionService
import androidx.compose.runtime.mutableStateOf

/**
 * Speech settings (own preferences file, not part of the settings export).
 * "Online speech" lets dictation use the normal recognizer (Google, offline preferred, may send audio) when the device
 * has no on-device one, and lets read-aloud use network voices while the device is online.
 */
object SpeechPrefs {
    private const val PREFS = "speech"
    private const val KEY_ONLINE = "allow_online"
    private var loaded = false
    private val online = mutableStateOf(false)

    /** Compose-observable when read inside a composable. */
    fun allowOnline(context: Context): Boolean {
        load(context)
        return online.value
    }

    fun setAllowOnline(context: Context, value: Boolean) {
        load(context)
        online.value = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ONLINE, value).apply()
        SpeechOutput.stop()
    }

    private fun load(context: Context) {
        if (loaded) return
        online.value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ONLINE, false)
        loaded = true
    }

    /** Speech recognition services Android shows to this app (package/class). */
    fun recognitionServices(context: Context): List<ComponentName> =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .mapNotNull { it.serviceInfo?.let { s -> ComponentName(s.packageName, s.name) } }
        }.getOrDefault(emptyList())

    /** The system's chosen voice input service first, then Google's app, then Speech Services, then any other. */
    fun onlineRecognizers(context: Context): List<ComponentName> {
        val services = recognitionServices(context)
        val chosen = runCatching {
            Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
        }.getOrNull()?.let { ComponentName.unflattenFromString(it) }
        return services.sortedBy {
            when {
                it == chosen -> 0
                it.packageName == GOOGLE_APP -> 1
                it.packageName == SpeechOutput.GOOGLE_TTS -> 2
                else -> 3
            }
        }
    }

    /** What dictation used last (shown in Settings), null before the first use. */
    val dictationService = mutableStateOf<String?>(null)

    fun isOnline(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)

    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
}
