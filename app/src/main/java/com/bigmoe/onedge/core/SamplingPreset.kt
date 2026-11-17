package com.bigmoe.onedge.core

/**
 * Named decoding (sampling) presets. Settings → Decoding can copy one into the global values; a model card can pick
 * one for that model, which then replaces the global decoding values for every chat turn with that model (the seed
 * stays the global one). Values follow the usual vendor recommendations (Qwen, Gemma) and the RP-finetune cards.
 */
enum class SamplingPreset(
    val label: String,
    val description: String,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val minP: Float,
    val repeatPenalty: Float = 1f,
    val repeatLastN: Int = 64,
    val frequencyPenalty: Float = 0f,
    val presencePenalty: Float = 0f
) {
    CODING(
        "Coding", "Low randomness, no penalties (they break code, names and JSON).",
        temperature = 0.3f, topP = 0.95f, topK = 20, minP = 0.05f
    ),
    AGENT(
        "Agent work", "Focused and reliable for tool use and multi-step tasks. Tool calls themselves are always greedy.",
        temperature = 0.6f, topP = 0.95f, topK = 20, minP = 0f
    ),
    ROLEPLAY(
        "Roleplay", "Lively, varied replies; Min P keeps them coherent, a mild presence penalty against stale phrases.",
        temperature = 0.9f, topP = 1f, topK = 0, minP = 0.05f, repeatLastN = 256, presencePenalty = 0.2f
    ),
    CREATIVE(
        "Creative", "Most surprising word choice for stories and poems; Min P cuts the nonsense tail.",
        temperature = 1.0f, topP = 1f, topK = 0, minP = 0.05f, repeatLastN = 256, presencePenalty = 0.3f
    ),
    LIFELIKE_CHAT(
        "Lifelike chat", "Natural conversation: some variety, stays on topic.",
        temperature = 0.8f, topP = 0.95f, topK = 40, minP = 0.05f, repeatLastN = 128, presencePenalty = 0.1f
    );

    /** [base] with this preset's values; the seed of [base] is kept. */
    fun applyTo(base: SamplingOverride): SamplingOverride = base.copy(
        temperature = temperature, topP = topP, topK = topK, minP = minP,
        repeatPenalty = repeatPenalty, repeatLastN = repeatLastN,
        frequencyPenalty = frequencyPenalty, presencePenalty = presencePenalty
    )

    /** One line with the values, for the model card. */
    fun summary(): String = buildString {
        append("Temp ").append(fmt(temperature))
        append(", Top P ").append(if (topP >= 1f) "off" else fmt(topP))
        append(", Top K ").append(if (topK <= 0) "off" else topK.toString())
        append(", Min P ").append(if (minP <= 0f) "off" else fmt(minP))
        if (repeatPenalty != 1f) append(", Rep ").append(fmt(repeatPenalty))
        if (frequencyPenalty != 0f) append(", Freq ").append(fmt(frequencyPenalty))
        if (presencePenalty != 0f) append(", Pres ").append(fmt(presencePenalty))
        if (repeatPenalty != 1f || frequencyPenalty != 0f || presencePenalty != 0f) append(" (window ").append(repeatLastN).append(")")
    }

    private fun fmt(v: Float) = "%.2f".format(java.util.Locale.US, v)
}
