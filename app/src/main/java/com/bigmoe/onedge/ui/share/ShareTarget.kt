package com.bigmoe.onedge.ui.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.ModelFile
import com.bigmoe.onedge.data.local.entity.AttachmentKind
import com.bigmoe.onedge.data.repository.ChatSessionDomainModel

/** What another app shared with BigMoe² (share sheet): text and/or files with their kind. */
data class SharedContent(
    val text: String?,
    val files: List<Pair<Uri, AttachmentKind>>,
    /** A link that came with the text; shown as a chip above the message box, appended in full when sent. */
    val links: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && files.isEmpty() && links.isEmpty()

    fun describe(): String = buildList {
        if (!text.isNullOrBlank()) add("text")
        if (links.isNotEmpty()) add(if (links.size == 1) "a link" else "${links.size} links")
        val pics = files.count { it.second == AttachmentKind.IMAGE }
        val vids = files.count { it.second == AttachmentKind.VIDEO }
        val docs = files.count { it.second == AttachmentKind.FILE }
        if (pics > 0) add(if (pics == 1) "a picture" else "$pics pictures")
        if (vids > 0) add(if (vids == 1) "a video" else "$vids videos")
        if (docs > 0) add(if (docs == 1) "a file" else "$docs files")
    }.joinToString(", ")

    companion object {
        /** Null when [intent] is not a share (ACTION_SEND / ACTION_SEND_MULTIPLE) or carries nothing usable. */
        fun from(intent: Intent?, resolver: ContentResolver): SharedContent? {
            if (intent == null) return null
            val uris: List<Uri> = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(streamExtra(intent))
                Intent.ACTION_SEND_MULTIPLE -> streamListExtra(intent)
                else -> return null
            }
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()
            val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
            val text = when {
                body.isNullOrBlank() -> subject?.takeIf { it.isNotBlank() }
                subject.isNullOrBlank() || body.contains(subject) -> body
                else -> "$subject\n\n$body"
            }
            val files = uris.map { uri ->
                val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: intent.type.orEmpty()
                uri to when {
                    mime.startsWith("image/") -> AttachmentKind.IMAGE
                    mime.startsWith("video/") -> AttachmentKind.VIDEO
                    else -> AttachmentKind.FILE
                }
            }
            val (clean, link) = text?.let(::tidy) ?: (null to null)
            return SharedContent(clean, files, listOfNotNull(link)).takeIf { !it.isEmpty }
        }

        private val URL = Regex("https?://\\S+")

        /**
         * Browsers share a selection as the text plus a "link to highlight": the URL with a `#:~:text=` fragment that
         * repeats the selection percent-encoded (the "many special characters"). The fragment goes; a link at the
         * end goes on its own line as the source.
         */
        internal fun tidy(raw: String): Pair<String?, String?> {
            val t = raw.replace(Regex("#:~:text=\\S*"), "").trim()
            val urls = URL.findAll(t).toList()
            val last = urls.lastOrNull() ?: return t to null
            if (last.range.last != t.lastIndex) return t to null
            val before = t.substring(0, last.range.first).trim()
            return before.ifEmpty { null } to cleanUrl(last.value)
        }

        /** Query parameters that only track the click or the browser (Google search adds a long gs_lcrp blob). */
        private val TRACKING = setOf(
            "oq", "gs_lcrp", "gs_lp", "sourceid", "source", "ie", "ved", "ei", "sca_esv", "sxsrf", "client", "aqs",
            "uact", "sei", "rlz", "fbclid", "gclid", "mc_cid", "mc_eid"
        )

        internal fun cleanUrl(u: String): String {
            val q = u.indexOf('?')
            if (q < 0) return u
            val hash = u.indexOf('#', q).let { if (it < 0) u.length else it }
            val kept = u.substring(q + 1, hash).split('&').filter { p ->
                val k = p.substringBefore('=')
                k.isNotEmpty() && k !in TRACKING && !k.startsWith("utm_")
            }
            return u.substring(0, q) + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + u.substring(hash)
        }

        @Suppress("DEPRECATION")
        private fun streamExtra(intent: Intent): Uri? =
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM)

        @Suppress("DEPRECATION")
        private fun streamListExtra(intent: Intent): List<Uri> =
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
    }
}

/**
 * Where the shared content goes: a new chat or one of the recent chats, and — when no model is loaded — which model
 * to load. [onDone] gets the chat (null = new) and the model to load (null = none).
 */
@Composable
fun ShareTargetDialog(
    shared: SharedContent,
    sessions: List<ChatSessionDomainModel>,
    models: List<ModelFile>,
    askForModel: Boolean,
    onDone: (sessionId: String?, modelPath: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var chosenChat by remember { mutableStateOf<String?>(null) }
    var chatPicked by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf<String?>(models.firstOrNull()?.path) }
    val pickingModel = askForModel && chatPicked
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (pickingModel) "No model is loaded — load which one?" else "Share ${shared.describe()} into") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (!pickingModel) {
                    Option("New chat", chosenChat == null) { chosenChat = null }
                    if (sessions.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    sessions.take(20).forEach { s -> Option(s.title.ifBlank { "Untitled chat" }, chosenChat == s.id) { chosenChat = s.id } }
                } else {
                    models.forEach { m -> Option(m.displayName, model == m.path) { model = m.path } }
                    Option("Don't load a model now", model == null) { model = null }
                }
                Text(
                    "The text goes into the message box and files are attached; nothing is sent until you send it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (askForModel && !chatPicked) chatPicked = true else onDone(chosenChat, if (askForModel) model else null)
            }) { Text(if (askForModel && !chatPicked) "Next" else "Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
