package com.bigmoe.onedge.ui.models

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.io.File
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
internal data class HfFile(val path: String, val bytes: Long)

/** A repo found by the search, with the GGUF files that match the chosen formats (all GGUFs when none is chosen). */
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

/** A model: one file, or the parts of a split GGUF (name-00001-of-00003.gguf), with their total size. */
private data class HfModel(val name: String, val parts: List<HfFile>) {
    val bytes: Long get() = parts.sumOf { it.bytes }
}

private val PART_RE = Regex("""-(\d{5})-of-(\d{5})\.gguf$""", RegexOption.IGNORE_CASE)

private fun groupModels(files: List<HfFile>): List<HfModel> =
    files.groupBy { PART_RE.replace(it.path, ".gguf") }
        .map { (key, parts) -> HfModel(key.substringAfterLast('/'), parts.sortedBy { it.path }) }
        .sortedBy { it.name }

private const val PREFS = "hf_downloads"

/**
 * Downloads run through Android's DownloadManager (it survives the app being closed) into the Download
 * folder, and are moved into the models folder once finished: by the receiver below while the app runs,
 * otherwise at the next start (moveFinished from OnEdgeApplication). DownloadManager itself may not write
 * outside the standard folders.
 */
object HfDownloads {
    internal fun enqueue(context: Context, repo: String, file: HfFile, modelsDir: String) {
        val name = file.path.substringAfterLast('/')
        val url = "https://huggingface.co/$repo/resolve/main/" + file.path.split('/').joinToString("/") { Uri.encode(it) } + "?download=true"
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setDescription("$repo → Models")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            .setAllowedOverMetered(true)
        val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(id.toString(), File(modelsDir, name).absolutePath).apply()
    }

    /** Move every finished download of ours into the models folder; forget failed ones. Returns how many moved. */
    fun moveFinished(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var moved = 0
        for ((key, target) in prefs.all) {
            val id = key.toLongOrNull() ?: continue
            val dest = target as? String ?: continue
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) { prefs.edit().remove(key).apply(); return@use }
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (st == DownloadManager.STATUS_FAILED) { prefs.edit().remove(key).apply(); return@use }
                if (st != DownloadManager.STATUS_SUCCESSFUL) return@use
                val local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))?.let { Uri.parse(it).path }
                val src = local?.let { File(it) }
                if (src == null || !src.exists()) { prefs.edit().remove(key).apply(); return@use }
                val out = File(dest).also { it.parentFile?.mkdirs() }
                val ok = src.renameTo(out) || runCatching { src.copyTo(out, overwrite = true); src.delete(); true }.getOrDefault(false)
                if (ok) {
                    moved++
                    prefs.edit().remove(key).apply()
                    // The finished entry pointed at the Download copy, which is gone now.
                    runCatching { dm.remove(id) }
                }
            }
        }
        return moved
    }

    private var receiver: BroadcastReceiver? = null

    /** While the app runs, move each download as soon as it finishes. */
    fun register(context: Context, onMoved: () -> Unit = {}) {
        val app = context.applicationContext
        moveFinished(app)
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                Thread { if (moveFinished(app) > 0) onMoved() }.start()
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(r, filter)
        receiver = r
    }
}

/**
 * Hugging Face search on the Models page: search GGUF repos by name, optionally only those that hold a
 * file of one format (Q4_0, Q4_K_M, ...), and download a file to the Download folder.
 */
@Composable
fun HuggingFaceCard(modelsDir: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var formats by remember { mutableStateOf<Set<String>>(emptySet()) }
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
        val fmt = formats
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val found = searchRepos(query)
                    if (fmt.isEmpty()) {
                        found.map { HfRepo(it.first, it.second, null) }
                    } else {
                        // Look into every repo's file list and keep the ones that hold the format.
                        found.map { (id, dl) ->
                            async {
                                val files = runCatching { listGguf(id) }.getOrDefault(emptyList())
                                HfRepo(id, dl, files.filter { f -> fmt.any { matchesFormat(f.path, it) } })
                            }
                        }.awaitAll().filter { !it.files.isNullOrEmpty() }
                    }
                }
            }
            busy = false
            result.onSuccess {
                repos = it
                status = if (it.isEmpty()) "Nothing found" + (if (fmt.isEmpty()) "" else " with " + fmt.joinToString()) else "${it.size} repos"
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
                FilterChip(selected = formats.isEmpty(), onClick = { formats = emptySet() }, label = { Text("any") })
                HF_FORMATS.forEach { f ->
                    FilterChip(selected = f in formats, onClick = { formats = if (f in formats) formats - f else formats + f }, label = { Text(f) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { search() }, enabled = !busy && query.isNotBlank()) { Text("Search") }
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Several formats can be chosen; then only repos holding a file of one of them are listed (each repo's file list is read, so it takes a few seconds). Downloads run in Android's download manager and are moved into $modelsDir when finished.",
                style = MaterialTheme.typography.bodySmall
            )
            repos.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { openRepo(r) }.padding(vertical = 4.dp)) {
                        Text(r.id, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${r.downloads} downloads" + (r.files?.let { " · ${groupModels(it).size} model(s)" } ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/${r.id}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Open") }
                }
                if (expanded == r.id) {
                    val files = r.files
                    if (files == null) Text("Loading files...", style = MaterialTheme.typography.bodySmall)
                    else if (files.isEmpty()) Text("No GGUF files", style = MaterialTheme.typography.bodySmall)
                    else groupModels(files).forEach { m ->
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    sizeText(m.bytes) + if (m.parts.size > 1) " in ${m.parts.size} parts" else "",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            TextButton(onClick = {
                                m.parts.forEach { HfDownloads.enqueue(context, r.id, it, modelsDir) }
                                Toast.makeText(context, "Download started: ${m.name}" + if (m.parts.size > 1) " (${m.parts.size} parts)" else "", Toast.LENGTH_SHORT).show()
                            }) { Text(if (m.parts.size > 1) "Download all parts" else "Download") }
                        }
                    }
                }
            }
        }
    }
}
