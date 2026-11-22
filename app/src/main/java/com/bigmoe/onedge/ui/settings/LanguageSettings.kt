package com.bigmoe.onedge.ui.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.bigmoe.onedge.core.I18n
import com.bigmoe.onedge.core.t

/** Language of the app: System or one of the JSON files in the language folder, plus a template for the system language. */
@Composable
internal fun LanguageSettings() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val langs = remember(refresh) { I18n.available(context) }
    var choice by remember { mutableStateOf(I18n.choice(context)) }
    Text(t("Language"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    ChoiceSetting(
        "App language",
        listOf(I18n.SYSTEM to "System") + langs.map { it.file.name to it.name },
        selected = choice
    ) {
        choice = it
        I18n.choose(context, it)
    }
    Hint(t("A language is a JSON file in {0}: the English text as key, the translation as value. Missing texts stay English.", I18n.dir(context).absolutePath))
    // The file to hand to a translator (Gemini or any app that takes a file): the chosen one, else the system language's.
    val sys = java.util.Locale.getDefault().language
    val toShare = langs.firstOrNull { it.file.name == choice } ?: langs.firstOrNull { it.lang.equals(sys, ignoreCase = true) }
    if (toShare != null && !toShare.lang.equals("en", ignoreCase = true)) {
        OutlinedButton(onClick = { shareForTranslation(context, toShare.file, toShare.name) }) {
            Text(t("Share {0} for translation", toShare.file.name))
        }
    }
    val missing = remember(refresh) { I18n.templateLanguage(context) }
    if (missing != null) {
        OutlinedButton(onClick = {
            val f = I18n.writeTemplate(context, missing)
            Toast.makeText(context, if (f != null) t("Template written: {0}", f.absolutePath) else t("The template could not be written."), Toast.LENGTH_LONG).show()
            refresh++
        }) { Text(t("Create a template for {0}", missing.getDisplayLanguage(missing))) }
    }
}

/**
 * Shares a language file with a prompt, so a translating app (Gemini, a chat app) gets both: translate the values,
 * keep the keys and placeholders, answer with the whole file. The answer is saved back over the file by hand.
 */
private fun shareForTranslation(context: Context, file: File, language: String) {
    runCatching {
        val dir = File(context.cacheDir, "conversation_exports").apply { mkdirs() }
        val copy = File(dir, file.name).apply { writeText(file.readText()) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", copy)
        val prompt = "This JSON file holds the user-interface texts of an Android app. Translate every value in " +
            "\"strings\" into $language. Keep every key exactly as it is (the English text), keep placeholders such as " +
            "{0} and {1}, line breaks and technical terms (MiB, NPU, KV, zram, top-k, MTP …). Keep \"name\" and \"lang\". " +
            "Answer with the complete JSON file and nothing else."
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, prompt)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, t("Share for translation")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(context, it.message ?: it.javaClass.simpleName, Toast.LENGTH_LONG).show() }
}
