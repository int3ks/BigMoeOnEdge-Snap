package com.bigmoe.onedge.core

import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.datastore.EngineSettingsDataStore
import org.json.JSONObject

/**
 * Settings advice from the performance log: the prompt a local model gets, the intro for an export read by a new
 * Claude chat, and the `bmoe-settings` block an answer may contain — parsed, checked against the settings that
 * exist and their ranges, shown as "current -> suggested" and applied only when the user taps Apply.
 */
object SettingsAdvice {

    const val BLOCK = "bmoe-settings"

    /** A global setting a suggestion may change: [key] is the name used in the block. */
    class Knob(
        val key: String,
        val label: String,
        val cfgName: String,
        val range: String,
        val current: (EngineSettings) -> String,
        /** The value from JSON as the setting's type, or null when it is not valid. */
        val parse: (Any) -> Any?,
        val apply: suspend (EngineSettingsDataStore, Any) -> Unit,
        /** How a value (as [current] gives it, or as [parse] returns it) is shown to the user. */
        val show: (String) -> String = { it }
    )

    private fun int(
        key: String, label: String, cfgName: String, min: Int, max: Int, note: String,
        get: (EngineSettings) -> Int, set: suspend (EngineSettingsDataStore, Int) -> Unit
    ) = Knob(
        key, label, cfgName, "integer $min..$max$note", { get(it).toString() },
        { v -> (v as? Number)?.toDouble()?.takeIf { it % 1.0 == 0.0 }?.toInt()?.takeIf { it in min..max } },
        { ds, v -> set(ds, v as Int) }
    )

    private fun bool(
        key: String, label: String, cfgName: String,
        get: (EngineSettings) -> Boolean, set: suspend (EngineSettingsDataStore, Boolean) -> Unit
    ) = Knob(key, label, cfgName, "true or false", { get(it).toString() }, { v -> v as? Boolean }, { ds, v -> set(ds, v as Boolean) })

    private inline fun <reified E : Enum<E>> enumKnob(
        key: String, label: String, cfgName: String,
        noinline get: (EngineSettings) -> E, noinline set: suspend (EngineSettingsDataStore, E) -> Unit
    ): Knob {
        val values = enumValues<E>()
        return Knob(
            key, label, cfgName, values.joinToString(" | ") { it.name }, { get(it).name },
            { v -> values.firstOrNull { it.name.equals(v.toString(), ignoreCase = true) } },
            { ds, v -> set(ds, v as E) }
        )
    }

    /** Core numbers of a mask, e.g. "0,1,6,7". */
    fun coreList(mask: Long): String = (0 until 64).filter { (mask shr it) and 1L == 1L }.joinToString(",")

    private fun showMask(raw: String): String {
        val m = raw.toLongOrNull() ?: return raw
        return "${java.lang.Long.bitCount(m)} threads (cores ${coreList(m)})"
    }

    /**
     * The threads are the CPU cores chosen in Settings (one thread per core). A suggestion gives either a count —
     * the app takes that many of the fastest cores — or a list of core numbers.
     */
    private fun cores(
        key: String, label: String, cfgName: String,
        get: (EngineSettings) -> Long, set: suspend (EngineSettingsDataStore, Long) -> Unit
    ) = Knob(
        key, label, cfgName,
        "a count 1..${CpuTopology.coreCount} (the fastest cores) or a list of core numbers 0..${CpuTopology.coreCount - 1}",
        { CpuTopology.effective(get(it)).toString() },
        { v ->
            val n = CpuTopology.coreCount
            val mask = when (v) {
                is Number -> v.toInt().takeIf { it in 1..n }?.let { c ->
                    CpuTopology.fastestFirst(0L).take(c).fold(0L) { m, core -> m or (1L shl core) }
                }
                is org.json.JSONArray -> (0 until v.length()).map { v.optInt(it, -1) }
                    .takeIf { l -> l.isNotEmpty() && l.all { it in 0 until n } }
                    ?.fold(0L) { m, core -> m or (1L shl core) }
                else -> null
            }
            mask
        },
        { ds, v -> set(ds, v as Long) },
        ::showMask
    )

    val KNOBS: List<Knob> = listOf(
        int("cacheMb", "Expert cache (MiB)", "cacheMb/cacheMode", -1, 65536, "; -1 = auto from free RAM, 0 = off",
            { it.cacheMb }, { ds, v -> ds.updateCacheMb(v) }),
        bool("npuPrefill", "NPU prefill", "npuPrefill", { it.npuPrefill }, { ds, v -> ds.updateNpuPrefill(v) }),
        int("batchWidth", "Prompt batch width", "batchWidth", 32, 8192, "", { it.batchWidth }, { ds, v -> ds.updateBatchWidth(v) }),
        cores("decodeCores", "CPU cores (decode)", "nThreads / decodeCores", { it.cpuMask }, { ds, m -> ds.updateCpuMask(m) }),
        cores("prefillCores", "CPU cores (prefill)", "nThreadsBatch / prefillCores", { it.cpuMaskPrefill }, { ds, m -> ds.updateCpuMaskPrefill(m) }),
        int("contextLength", "Context length", "nCtx", 512, 262144, "", { it.contextLength }, { ds, v -> ds.updateContextLength(v) }),
        enumKnob<DenseWeights>("denseWeights", "Dense weights", "denseWeights", { it.denseWeights }, { ds, v -> ds.updateDenseWeights(v) }),
        int("ioThreads", "I/O lanes", "ioThreads", 1, 8, "", { it.ioThreads }, { ds, v -> ds.updateIoThreads(v) }),
        int("prefetchLayers", "Prefetch layers", "prefetchLayers", 0, 8, "", { it.prefetchLayers }, { ds, v -> ds.updatePrefetchLayers(v) }),
        int("nExpertUsed", "Experts per token", "nExpertUsed", 0, 64, "; 0 = the model's own (fewer = faster, worse answers)",
            { it.nExpertUsed }, { ds, v -> ds.updateNExpertUsed(v) }),
        enumKnob<SpecSource>("spec", "Guess ahead", "spec", { it.spec }, { ds, v -> ds.updateSpec(v) }),
        int("npuMinTokens", "NPU from tokens", "npuMinTokens", 2, 2048, "", { it.npuMinTokens }, { ds, v -> ds.updateNpuMinTokens(v) }),
        int("npuLoaders", "NPU loaders", "npuLoaders", 1, 16, "", { it.npuLoaders }, { ds, v -> ds.updateNpuLoaders(v) })
    )

    data class Change(val knob: Knob, val current: String, val value: Any)
    data class Suggestion(val changes: List<Change>, val ignored: List<String>)

    private val BLOCK_RE = Regex("```$BLOCK\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

    fun hasBlock(text: String): Boolean = text.contains("```$BLOCK")

    /** The last `bmoe-settings` block of [text]: valid changes that differ from [settings], and what was left out. */
    fun parse(text: String, settings: EngineSettings): Suggestion? {
        val body = BLOCK_RE.findAll(text).lastOrNull()?.groupValues?.get(1) ?: return null
        val json = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return Suggestion(emptyList(), listOf("the block is not valid JSON"))
        val changes = mutableListOf<Change>()
        val ignored = mutableListOf<String>()
        for (key in json.keys()) {
            val knob = KNOBS.firstOrNull { it.key == key }
            if (knob == null) { ignored += "$key (no such setting)"; continue }
            val value = knob.parse(json.get(key))
            if (value == null) { ignored += "$key = ${json.get(key)} (allowed: ${knob.range})"; continue }
            val cur = knob.current(settings)
            if (value.toString() != cur) changes += Change(knob, cur, value)
        }
        return Suggestion(changes, ignored)
    }

    suspend fun apply(store: EngineSettingsDataStore, changes: List<Change>) {
        changes.forEach { it.knob.apply(store, it.value) }
    }

    private fun knobList(settings: EngineSettings?): String = KNOBS.joinToString("\n") { k ->
        "- ${k.key} (${k.label}; logged as cfg.${k.cfgName}): ${k.range}" + (settings?.let { " — now ${k.show(k.current(it))}" } ?: "")
    }

    /** What every setting does and what each logged field means; shared by both prompts. */
    private val BACKGROUND = """
        BigMoeOnEdge² is an Android app (Kotlin + a C++ engine on llama.cpp) that runs Mixture-of-Experts GGUF models
        larger than the device's RAM: the dense weights (attention, shared parts) stay in RAM, the routed experts are
        streamed from flash storage per token, and an expert cache in RAM keeps recently used experts.

        What the settings do:
        - Expert cache (cacheMb): more cache = more cache hits = faster decode, but it takes RAM. The cache is only
          address space at load and takes real RAM as experts are loaded, so the RAM shows up during use, not at load.
          Android kills the app when free RAM runs out (logged as a crash).
        - NPU prefill (npuPrefill): the prompt is processed on the Hexagon NPU — much faster prefill. It costs RAM of its
          own: two slots of the largest layer's experts, two slots of the other layer weights and Q8_0 copies of weights
          the NPU cannot take as they are. Without it the CPU prefills and the expert cache fills up right away.
        - Prompt batch width (batchWidth): tokens per prefill pass, CPU and NPU. The CPU's compute buffer grows with it
          (measured about 80 MiB at 512, 2.2 GB at 2048 on a 35B-A3B). Decode is not affected.
        - Threads: one thread per CPU core chosen in Settings, separately for decode (decodeCores, logged as
          decodeThreads/decodeCores, live) and prefill (prefillCores). cfg.nThreads/nThreadsBatch are the counts at
          load time. (The old "threadCount" settings are not used any more.)
        - Dense weights (denseWeights): MMAP (pageable, Android can drop and re-read them), WARMED, ANONYMOUS (copied
          into RAM, the default), PINNED (locked in RAM).
        - Guess ahead (spec): OFF, MTP (the model's own prediction head) or NGRAM (drafts from text already seen).
        - Experts per token (nExpertUsed): 0 = the model's own top-k; fewer is faster but gives worse answers.
        - Context length (nCtx), I/O lanes (ioThreads), prefetch layers (prefetchLayers), NPU thresholds.
        - NPU and expert cache can also be set per model on its card; a card value overrides the global one.

        What the logged fields mean:
        - turn (one answer): promptChars = length of the user message; prefillTokens = tokens actually prefilled
          (small when the chat's earlier context was reused from the KV cache); prefillSeconds ≈ time to first token;
          ctxUsed of nCtx = context filled after the answer; generated = answer tokens; decodeTps; cacheHitPct = expert
          cache hits; minFreeMb = lowest free RAM during the answer (sampled every 2 s); maxSwapMb = most of the app in swap (zram) during it; startThermal/endThermal =
          Android thermal status (0 none, 1 light, 2 moderate, 3 severe, 4 critical); Headroom = how close the device
          is to throttling (1.0 = throttling now); BatteryC = battery temperature; cfg.* = all load settings;
          engine.* = the engine's own figures for the answer: n_prompt_total/n_reused (prompt tokens, of them reused
          from the KV cache), cache_resident_mib/cache_budget_mib (expert cache really filled / its budget),
          cache_evictions, moe_read_mib (read from flash), s_per_token split into compute/io/stall_s_per_token,
          cores_busy (cpu time / wall time; near the thread count = compute-bound, low = waiting), majflt_per_token,
          drafted/accepted/verify_decodes (guess ahead: proposed and confirmed tokens), prefill_device_tokens/nodes
          (> 0 = the prefill really ran on the NPU), kv_diverge (first prompt token that differed from the cached KV;
          n_reused below it = a checkpoint stood in).
        - load: loadSeconds, freeBeforeMb (only when no model was loaded before; "replaced" names the model a load
          replaced), freeAfterMb, npuReady (the NPU was really set up), swapTotalMb/swapFreeMb (0 = the device has no
          swap: then majflt are file pages read again, not swap-ins), cfg.*.
        - crash: the app ended while a model was loaded; minFreeMb is the lowest free RAM seen since that load; it is
          matched to the newest load of that model before it. idleSeconds = time from the last answer or load to the
          last moment the app was seen alive; exitReason/exitImportance = Android's record of the end (LOW_MEMORY,
          SIGNALED, CRASH_NATIVE, USER_REQUESTED …; importance 100 = foreground, 400 = cached in the background).
          A crash idle for more than 120 s or ended by the user is not counted against the settings.
        - load_failed: the error of a load that did not finish.
        - Every entry: app/appCode = the build that recorded it (older entries have none). Prefer the newest build's
          numbers; an engine change can make older ones stale.

        Careful: compare like with like — an answer whose prompt came from the KV cache prefills almost nothing; longer
        contexts are slower; a hot device throttles (answers that started at thermal status 2 or more are left out of
        the medians, and so are answers under 32 generated tokens). Few answers per setting mean little. Goal order: no crash first, then decode speed, then time to
        first token.
    """.trimIndent()

    /** For the export: a new Claude chat reads this first. */
    fun exportIntro(settings: EngineSettings?): String = """
        # BigMoeOnEdge² performance log — please read this first

        You are getting the performance log of BigMoeOnEdge², an app the owner (Tom) develops with Claude. He tries
        models and settings on his Android device and wants to know which settings work best — for the device in
        general and per model. Please answer in German; Tom is a developer, talk to him as a peer.

        @@BACKGROUND@@

        What I would like from you:
        1. Per model and for the device: which settings to use, with the reason taken from the data (name the numbers),
           and how sure that is (how many answers it rests on).
        2. Where the data is too thin or not comparable: which tests to run next (one change at a time, same prompt).
        3. Anything that looks wrong in the data or in the app.
        4. Engine optimisations, if the numbers point to one (e.g. flash stall, cache churn, cores waiting, NPU use).
           The source is public: https://github.com/int3ks/BigMoeOnEdge-Snap — the engine is in
           app/src/main/cpp/core (BigMoeOnEdge 0.28 baseline with local patches and ported pieces), the vendored
           llama.cpp in app/src/main/cpp/third_party/llama.cpp, the JNI bridge in app/src/main/cpp/native-bridge.cpp,
           the app in app/src/main/java/com/bigmoe/onedge; CHANGELOG.md ("Snap fork") is the history. Name the file
           and what to change, and how a measurement in this log would show it worked.
        Only suggest settings that exist (list below). If you give values, add them as a JSON block marked
        `$BLOCK`, e.g. ```$BLOCK {"cacheMb": 2500} ``` — the app can apply such a block.

        Settings that can be changed:
        @@KNOBS@@

        The summary below is computed by the app (medians, grouping, crash matching); the CSV after it has every entry
        of the exported period. Columns with the same value in every row are listed once above the table.
    """.trimIndent().replace("@@BACKGROUND@@", BACKGROUND).replace("@@KNOBS@@", knobList(settings))

    /** The prompt for a local model: the computed report, the settings and a strict answer format. */
    fun localPrompt(report: String, settings: EngineSettings, about: String?): String = """
        You help tune the settings of the app you are running in. Read the background, the settings and the measured
        summary, then recommend settings ${if (about != null) "for the model $about" else "for this device"}.

        @@BACKGROUND@@

        Settings you may change (key: allowed values — current value):
        @@KNOBS@@

        Measured summary (computed by the app; trust these numbers, do not recompute them):
        @@REPORT@@

        Answer in this form:
        1. At most 10 short lines: what the numbers show and why you recommend each change. Name the numbers.
        2. Then exactly one block with only the settings to change:
        ```$BLOCK
        {"key": value}
        ```
        If the data is too thin or not comparable, say so, propose which settings to test next instead and leave the
        block empty: {}. Never invent settings or numbers.
    """.trimIndent().replace("@@BACKGROUND@@", BACKGROUND).replace("@@KNOBS@@", knobList(settings))
        .replace("@@REPORT@@", report)
}
