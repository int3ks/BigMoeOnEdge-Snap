package com.bigmoe.onedge.tools

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import kotlin.math.ln

/**
 * Keyword search (BM25, offline) over the app's own knowledge: docs, change log and the longer setting texts, cut into
 * chunks by scripts/build-help-index.py into assets/help/help.json. Loaded on the first search.
 */
class HelpIndex(private val open: () -> InputStream) {
    class Hit(val source: String, val heading: String, val text: String, val score: Double)

    private class Chunk(val source: String, val heading: String, val text: String, val tf: Map<String, Int>, val len: Int)

    private var chunks: List<Chunk> = emptyList()
    private var df: Map<String, Int> = emptyMap()
    private var avgLen = 1.0
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        val arr = JSONArray(open().use { it.readBytes().toString(Charsets.UTF_8) })
        val list = ArrayList<Chunk>(arr.length())
        val docFreq = HashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val heading = o.optString("h")
            val text = o.optString("t")
            val tokens = tokenize("$heading $heading $text")
            val tf = HashMap<String, Int>()
            for (t in tokens) tf[t] = (tf[t] ?: 0) + 1
            for (t in tf.keys) docFreq[t] = (docFreq[t] ?: 0) + 1
            list.add(Chunk(o.optString("s"), heading, text, tf, tokens.size))
        }
        chunks = list
        df = docFreq
        avgLen = if (list.isEmpty()) 1.0 else list.sumOf { it.len }.toDouble() / list.size
        loaded = true
    }

    fun search(query: String, limit: Int = 4): List<Hit> {
        ensureLoaded()
        val terms = tokenize(query).distinct()
        if (terms.isEmpty()) return emptyList()
        val n = chunks.size.toDouble()
        val k1 = 1.4
        val b = 0.75
        val scored = ArrayList<Hit>()
        for (c in chunks) {
            var score = 0.0
            for (t in terms) {
                fun add(term: String, f: Int, weight: Double) {
                    val d = (df[term] ?: 0).toDouble()
                    val idf = ln(1.0 + (n - d + 0.5) / (d + 0.5))
                    score += weight * idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * c.len / avgLen))
                }
                c.tf[t]?.let { add(t, it, 1.0) }
                // Longer words starting with it count half (compounds: "akku" -> "akkuverbrauch").
                if (t.length >= 4) for ((k, f) in c.tf) if (k.length > t.length && k.startsWith(t)) add(k, f, 0.5)
            }
            if (score > 0.0) scored.add(Hit(c.source, c.heading, c.text, score))
        }
        return scored.sortedByDescending { it.score }.take(limit)
    }

    companion object {
        private val WORD = Regex("[\\p{L}\\p{N}]+")

        /** Lower-case words (any letters, umlauts included) of 2+ characters with a plural s removed ("experts" -> "expert"). */
        fun tokenize(text: String): List<String> = WORD.findAll(text.lowercase()).map { it.value }
            .filter { it.length >= 2 }
            .map { if (it.length > 3 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }
            .toList()
    }
}

/** Answers questions about this app and its engine from the bundled documentation (see [HelpIndex]). */
class AppHelpTool(private val index: HelpIndex) : Tool {
    override val name = "app_help"
    override val category = ToolCategory.HELP
    override val description =
        "Look up how this app (BigMoeOnEdge: settings, features, tools, models, expert cache, NPU, API server) and its " +
            "inference engine work, in the app's own documentation, change log and setting texts. The documentation is in " +
            "English: always write the query as 2 to 6 short English keywords, translating it first when the user wrote " +
            "another language (e.g. \"cold experts drop MADV_FREE\"), then answer the user in the user's own language."
    override val parametersSchema = toolSchema(
        listOf(Triple("query", "string", "English keywords, e.g. \"auto cache grow reserve zram\"")),
        listOf("query")
    )
    override val parallelSafe = true
    override fun describe(arguments: JSONObject) = "Looking up: ${arguments.optString("query").take(50)}"

    override suspend fun execute(arguments: JSONObject): String {
        val query = arguments.requireString("query")
        val hits = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { index.search(query) }
        if (hits.isEmpty()) return "No match for \"$query\". The documentation is English: try other English keywords."
        val sb = StringBuilder()
        for (h in hits) {
            sb.append("[").append(h.source).append(" - ").append(h.heading).append("]\n").append(h.text.take(1800)).append("\n\n")
        }
        sb.append("Sources are English. Answer in the user's language, from these passages only; if they do not answer the question, say so or search again with other keywords.")
        return sb.toString()
    }
}

val HELP_TOOL_NAMES = setOf("app_help")
