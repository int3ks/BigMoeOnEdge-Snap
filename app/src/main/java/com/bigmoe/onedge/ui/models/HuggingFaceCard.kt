package com.bigmoe.onedge.ui.models

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** One GGUF file of a Hugging Face repo. */
private data class HfFile(val path: String, val bytes: Long)

/** A repo found by the search, with the GGUF files that match the chosen format (all GGUFs when none is chosen). */
private data class HfRepo(val id: String, val downloads: Long, val files: List<HfFile>?)

/** Formats to filter by; matched in the file name, case-insensitive, as a whole token. */
private val HF_FORMATS = listOf("Q4_0", "Q4_1", "Q4_K_S", "Q4_K_M", "IQ4_NL", "IQ4_XS", "Q5_K_M", "Q6_K", "Q8_0", "Q3_K_M", "Q2_K", "IQ2_XS", "MXFP4")

private fun httpGet(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15000
    c.readTimeout = 30000
    c.setRequestProperty("User-Agent", "BigMoeOnEdge2")
    try {
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    } finally {
        c.disconnect()
    }
}

private fun searchRepos(query: String): List<Pair<String, Long>> {
    val q = URLEncoder.encode(query.trim(), "UTF-8")
    val arr = JSONArray(httpGet("https://huggingface.co/api/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=40"))
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        o.getString("id") to o.optLong("downloads", 0)
    }
}

private fun listGguf(repo: String): List<HfFile> {
    val arr = JSONArray(httpGet("https://huggingface.co/api/models/$repo/tree/main?recursive=true"))
    val out = ArrayList<HfFile>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val path = o.optString("path")
        if (o.optString("type") != "file" || !path.lowercase(Locale.ROOT).endsWith(".gguf")) continue
        val lfs = o.optJSONObject("lfs")
        out += HfFile(path, lfs?.optLong("size", 0) ?: o.optLong("size", 0))
    }
    return out.sortedBy { it.path }
}

/** Whether a file name carries the format as its own token (Q4_K does not match Q4_K_M, Q4_0 not Q4_0_4_8). */
private fun matchesFormat(path: String, format: String): Boolean {
    val name = path.substringAfterLast('/').uppercase(Locale.ROOT)
    val f = format.uppercase(Locale.ROOT)
    var from = 0
    while (true) {
        val i = name.indexOf(f, from)
        if (i < 0) return false
        val before = if (i == 0) '.' else name[i - 1]
        val after = if (i + f.length >= name.length) '.' else name[i + f.length]
        val sep = { ch: Char -> !ch.isLetterOrDigit() || ch == '-' }
        // "_" after the token means a longer format (Q4_K + _M), except the UD/XL suffixes Unsloth adds.
        val afterOk = sep(after) && (after != '_' || name.startsWith("_XL", i + f.length))
        if (sep(before) && afterOk) return true
        from = i + 1
    }
}

private fun sizeText(bytes: Long): String =
    if (bytes <= 0) "?" else String.format(Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)

/** Into the public Download folder, as a browser download would go; from there the archive card moves it. */
private fun enqueue(context: Context, repo: String, file: HfFile) {
    val name = file.path.substringAfterLast('/')
    val url = "https://huggingface.co/$repo/resolve/main/" + file.path.split('/').joinToString("/") { Uri.encode(it) } + "?download=true"
    val req = DownloadManager.Request(Uri.parse(url))
        .setTitle(name)
        .setDescription(repo)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        .setAllowedOverMetered(true)
    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
    Toast.makeText(context, "Download started: $name", Toast.LENGTH_SHORT).show()
}

/**
 * Hugging Face search on the Models page: search GGUF repos by name, optionally only those that hold a
 * file of one format (Q4_0, Q4_K_M, ...), and download a file to the Download folder.
 */
@Composable
fun HuggingFaceCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var format by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<HfRepo>>(emptyList()) }
    var expanded by remember { mutableStateOf<String?>(null) }

    fun search() {
        if (query.isBlank() || busy) return
        busy = true
        status = "Searching..."
        repos = emptyList()
        expanded = null
        val fmt = format
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val found = searchRepos(query)
                    if (fmt == null) {
                        found.map { HfRepo(it.first, it.second, null) }
                    } else {
                        // Look into every repo's file list and keep the ones that hold the format.
                        found.map { (id, dl) ->
                            async {
                                val files = runCatching { listGguf(id) }.getOrDefault(emptyList())
                                HfRepo(id, dl, files.filter { matchesFormat(it.path, fmt) })
                            }
                        }.awaitAll().filter { !it.files.isNullOrEmpty() }
                    }
                }
            }
            busy = false
            result.onSuccess {
                repos = it
                status = if (it.isEmpty()) "Nothing found" + (fmt?.let { f -> " with $f" } ?: "") else "${it.size} repos"
            }.onFailure { status = "Search failed: ${it.message}" }
        }
    }

    fun openRepo(r: HfRepo) {
        if (expanded == r.id) { expanded = null; return }
        expanded = r.id
        if (r.files != null) return
        scope.launch {
            val files = runCatching { withContext(Dispatchers.IO) { listGguf(r.id) } }
            files.onSuccess { list -> repos = repos.map { if (it.id == r.id) it.copy(files = list) else it } }
                .onFailure { status = "Could not list ${r.id}: ${it.message}" }
        }
    }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text("Hugging Face search", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (open) "▲" else "▼")
            }
            if (!open) return@Column
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("Model (e.g. Qwen3.6 35B)") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = format == null, onClick = { format = null }, label = { Text("any") })
                HF_FORMATS.forEach { f ->
                    FilterChip(selected = format == f, onClick = { format = if (format == f) null else f }, label = { Text(f) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { search() }, enabled = !busy && query.isNotBlank()) { Text("Search") }
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "With a format chosen, only repos that hold a file of that format are listed (each repo's file list is read, so it takes a few seconds). Downloads go to the Download folder; move them to /Models or the archive from there.",
                style = MaterialTheme.typography.bodySmall
            )
            repos.forEach { r ->
                Column(Modifier.fillMaxWidth().clickable { openRepo(r) }.padding(vertical = 4.dp)) {
                    Text(r.id, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${r.downloads} downloads" + (r.files?.let { " · ${it.size} file(s)" + (format?.let { f -> " $f" } ?: "") } ?: ""),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (expanded == r.id) {
                    val files = r.files
                    if (files == null) Text("Loading files...", style = MaterialTheme.typography.bodySmall)
                    else if (files.isEmpty()) Text("No GGUF files", style = MaterialTheme.typography.bodySmall)
                    else files.forEach { f ->
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.path.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall)
                                Text(sizeText(f.bytes), style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { enqueue(context, r.id, f) }) { Text("Download") }
                        }
                    }
                }
            }
        }
    }
}
