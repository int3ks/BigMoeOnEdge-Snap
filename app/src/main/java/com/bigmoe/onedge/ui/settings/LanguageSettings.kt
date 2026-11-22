package com.bigmoe.onedge.ui.settings

import android.widget.Toast
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
    val missing = remember(refresh) { I18n.templateLanguage(context) }
    if (missing != null) {
        OutlinedButton(onClick = {
            val f = I18n.writeTemplate(context, missing)
            Toast.makeText(context, if (f != null) t("Template written: {0}", f.absolutePath) else t("The template could not be written."), Toast.LENGTH_LONG).show()
            refresh++
        }) { Text(t("Create a template for {0}", missing.getDisplayLanguage(missing))) }
    }
}
