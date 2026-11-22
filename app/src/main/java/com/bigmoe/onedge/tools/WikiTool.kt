package com.bigmoe.onedge.tools

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.ln

/**
 * The user's own knowledge: .md / .txt files in `<internal storage>/BigMoe/wiki` (without all-files access: the app's
 * external files folder), subfolders included. Searched with BM25 over an inverted index (term -> chunk ids and counts,
 * terms sorted so a word also finds longer words starting with it, e.g. "akku" -> "akkuverbrauch"). Only offsets are
 * kept for the chunks; their text is read from the file for the hits. Built on the first search, rebuilt when a file
 * changed, and dropped after [IDLE_MS] without a search so it does not hold RAM the expert cache could use.
 */
class WikiIndex private constructor(private val context: Context) {
    class Hit(val file: String, val heading: String, val text: String)
    class Stats(val dir: String, val files: Int, val chunks: Int)

    private class Built(
        val signature: String,
        val files: List<File>,
        val chunkFile: IntArray,
        val chunkStart: IntArray,
        val chunkEnd: IntArray,
        val chunkHead: Array<String>,
        val chunkLen: IntArray,
        val terms: Array<String>,
        val postDoc: Array<IntArray>,
        val postTf: Array<IntArray>,
        val avgLen: Double
    )

    @Volatile private var built: Built? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleJob: Job? = null

    fun dir(): File {
        val shared = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        val base = if (shared) File(Environment.getExternalStorageDirectory(), "BigMoe/wiki")
        else File(context.getExternalFilesDir(null) ?: context.filesDir, "wiki")
        return base.also { runCatching { it.mkdirs() } }
    }

    private fun files(): List<File> = dir().walkTopDown()
        .onEnter { !it.name.startsWith(".") }
        .filter { f ->
            f.isFile && !f.name.startsWith(".") && f.length() in 1..MAX_FILE_BYTES &&
                f.extension.lowercase() in setOf("md", "markdown", "txt")
        }
        .sortedBy { it.path }
        .toList()

    private fun signatureOf(files: List<File>) = files.joinToString("|") { "${it.path}:${it.length()}:${it.lastModified()}" }

    /** Files and chunks now (builds the index when needed). */
    fun stats(): Stats {
        val b = ensure()
        return Stats(dir().absolutePath, b.files.size, b.chunkLen.size)
    }

    /** Drops the index; the next search reads the folder again. */
    fun release() { synchronized(this) { built = null } }

    @Synchronized
    private fun ensure(): Built {
        touch()
        val files = files()
        val sig = signatureOf(files)
        built?.takeIf { it.signature == sig }?.let { return it }
        return build(files, sig).also { built = it }
    }

    private fun touch() {
        idleJob?.cancel()
        idleJob = scope.launch { delay(IDLE_MS); release() }
    }

    private fun build(files: List<File>, sig: String): Built {
        val cFile = IntArrayBuilder(); val cStart = IntArrayBuilder(); val cEnd = IntArrayBuilder(); val cLen = IntArrayBuilder()
        val cHead = ArrayList<String>()
        val postings = HashMap<String, Pair<IntArrayBuilder, IntArrayBuilder>>()
        files.forEachIndexed { fi, f ->
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEachIndexed
            chunk(text, f.nameWithoutExtension) { head, start, end ->
                val id = cLen.size
                val tokens = HelpIndex.tokenize(head + " " + head + " " + text.substring(start, end))
                val tf = HashMap<String, Int>()
                for (t in tokens) tf[t] = (tf[t] ?: 0) + 1
                for ((t, n) in tf) {
                    val p = postings.getOrPut(t) { IntArrayBuilder() to IntArrayBuilder() }
                    p.first.add(id); p.second.add(n)
                }
                cFile.add(fi); cStart.add(start); cEnd.add(end); cLen.add(tokens.size); cHead.add(head)
            }
        }
        val terms = postings.keys.sorted().toTypedArray()
        val lens = cLen.toArray()
        return Built(
            sig, files, cFile.toArray(), cStart.toArray(), cEnd.toArray(), cHead.toTypedArray(), lens, terms,
            Array(terms.size) { postings[terms[it]]!!.first.toArray() },
            Array(terms.size) { postings[terms[it]]!!.second.toArray() },
            if (lens.isEmpty()) 1.0 else lens.average()
        )
    }

    fun search(query: String, limit: Int = 4): List<Hit> {
        val b = ensure()
        val n = b.chunkLen.size
        if (n == 0) return emptyList()
        val scores = DoubleArray(n)
        fun addTerm(i: Int, weight: Double) {
            val docs = b.postDoc[i]; val tfs = b.postTf[i]
            val idf = ln(1.0 + (n - docs.size + 0.5) / (docs.size + 0.5))
            for (k in docs.indices) {
                val f = tfs[k].toDouble(); val d = docs[k]
                scores[d] += weight * idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * b.chunkLen[d] / b.avgLen))
            }
        }
        for (t in HelpIndex.tokenize(query).distinct()) {
            var i = java.util.Arrays.binarySearch(b.terms, t)
            if (i >= 0) addTerm(i, 1.0) else i = -i - 1
            if (t.length >= 4) { // longer words starting with it: German compounds ("akku" -> "akkuverbrauch")
                var j = if (i < b.terms.size && b.terms[i] == t) i + 1 else i
                var taken = 0
                while (j < b.terms.size && b.terms[j].startsWith(t) && taken < MAX_PREFIX_TERMS) {
                    addTerm(j, PREFIX_WEIGHT); j++; taken++
                }
            }
        }
        val top = scores.indices.filter { scores[it] > 0.0 }.sortedByDescending { scores[it] }.take(limit)
        val texts = HashMap<Int, String?>()
        return top.mapNotNull { c ->
            val fi = b.chunkFile[c]
            val text = texts.getOrPut(fi) { runCatching { b.files[fi].readText(Charsets.UTF_8) }.getOrNull() } ?: return@mapNotNull null
            if (b.chunkEnd[c] > text.length) return@mapNotNull null // changed since the build; the next search rebuilds
            Hit(b.files[fi].relativeTo(dir()).path, b.chunkHead[c], text.substring(b.chunkStart[c], b.chunkEnd[c]).trim())
        }
    }

    private class IntArrayBuilder {
        private var a = IntArray(8)
        var size = 0
            private set
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun toArray(): IntArray = a.copyOf(size)
    }

    companion object {
        private const val MAX_CHARS = 1100
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024
        private const val IDLE_MS = 10 * 60_000L
        private const val K1 = 1.4
        private const val B = 0.75
        private const val PREFIX_WEIGHT = 0.5
        private const val MAX_PREFIX_TERMS = 40
        private val HEADING = Regex("^#{1,6}\\s+(.*)$")
        private val LIST_ITEM = Regex("^\\s*([-*+]|\\d+\\.)\\s")

        @Volatile private var instance: WikiIndex? = null
        fun shared(context: Context): WikiIndex =
            instance ?: synchronized(this) { instance ?: WikiIndex(context.applicationContext).also { instance = it } }

        /**
         * Cuts [text] into chunks of about [MAX_CHARS] along headings, blank lines and list items (as
         * scripts/build-help-index.py does); [emit] gets the heading in force and the chunk's character range.
         */
        fun chunk(text: String, title: String, emit: (String, Int, Int) -> Unit) {
            val paras = ArrayList<Triple<String, Int, Int>>()
            var head = title
            var pStart = -1; var pEnd = -1
            fun close() { if (pStart >= 0) paras.add(Triple(head, pStart, pEnd)); pStart = -1 }
            var pos = 0
            while (pos <= text.length) {
                val nl = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
                val line = text.substring(pos, nl)
                val h = HEADING.find(line.trimEnd())
                when {
                    h != null -> { close(); head = h.groupValues[1].trim().ifBlank { title } }
                    line.isBlank() -> close()
                    else -> {
                        if (LIST_ITEM.containsMatchIn(line)) close()
                        if (pStart < 0) pStart = pos
                        pEnd = nl
                    }
                }
                if (nl >= text.length) break
                pos = nl + 1
            }
            close()
            // Long paragraphs are cut at a sentence end; short ones under the same heading are joined up to MAX_CHARS.
            var cHead: String? = null; var cStart = -1; var cEnd = -1
            fun flush() { if (cStart >= 0) emit(cHead ?: title, cStart, cEnd); cStart = -1 }
            for ((hd, s0, e) in paras) {
                var s = s0
                while (e - s > MAX_CHARS * 16 / 10) {
                    val window = text.substring(s + MAX_CHARS / 2, s + MAX_CHARS)
                    val cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf('\n')).let { if (it < 0) MAX_CHARS else MAX_CHARS / 2 + it + 1 }
                    flush(); emit(hd, s, s + cut); s += cut
                }
                if (cStart >= 0 && (hd != cHead || e - cStart > MAX_CHARS)) flush()
                if (cStart < 0) { cHead = hd; cStart = s }
                cEnd = e
            }
            flush()
        }
    }
}

/** Searches the user's own notes and wiki ([WikiIndex]). */
class WikiSearchTool(private val index: WikiIndex) : Tool {
    override val name = "wiki_search"
    override val category = ToolCategory.HELP
    override val description =
        "Search the user's own notes and wiki (files the user put into the app's wiki folder: devices, setups, how-tos, " +
            "own documentation). Use it before any web search when the user's notes could answer the question. The notes " +
            "can be German or English: write the query as 3 to 8 keywords in BOTH languages (e.g. \"lüfter kühlung fan " +
            "cooling\"), then answer in the user's language from the passages."
    override val parametersSchema = toolSchema(
        listOf(Triple("query", "string", "Keywords in German and English, e.g. \"akku laufzeit battery life\"")),
        listOf("query")
    )
    override val parallelSafe = true
    override fun describe(arguments: JSONObject) = "Searching the wiki: ${arguments.optString("query").take(50)}"

    override suspend fun execute(arguments: JSONObject): String {
        val query = arguments.requireString("query")
        val (hits, stats) = withContext(Dispatchers.IO) { index.search(query) to index.stats() }
        if (stats.files == 0) return "The wiki folder (${stats.dir}) is empty. Tell the user to put .md or .txt files there."
        if (hits.isEmpty()) return "No match for \"$query\" in the user's wiki. Try other keywords, in German and English."
        val sb = StringBuilder()
        for (h in hits) sb.append("[").append(h.file).append(" - ").append(h.heading).append("]\n").append(h.text.take(1800)).append("\n\n")
        sb.append("These are the user's own notes. Answer in the user's language from these passages; if they do not answer the question, say so or search again with other keywords.")
        return sb.toString()
    }
}

val WIKI_TOOL_NAMES = setOf("wiki_search")
