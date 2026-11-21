package com.bigmoe.onedge.core

import android.os.Build
import com.bigmoe.onedge.data.local.entity.PerfEventEntity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns the performance log into a report: answers grouped by model and by the settings that change speed or RAM,
 * medians over comparable answers only (not throttled), crashes matched to the load they came from, groups ranked
 * by the owner's goal order — no crash, then decode speed, then time to first token. The numbers are computed here;
 * a model asked for advice only reads this report (it is bad at arithmetic over raw rows).
 */
object PerfAnalyzer {

    /** The load settings an answer is grouped by (names as in LoadConfig; "npu" is whether the NPU was really used). */
    val GROUP_KEYS = listOf(
        "cacheMb", "batchWidth", "nThreads", "nThreadsBatch", "denseWeights", "kvCacheType",
        "spec", "nCtx", "nExpertUsed", "ioThreads", "prefetchLayers"
    )

    /** Fewer comparable answers than this: the group is listed but not ranked. */
    const val MIN_ANSWERS = 3

    /** Android thermal status from which an answer counts as throttled (2 = moderate). */
    const val THROTTLED = 2

    /** Answers with fewer generated tokens than this say nothing about the decode speed (cold first tokens). */
    const val MIN_GENERATED = 32

    /** A run that ended this long after its last answer or load went while idle (Android clearing a background app). */
    const val IDLE_SECONDS = 120L

    /** Exit reasons that are the user's doing, not the settings'. */
    private val USER_EXITS = setOf("USER_REQUESTED", "USER_STOPPED", "PERMISSION_CHANGE")

    /** Whether a crash entry counts against the settings it ran with: it ended while in use, not idle, not by the user. */
    fun crashInUse(d: JSONObject): Boolean =
        d.optLong("idleSeconds", 0L) <= IDLE_SECONDS && d.optString("exitReason") !in USER_EXITS

    private class Turn(val at: Long, val d: JSONObject, val key: String)

    private class Group(val key: String) {
        val turns = mutableListOf<Turn>()
        var throttled = 0
        var short = 0
        var crashes = 0
        var idleEnds = 0
        val comparable get() = turns
    }

    /** [live]: values that can change after the load (threads follow the CPU cores live) and win over cfg. */
    fun settingsKey(cfg: JSONObject?, npu: Boolean, live: Map<String, Any> = emptyMap()): String {
        val parts = mutableListOf("npu=${if (npu) "on" else "off"}")
        if (cfg != null) {
            for (k in GROUP_KEYS) {
                if (!cfg.has(k) && k !in live) continue
                val v = live[k] ?: cfg.get(k)
                // The cache size only means something in FIXED mode.
                if (k == "cacheMb" && cfg.optString("cacheMode") != "FIXED") {
                    parts += "cache=${cfg.optString("cacheMode").lowercase()}"
                    continue
                }
                parts += "$k=$v"
            }
        }
        return parts.joinToString(" ")
    }

    /** The report as text; [model] limits it to one model (file name as logged), null = all. */
    fun report(events: List<PerfEventEntity>, model: String? = null, totalRamMb: Long = -1): String {
        val sel = if (model == null) events else events.filter { it.model == model }
        val parsed = sel.mapNotNull { e -> runCatching { e to JSONObject(e.data) }.getOrNull() }
        val sb = StringBuilder()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        sb.appendLine("# Performance log summary")
        sb.appendLine(
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}" +
                (if (Build.VERSION.SDK_INT >= 31) ", SoC ${Build.SOC_MODEL}" else "") +
                (if (totalRamMb > 0) ", RAM ${totalRamMb} MiB" else "") + ", Android SDK ${Build.VERSION.SDK_INT}"
        )
        if (parsed.isEmpty()) {
            sb.appendLine("No entries" + (model?.let { " for $it" } ?: "") + " yet.")
            return sb.toString()
        }
        val counts = parsed.groupingBy { it.first.kind }.eachCount()
        sb.appendLine(
            "Period: ${fmt.format(Date(parsed.first().first.at))} to ${fmt.format(Date(parsed.last().first.at))}; " +
                "loads ${counts["load"] ?: 0}, failed loads ${counts["load_failed"] ?: 0}, answers ${counts["turn"] ?: 0}, " +
                "crashes ${counts["crash"] ?: 0}"
        )
        val builds = parsed.mapNotNull { (_, d) -> d.optString("app").takeIf { it.isNotEmpty() } }
        if (builds.isNotEmpty()) {
            sb.appendLine(
                "App builds: " + builds.groupingBy { it }.eachCount().entries.joinToString(", ") { "${it.key} (${it.value})" } +
                    (if (builds.size < parsed.size) ", older entries without a build" else "")
            )
        }
        sb.appendLine(
            "Ranking: groups without crash first, then median decode t/s (higher), then median time to first token " +
                "(lower). Medians use answers that started below thermal status $THROTTLED (moderate) and generated at " +
                "least $MIN_GENERATED tokens; groups with fewer than $MIN_ANSWERS such answers are listed but not ranked. " +
                "A crash counts only when the app ended within $IDLE_SECONDS s of its last answer or load and not by the user."
        )

        for ((name, list) in parsed.groupBy { it.first.model }) {
            sb.appendLine()
            sb.appendLine("## Model: $name")
            val loads = list.filter { it.first.kind == "load" }
            // Each answer and crash belongs to the newest load of this model before it.
            fun loadBefore(at: Long) = loads.lastOrNull { it.first.at <= at }
            val groups = linkedMapOf<String, Group>()
            for ((e, d) in list) {
                when (e.kind) {
                    "turn" -> {
                        val live = buildMap<String, Any> {
                            if (d.has("decodeThreads")) put("nThreads", d.getInt("decodeThreads"))
                            if (d.has("prefillThreads")) put("nThreadsBatch", d.getInt("prefillThreads"))
                        }
                        val key = settingsKey(d.optJSONObject("cfg"), d.optBoolean("npuReady"), live)
                        val g = groups.getOrPut(key) { Group(key) }
                        when {
                            d.optInt("startThermal", 0) >= THROTTLED -> g.throttled++
                            d.has("generated") && d.optInt("generated") < MIN_GENERATED -> g.short++
                            else -> g.turns += Turn(e.at, d, key)
                        }
                    }
                    "crash" -> {
                        val load = loadBefore(e.at) ?: continue
                        val key = settingsKey(load.second.optJSONObject("cfg"), load.second.optBoolean("npuReady"))
                        val g = groups.getOrPut(key) { Group(key) }
                        if (crashInUse(d)) g.crashes++ else g.idleEnds++
                    }
                }
            }
            if (loads.isNotEmpty()) {
                val secs = loads.map { it.second.optDouble("loadSeconds", Double.NaN) }.filter { !it.isNaN() }
                val after = loads.map { it.second.optLong("freeAfterMb", -1) }.filter { it >= 0 }
                sb.appendLine(
                    "Loads: ${loads.size}" + (median(secs)?.let { ", median load time ${f1(it)} s" } ?: "") +
                        (after.minOrNull()?.let { ", lowest free RAM right after a load $it MiB" } ?: "")
                )
            }
            val ranked = groups.values.sortedWith(
                compareBy<Group>({ it.crashes > 0 }, { it.comparable.size < MIN_ANSWERS })
                    .thenByDescending { median(it.comparable.map { t -> t.d.optDouble("decodeTps") }) ?: 0.0 }
                    .thenBy { median(it.comparable.map { t -> t.d.optDouble("prefillSeconds") }) ?: Double.MAX_VALUE }
            )
            ranked.forEachIndexed { i, g -> sb.append(groupLine(i + 1, g)) }
            list.filter { it.first.kind == "crash" }.forEach { (e, d) ->
                val inUse = crashInUse(d)
                val idle = d.optLong("idleSeconds", -1L)
                sb.appendLine(
                    (if (inUse) "Crash " else "Ended while idle (not counted) ") + "${fmt.format(Date(e.at))}: ${d.optString("config")}" +
                        (if (d.has("minFreeMb")) ", lowest free RAM seen ${d.getLong("minFreeMb")} MiB" else "") +
                        (if (idle >= 0) ", ${idle / 60} min ${idle % 60} s after the last answer or load" else "") +
                        (d.optString("exitReason").takeIf { it.isNotEmpty() }?.let { r ->
                            ", Android: $r" + (if (d.has("exitImportance")) " (importance ${d.getInt("exitImportance")})" else "")
                        } ?: "")
                )
            }
            list.filter { it.first.kind == "load_failed" }.takeLast(5).forEach { (e, d) ->
                sb.appendLine("Failed load ${fmt.format(Date(e.at))}: ${d.optString("error").lineSequence().firstOrNull().orEmpty()}")
            }
        }
        return sb.toString()
    }

    private fun groupLine(rank: Int, g: Group): String {
        val t = g.comparable
        val sb = StringBuilder()
        sb.appendLine("$rank. ${g.key}")
        val decode = t.map { it.d.optDouble("decodeTps") }.filter { it > 0 }
        // Prefill speed only from answers that really prefilled something (not a few tokens after a reused prefix).
        val pre = t.filter { it.d.optInt("prefillTokens") >= 64 && it.d.optDouble("prefillSeconds") > 0 }
            .map { it.d.optInt("prefillTokens") / it.d.optDouble("prefillSeconds") }
        val ttft = t.map { it.d.optDouble("prefillSeconds") }.filter { it > 0 }
        val hit = t.map { it.d.optDouble("cacheHitPct", -1.0) }.filter { it >= 0 }
        val ctx = t.map { it.d.optInt("ctxUsed").toDouble() }.filter { it > 0 }
        val nCtx = t.firstOrNull()?.d?.optInt("nCtx") ?: 0
        val ram = t.mapNotNull { if (it.d.has("minFreeMb")) it.d.getLong("minFreeMb") else null }
        val left = listOfNotNull(
            g.throttled.takeIf { it > 0 }?.let { "$it throttled" },
            g.short.takeIf { it > 0 }?.let { "$it under $MIN_GENERATED tokens" }
        )
        val parts = mutableListOf("answers ${t.size}" + if (left.isNotEmpty()) " (+${left.joinToString(", ")}, left out)" else "")
        if (g.crashes > 0) parts += "CRASHES ${g.crashes}"
        if (g.idleEnds > 0) parts += "ended while idle ${g.idleEnds} (not counted)"
        median(decode)?.let { parts += "decode median ${f1(it)} t/s (${f1(decode.min())}–${f1(decode.max())})" }
        median(pre)?.let { parts += "prefill median ${f1(it)} t/s (n=${pre.size})" }
        median(ttft)?.let { parts += "time to first token median ${f1(it)} s" }
        median(hit)?.let { parts += "expert cache hits median ${f1(it)} %" }
        median(ctx)?.let { parts += "context used median ${it.toInt()}" + if (nCtx > 0) " of $nCtx" else "" }
        ram.minOrNull()?.let { parts += "lowest free RAM $it MiB" }
        // The engine's own figures (answers recorded from v0.6.91 on).
        val eng = t.mapNotNull { it.d.optJSONObject("engine") }
        if (eng.isNotEmpty()) {
            fun med(k: String) = median(eng.filter { it.has(k) }.map { it.optDouble(k) })
            med("cache_resident_mib")?.let { r ->
                parts += "cache filled median ${r.toInt()} of ${med("cache_budget_mib")?.toInt() ?: "?"} MiB"
            }
            med("stall_s_per_token")?.let { parts += "flash stall median ${f1(it * 1000)} ms/token" }
            med("cores_busy")?.let { parts += "cores busy median ${f1(it)}" }
            val drafted = eng.sumOf { it.optDouble("drafted", 0.0) }
            if (drafted > 0) parts += "guess ahead accepted ${f1(100 * eng.sumOf { it.optDouble("accepted", 0.0) } / drafted)} % of ${drafted.toInt()} drafted"
            val npuTurns = eng.count { it.optDouble("prefill_device_nodes", 0.0) > 0 }
            parts += "prefill on NPU in $npuTurns of ${eng.size}"
        }
        sb.appendLine("   " + parts.joinToString(" | "))
        if (t.size < MIN_ANSWERS) sb.appendLine("   not ranked: fewer than $MIN_ANSWERS comparable answers")
        return sb.toString()
    }

    private fun median(v: List<Double>): Double? {
        val s = v.filter { !it.isNaN() }.sorted()
        if (s.isEmpty()) return null
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)

    /** All entries as CSV (newest [limit]); the `data` JSON is flattened, the load config as cfg.<name>. */
    fun csv(events: List<PerfEventEntity>, limit: Int = 1000): String {
        val rows = events.takeLast(limit).map { e ->
            val flat = linkedMapOf("id" to e.id.toString(), "at" to e.at.toString(), "kind" to e.kind, "model" to e.model)
            runCatching {
                val d = JSONObject(e.data)
                for (k in d.keys()) {
                    val v = d.get(k)
                    if (v is JSONObject) for (ck in v.keys()) flat["$k.$ck"] = v.get(ck).toString()
                    else flat[k] = v.toString()
                }
            }
            flat
        }
        val head = listOf("id", "at", "kind", "model")
        fun order(c: String) = (if (c.startsWith("cfg.")) "2" else if (c.contains('.')) "1" else "0") + c
        val all = rows.flatMap { it.keys }.distinct().filter { it !in head }.sortedBy(::order)
        // A column with the same value in every row (empty counts) says nothing per row: it is listed once above
        // the table instead — about half the columns, and every one of them costs a reader's tokens on each row.
        val same = if (rows.size < 2) emptyList() else all.filter { c -> rows.map { it[c].orEmpty() }.distinct().size == 1 }
        val cols = head + (all - same.toSet())
        fun esc(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        return buildString {
            val shown = same.filter { c -> rows.first()[c].orEmpty().isNotEmpty() }
            if (shown.isNotEmpty()) appendLine("# same in every row: " + shown.joinToString("; ") { "$it=${rows.first()[it]}" })
            appendLine(cols.joinToString(","))
            rows.forEach { r -> appendLine(cols.joinToString(",") { esc(r[it].orEmpty()) }) }
        }
    }
}
