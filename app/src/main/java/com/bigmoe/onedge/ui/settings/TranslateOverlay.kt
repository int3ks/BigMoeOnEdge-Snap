package com.bigmoe.onedge.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.OnEdgeApplication
import com.bigmoe.onedge.core.EngineEvent
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.core.GenerationRequest
import com.bigmoe.onedge.core.I18n
import com.bigmoe.onedge.core.ModelFile
import com.bigmoe.onedge.core.ModelManager
import com.bigmoe.onedge.core.t
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Translation mode (Settings → Language): a button over every page lists the texts this page asked [t] for; one
 * tapped is translated by a local model (picked and loaded first when none is) and "Apply" writes it into the
 * active language file. [routeKey] changes with the page, which starts a new list.
 */
@Composable
fun TranslateOverlay(routeKey: String?, models: List<ModelFile>, onLoad: (String) -> Unit) {
    if (!I18n.translateMode.value) return
    LaunchedEffect(routeKey) { I18n.markPage() }
    var listOpen by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        SmallFloatingActionButton(
            onClick = { listOpen = true },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 96.dp)
        ) { Icon(Icons.Default.Translate, contentDescription = "Translate texts of this page") }
    }
    if (listOpen) {
        val texts = remember { I18n.seenTexts().filter { it.any(Char::isLetter) } }
        AlertDialog(
            onDismissRequest = { listOpen = false },
            title = { Text("Texts on this page") },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(texts) { en ->
                        Column(Modifier.fillMaxWidth().clickable { picked = en; listOpen = false }.padding(vertical = 6.dp)) {
                            Text(en, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                            Text(
                                I18n.translationOf(en) ?: "— not translated —",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 3
                            )
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { listOpen = false }) { Text("Close") } }
        )
    }
    picked?.let { en -> TranslateOneDialog(en, models, onLoad) { picked = null } }
}

@Composable
private fun TranslateOneDialog(en: String, models: List<ModelFile>, onLoad: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val engine = (context.applicationContext as OnEdgeApplication).container.engineController
    val scope = rememberCoroutineScope()
    val state by engine.state.collectAsState()
    var value by remember { mutableStateOf(I18n.translationOf(en) ?: "") }
    var busy by remember { mutableStateOf(false) }
    var choose by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val language = I18n.activeFile?.let { f ->
        I18n.available(context).firstOrNull { it.file == f }?.name
    } ?: Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)

    fun translate() {
        busy = true
        error = null
        scope.launch {
            // A model being loaded: wait until it is ready (up to five minutes).
            withTimeoutOrNull(300_000) { engine.state.first { it is EngineState.Ready || it is EngineState.Failed } }
            val prompt = "Translate this user-interface text of an Android app into $language. Keep placeholders such as " +
                "{0}, line breaks and technical terms (MiB, NPU, KV, zram, top-k, MTP). Answer with the translation only, " +
                "no quotes, no explanation.\n\n$en"
            var out = ""
            var err: String? = null
            runCatching {
                engine.generate(
                    GenerationRequest(system = "You translate software texts precisely.", history = emptyList(), prompt = prompt,
                        expectedKey = "translate", maxTokens = 512, think = false)
                ).collect { ev -> if (ev is EngineEvent.Finished) { out = ev.answer; if (!ev.ok && !ev.cancelled) err = ev.error } }
            }.onFailure { err = it.message ?: it.javaClass.simpleName }
            if (err != null) error = err else value = out.trim().trim('"', '„', '“')
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t("Translate")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(en, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(language) }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text(if (state is EngineState.Ready) t("Translating…") else t("Loading the model…"), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = !busy, onClick = {
                    if (state is EngineState.Ready) translate() else choose = true
                }) {
                    Text(
                        if (state is EngineState.Ready) t("Translate with {0}", (state as EngineState.Ready).info.name)
                        else t("Translate with a local model…")
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && value.isNotBlank(), onClick = {
                val f = I18n.putTranslation(context, en, value.trim())
                if (f == null) Toast.makeText(context, t("Pick or create a language first (Settings → Language)."), Toast.LENGTH_LONG).show()
                else onClose()
            }) { Text(t("Apply")) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(t("Cancel")) } }
    )
    if (choose) {
        var sel by remember { mutableStateOf<ModelFile?>(null) }
        AlertDialog(
            onDismissRequest = { choose = false },
            title = { Text(t("Which model should translate?")) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(t("A model that is not loaded is loaded first."), style = MaterialTheme.typography.bodySmall)
                    models.filter { !ModelManager.isProjectorName(it.path) }.forEach { m ->
                        Row(Modifier.fillMaxWidth().clickable { sel = m }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = sel == m, onClick = { sel = m })
                            Text(m.displayName, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = sel != null, onClick = {
                    choose = false
                    sel?.let { onLoad(it.path) }
                    translate()
                }) { Text(t("Translate")) }
            },
            dismissButton = { TextButton(onClick = { choose = false }) { Text(t("Cancel")) } }
        )
    }
}
