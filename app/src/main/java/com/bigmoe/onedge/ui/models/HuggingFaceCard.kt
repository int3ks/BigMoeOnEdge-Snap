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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
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
private data class HfRepo(val id: String, val downloads: Long, val files: List<HfFile>?, val moe: Boolean? = null)

/** Formats to filter by; matched in the file name, case-insensitive, as a whole token. */
private val HF_FORMATS = listOf("Q4_0", "Q4_1", "Q4_K_S", "Q4_K_M", "IQ4_NL", "IQ4_XS", "Q5_K_M", "Q6_K", "Q8_0", "Q3_K_M", "Q2_K", "IQ2_XS", "MXFP4")

/** Format guide: rough estimates for this engine on a Snapdragon tablet (CPU decode, Hexagon prefill), not measurements. */
private val HF_LEGEND = listOf(
    "Q4_0" to "4.5 bit · fastest CPU decode · NPU yes · quality lowest of the 4-bit (take an i1/imatrix file)",
    "Q4_1" to "5.0 bit · about as fast as Q4_0, ~11 % bigger · NPU yes · a bit better than Q4_0",
    "IQ4_NL" to "4.5 bit · close to Q4_0 speed · NPU yes · better than Q4_0",
    "IQ4_XS" to "4.25 bit · a bit slower (table lookups) · NPU no (CPU prefill) · good quality for its size",
    "Q4_K_S" to "~4.6 bit · a bit slower than Q4_0 · NPU yes · good",
    "Q4_K_M" to "~4.85 bit · slower than Q4_0 (Q4_K + Q6_K mix) · NPU yes · best of the 4-bit",
    "Q5_K_M / Q6_K" to "5.7 / 6.6 bit · slower, much bigger (more flash reads) · NPU yes · near lossless",
    "Q8_0" to "8.5 bit · simple but twice the bytes of Q4 · NPU yes · lossless for practical use",
    "Q3_K_M / Q2_K" to "~3.9 / ~3 bit · small, not faster on CPU · NPU no (CPU prefill, slow) · clearly worse; only i1/UD files",
    "IQ2_XS" to "~2.3 bit · slow kernels · NPU no · last resort for models that do not fit otherwise",
    "MXFP4" to "4.25 bit · fast where the model was trained for it (gpt-oss) · NPU yes",
)

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

/**
 * MoE or dense, from the GGUF header: reads the first MiB of the file with an HTTP range request and walks
 * the metadata for `<arch>.expert_count` (> 0 = MoE). True/false, or null when the header could not be
 * read or the key lies beyond the first MiB (behind a large array).
 */
private fun probeMoe(repo: String, path: String): Boolean? = runCatching {
    val url = "https://huggingface.co/$repo/resolve/main/" + path.split('/').joinToString("/") { Uri.encode(it) }
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15000
    c.readTimeout = 30000
    c.setRequestProperty("User-Agent", "BigMoeOnEdge2")
    c.setRequestProperty("Range", "bytes=0-1048575")
    val bytes = try {
        if (c.responseCode !in 200..299) return@runCatching null
        val out = java.io.ByteArrayOutputStream()
        c.inputStream.use { ins ->
            val buf = ByteArray(65536)
            while (out.size() < 1048576) {
                val n = ins.read(buf); if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        out.toByteArray()
    } finally { c.disconnect() }
    val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    if (b.int != 0x46554747) return@runCatching null // "GGUF"
    b.int; b.long
    val nKv = b.long
    fun str(): String { val n = b.long.toInt(); val a = ByteArray(n); b.get(a); return String(a) }
    fun skip(type: Int) {
        when (type) {
            0, 1, 7 -> b.get()
            2, 3 -> b.short
            4, 5, 6 -> b.int
            10, 11, 12 -> b.long
            8 -> b.position(b.position() + b.long.toInt())
            9 -> { val t = b.int; val n = b.long; for (i in 0 until n) skip(t) }
            else -> error("type $type")
        }
    }
    for (i in 0 until nKv) {
        val key = str()
        val type = b.int
        if (key.endsWith(".expert_count")) {
            val v = when (type) { 4, 5 -> b.int.toLong(); 10, 11 -> b.long; 2, 3 -> b.short.toLong(); else -> { skip(type); 0L } }
            return@runCatching v > 0
        }
        skip(type)
    }
    false // every key read, none says experts: dense
}.getOrNull()

private fun searchRepos(query: String, sort: String): List<Pair<String, Long>> {
    val q = URLEncoder.encode(query.trim(), "UTF-8")
    val arr = JSONArray(httpGet("https://huggingface.co/api/models?search=$q&filter=gguf&sort=$sort&direction=-1&limit=40"))
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

    /** One of our downloads as the DownloadManager reports it. */
    data class Item(val id: Long, val name: String, val done: Long, val total: Long, val status: Int)

    fun items(context: Context): List<Item> {
        val ids = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.keys.mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return emptyList()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val out = ArrayList<Item>()
        dm.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
            while (c.moveToNext()) {
                out += Item(
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                    c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "?",
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                )
            }
        }
        return out.sortedBy { it.name }
    }

    fun cancel(context: Context, id: Long) {
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id.toString()).apply()
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
fun HuggingFaceCard(modelsDir: String, onMoved: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var formats by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<HfRepo>>(emptyList()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var legend by remember { mutableStateOf(false) }
    var downloads by remember { mutableStateOf<List<HfDownloads.Item>>(emptyList()) }
    // Progress of our downloads, every 2 s; finished ones are moved into the models folder here too, so
    // that works even where the completion broadcast does not arrive.
    LaunchedEffect(Unit) {
        while (true) {
            val (list, moved) = withContext(Dispatchers.IO) {
                val m = runCatching { HfDownloads.moveFinished(context) }.getOrDefault(0)
                runCatching { HfDownloads.items(context) }.getOrDefault(emptyList()) to m
            }
            downloads = list
            if (moved > 0) onMoved()
            delay(2000)
        }
    }
    var sortBy by remember { mutableStateOf("downloads") } // downloads | createdAt | size
    var kind by remember { mutableStateOf("all") } // all | moe | dense

    fun search() {
        if (query.isBlank() || busy) return
        busy = true
        status = "Searching..."
        repos = emptyList()
        expanded = null
        val fmt = formats
        val sort = sortBy
        val kd = kind
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val found = searchRepos(query, if (sort == "createdAt") "createdAt" else "downloads")
                    // Sorting by size needs every repo's file list, as a format filter does.
                    val list = if (fmt.isEmpty() && sort != "size" && kd == "all") {
                        found.map { HfRepo(it.first, it.second, null) }
                    } else {
                        // Look into every repo's file list and keep the ones that hold the format.
                        found.map { (id, dl) ->
                            async {
                                val files = runCatching { listGguf(id) }.getOrDefault(emptyList())
                                val kept = if (fmt.isEmpty()) files else files.filter { f -> fmt.any { matchesFormat(f.path, it) } }
                                // MoE or dense from the header of the smallest file (the first part of a split one).
                                val moe = if (kd == "all" || kept.isEmpty()) null
                                          else groupModels(kept).minByOrNull { it.bytes }?.parts?.first()?.let { probeMoe(id, it.path) }
                                HfRepo(id, dl, kept, moe)
                            }
                        }.awaitAll().filter { !it.files.isNullOrEmpty() }
                            .filter { kd == "all" || (kd == "moe" && it.moe == true) || (kd == "dense" && it.moe == false) }
                    }
                    if (sort == "size") list.sortedBy { r -> groupModels(r.files.orEmpty()).minOfOrNull { it.bytes } ?: Long.MAX_VALUE } else list
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
            downloads.forEach { d ->
                val frac = if (d.total > 0) (d.done.toFloat() / d.total).coerceIn(0f, 1f) else 0f
                val state = when (d.status) {
                    DownloadManager.STATUS_PENDING -> "waiting"
                    DownloadManager.STATUS_PAUSED -> "paused (network?)"
                    DownloadManager.STATUS_FAILED -> "failed"
                    DownloadManager.STATUS_SUCCESSFUL -> "done, moving to Models"
                    else -> String.format(Locale.ROOT, "%.0f %%", frac * 100)
                }
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.bodySmall)
                            Text("${sizeText(d.done)} of ${sizeText(d.total)} · $state", style = MaterialTheme.typography.labelSmall)
                        }
                        if (d.status != DownloadManager.STATUS_SUCCESSFUL)
                            TextButton(onClick = { HfDownloads.cancel(context, d.id); downloads = downloads.filter { it.id != d.id } }) { Text("Cancel") }
                    }
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                }
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
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Type:", style = MaterialTheme.typography.bodySmall)
                listOf("all" to "All", "moe" to "MoE", "dense" to "Dense").forEach { (k, l) ->
                    FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(l) })
                }
                Text("Sort:", style = MaterialTheme.typography.bodySmall)
                listOf("downloads" to "Downloads", "createdAt" to "Newest", "size" to "Size (smallest)").forEach { (k, l) ->
                    FilterChip(selected = sortBy == k, onClick = { sortBy = k }, label = { Text(l) })
                }
            }
            TextButton(onClick = { legend = !legend }) { Text(if (legend) "Hide format guide" else "Format guide (speed on a tablet)") }
            if (legend) {
                Text("Rough estimates for this app (CPU decode, NPU prefill), not measured on your device. Decode here is mostly compute-bound: fewer bits help mainly when the model would not fit or the cache is small.", style = MaterialTheme.typography.bodySmall)
                HF_LEGEND.forEach { (f, d) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(f, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp).weight(0.3f))
                        Text(d, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.7f))
                    }
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
                            "${r.downloads} downloads" + (r.files?.let { " · ${groupModels(it).size} model(s)" } ?: "") +
                                when (r.moe) { true -> " · MoE"; false -> " · dense"; null -> "" },
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
