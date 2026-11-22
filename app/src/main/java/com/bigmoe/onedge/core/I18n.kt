package com.bigmoe.onedge.core

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * The app's texts in other languages, from JSON files anyone can write: the JSON files in `<internal storage>/BigMoe/language`
 * (without all-files access the app's own external folder). The English text in the code is the key, so a missing
 * file or entry shows English. A file:
 *
 *     { "name": "Deutsch", "lang": "de", "strings": { "Expert cache (MiB)": "Experten-Cache (MiB)", ... } }
 *
 * Values may use {0}, {1} … for what the code fills in. assets/language/english.json lists every text wrapped in
 * [t] (scripts/extract-strings.py writes it); "Create template" copies it for the system language.
 */
object I18n {
    const val SYSTEM = "system"
    private const val PREFS = "i18n"
    private const val KEY = "language"

    /** The texts in force; a Compose state, so changing the language redraws what reads it. */
    private val strings = mutableStateOf<Map<String, String>>(emptyMap())

    class Language(val file: File, val name: String, val lang: String)

    fun dir(context: Context): File {
        val shared = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        val base = if (shared) File(Environment.getExternalStorageDirectory(), "BigMoe/language")
        else File(context.getExternalFilesDir(null) ?: context.filesDir, "language")
        return base.also { runCatching { it.mkdirs() } }
    }

    fun available(context: Context): List<Language> =
        (dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) } ?: emptyArray())
            .mapNotNull { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    Language(f, o.optString("name").ifBlank { f.nameWithoutExtension }, o.optString("lang"))
                }.getOrNull()
            }
            .sortedBy { it.name.lowercase() }

    /** The chosen language: [SYSTEM] or a file name in [dir]. */
    fun choice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    fun choose(context: Context, choice: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, choice).apply()
        apply(context)
    }

    /** Loads the chosen language (System: the file whose "lang" is the device's language; none = English). */
    fun apply(context: Context) {
        val c = choice(context)
        val langs = available(context)
        val pick = if (c == SYSTEM) {
            val sys = Locale.getDefault().language
            if (sys == "en") null else langs.firstOrNull { it.lang.equals(sys, ignoreCase = true) }
        } else langs.firstOrNull { it.file.name == c }
        strings.value = pick?.let { load(it.file) } ?: emptyMap()
    }

    private fun load(f: File): Map<String, String> = runCatching {
        val s = JSONObject(f.readText()).optJSONObject("strings") ?: return emptyMap()
        buildMap { for (k in s.keys()) s.optString(k).takeIf { it.isNotBlank() }?.let { put(k, it) } }
    }.getOrDefault(emptyMap())

    /** The device language, when it is not English and has no file yet: the one a template button can make. */
    fun templateLanguage(context: Context): Locale? {
        val sys = Locale.getDefault()
        if (sys.language == "en") return null
        return sys.takeIf { available(context).none { it.lang.equals(sys.language, ignoreCase = true) } }
    }

    /**
     * Writes `<language>.json` for [locale] into [dir]: every known text with its English value, to be translated.
     * Returns the file, or null when the list of texts could not be read.
     */
    fun writeTemplate(context: Context, locale: Locale): File? = runCatching {
        val src = JSONObject(context.assets.open("language/english.json").bufferedReader().use { it.readText() })
        val name = locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
        val out = JSONObject()
        out.put("name", name)
        out.put("lang", locale.language)
        out.put("strings", src.optJSONObject("strings") ?: JSONObject())
        val file = File(dir(context), locale.getDisplayLanguage(Locale.ENGLISH).lowercase() + ".json")
        file.writeText(out.toString(2))
        file
    }.getOrNull()

    fun t(en: String, args: Array<out Any?>): String {
        var s = strings.value[en] ?: en
        args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
        return s
    }
}

/** [en] in the chosen language; {0}, {1} … are replaced by [args]. Inside composables it follows a language change. */
fun t(en: String, vararg args: Any?): String = I18n.t(en, args)
