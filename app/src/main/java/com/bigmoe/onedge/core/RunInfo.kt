package com.bigmoe.onedge.core

import com.bigmoe.onedge.data.local.datastore.EngineSettings
import org.json.JSONObject
import java.io.File

/**
 * What an answer was made with: the model, where it was read from and the settings that matter for comparing
 * answers (Regenerate variants). Stored as JSON on the assistant message (`run_info`); fields can be missing in
 * older records.
 */
data class RunInfo(
    val model: String,
    val path: String = "",
    val npu: Boolean? = null,
    val cacheMode: String? = null,
    val cacheMb: Int? = null,
    val threads: Int? = null,
    val ctx: Int? = null,
    val spec: String? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val minP: Double? = null,
    val repeatPenalty: Double? = null,
    /** Decoding preset set on the model's card (its label); null = Settings → Decoding. */
    val preset: String? = null,
    val systemPrompt: String? = null,
    /** Lowest free RAM seen while the turn ran (sampled every 2 s by MemoryWatch); null when no sample. */
    val minFreeMb: Long? = null,
    /** Most of the app seen in swap (zram) while the turn ran. */
    val maxSwapMb: Long? = null,
    /** The engine's major page faults per generated token (expert cache or weights read back from swap/flash). */
    val majfltPerToken: Double? = null
) {
    fun toJson(): String = JSONObject().apply {
        put("model", model); put("path", path)
        npu?.let { put("npu", it) }
        cacheMode?.let { put("cacheMode", it) }
        cacheMb?.let { put("cacheMb", it) }
        threads?.let { put("threads", it) }
        ctx?.let { put("ctx", it) }
        spec?.let { put("spec", it) }
        temperature?.let { put("temp", it) }
        topP?.let { put("topP", it) }
        topK?.let { put("topK", it) }
        minP?.let { put("minP", it) }
        repeatPenalty?.let { put("repeat", it) }
        preset?.let { put("preset", it) }
        systemPrompt?.let { put("systemPrompt", it) }
        minFreeMb?.let { put("minFreeMb", it) }
        maxSwapMb?.let { put("maxSwapMb", it) }
        majfltPerToken?.let { put("majfltPerToken", it) }
    }.toString()

    /** "/storage/.../Models" -> "Models", for the details line. */
    val folder: String get() = File(path).parentFile?.name.orEmpty()

    companion object {
        /** [base] with the values of the model's decoding [preset] (they replaced the global ones for the turn). */
        fun withPreset(base: RunInfo, preset: SamplingPreset?): RunInfo = if (preset == null) base else base.copy(
            temperature = preset.temperature.toDouble(), topP = preset.topP.toDouble(), topK = preset.topK,
            minP = preset.minP.toDouble(), repeatPenalty = preset.repeatPenalty.toDouble(), preset = preset.label
        )

        fun from(info: ModelInfo, settings: EngineSettings): RunInfo {
            val c = info.config
            return RunInfo(
                model = info.name,
                path = info.path,
                npu = info.npuPrefillReady,
                cacheMode = c.cacheMode.name.lowercase(),
                cacheMb = c.cacheMb,
                threads = c.nThreads,
                ctx = info.nCtx,
                spec = c.spec.name.lowercase(),
                temperature = settings.temperature.toDouble(),
                topP = settings.topP.toDouble(),
                topK = settings.topK,
                minP = settings.minP.toDouble(),
                repeatPenalty = settings.repeatPenalty.toDouble(),
                systemPrompt = settings.systemPrompts.firstOrNull { it.id == settings.systemPromptId }?.name
            )
        }

        fun parse(json: String?): RunInfo? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(json)
                fun s(k: String) = if (o.has(k)) o.getString(k) else null
                fun i(k: String) = if (o.has(k)) o.getInt(k) else null
                fun d(k: String) = if (o.has(k)) o.getDouble(k) else null
                RunInfo(
                    model = o.getString("model"),
                    path = s("path").orEmpty(),
                    npu = if (o.has("npu")) o.getBoolean("npu") else null,
                    cacheMode = s("cacheMode"),
                    cacheMb = i("cacheMb"),
                    threads = i("threads"),
                    ctx = i("ctx"),
                    spec = s("spec"),
                    temperature = d("temp"),
                    topP = d("topP"),
                    topK = i("topK"),
                    minP = d("minP"),
                    repeatPenalty = d("repeat"),
                    preset = s("preset"),
                    systemPrompt = s("systemPrompt"),
                    minFreeMb = if (o.has("minFreeMb")) o.getLong("minFreeMb") else null,
                    maxSwapMb = if (o.has("maxSwapMb")) o.getLong("maxSwapMb") else null,
                    majfltPerToken = if (o.has("majfltPerToken")) o.getDouble("majfltPerToken") else null
                )
            }.getOrNull()
        }
    }
}
