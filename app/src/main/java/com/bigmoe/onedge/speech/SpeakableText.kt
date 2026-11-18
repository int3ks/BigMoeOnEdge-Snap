package com.bigmoe.onedge.speech

/**
 * Turns a chat answer into text for the speech engine: code blocks, links, Markdown marks and table pipes are dropped,
 * lines become sentences, and the result is cut into sentence-aligned chunks (an engine takes at most
 * TextToSpeech.getMaxSpeechInputLength() characters per utterance, and short chunks start speaking sooner).
 */
internal object SpeakableText {
    private val think = Regex("""<think>[\s\S]*?(</think>|$)""", RegexOption.IGNORE_CASE)
    private val fence = Regex("""```[\s\S]*?(```|$)""")
    private val displayMath = Regex("""\$\$[\s\S]*?\$\$""")
    private val image = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val url = Regex("""https?://\S+""")
    private val htmlTag = Regex("""</?[a-zA-Z][^>]*>""")
    private val rule = Regex("""^\s*([-*_]\s*){3,}$""")
    private val tableSeparator = Regex("""^\s*\|?\s*:?-{2,}[-:|\s]*$""")
    private val heading = Regex("""^\s{0,3}#{1,6}\s*""")
    private val quote = Regex("""^\s*>\s?""")
    private val bullet = Regex("""^\s*([-*+•]|\d+[.)])\s+""")
    private val marks = Regex("""[*_~`]+""")
    private val pipes = Regex("""\s*\|\s*""")
    private val spaces = Regex("""[ \t]+""")
    private val sentenceEnd = Regex("""(?<=[.!?…])\s+""")

    fun clean(raw: String): String {
        var t = think.replace(raw, "\n")
        t = fence.replace(t, "\n")
        t = displayMath.replace(t, "\n")
        t = image.replace(t, " ")
        t = link.replace(t, "\$1")
        t = url.replace(t, " ")
        t = htmlTag.replace(t, " ")
        val lines = t.lines().mapNotNull { line ->
            if (rule.matches(line) || tableSeparator.matches(line)) return@mapNotNull null
            var l = heading.replace(line, "")
            l = quote.replace(l, "")
            l = bullet.replace(l, "")
            l = pipes.replace(l, ", ")
            l = marks.replace(l, "")
            l = spaces.replace(l, " ").trim().trim(',').trim()
            if (l.isEmpty()) return@mapNotNull null
            // A line without its own end mark (heading, list item, table row) still ends a thought: let it pause.
            if (l.last() in ".!?…:;,") l else "$l."
        }
        return lines.joinToString(" ")
    }

    fun chunks(raw: String, max: Int = 400): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) out += current.toString().trim()
            current.setLength(0)
        }
        for (sentence in sentenceEnd.split(clean(raw))) {
            var s = sentence.trim()
            if (s.isEmpty()) continue
            while (s.length > max) {
                // One very long sentence: cut at the last space before the limit.
                flush()
                val cut = s.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
                out += s.substring(0, cut).trim()
                s = s.substring(cut).trim()
            }
            if (current.isNotEmpty() && current.length + 1 + s.length > max) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(s)
        }
        flush()
        return out
    }

    private val german = setOf(
        "der", "die", "das", "und", "ist", "nicht", "ich", "ein", "eine", "zu", "mit", "auf", "für", "sie", "es",
        "du", "wir", "sich", "auch", "den", "dem", "wie", "oder", "aber", "wird", "sind", "noch", "kann", "dass"
    )
    private val english = setOf(
        "the", "and", "is", "are", "of", "to", "you", "it", "that", "this", "with", "for", "not", "be", "have",
        "was", "on", "can", "will", "your", "they", "what", "which", "there", "would"
    )
    private val word = Regex("""\p{L}+""")

    /** "de" or "en" when the text clearly is one of them, otherwise null (then the device language is used). */
    fun guessLanguage(raw: String): String? {
        var de = 0
        var en = 0
        word.findAll(raw.take(3000)).forEach {
            val w = it.value.lowercase()
            if (w in german) de++
            if (w in english) en++
        }
        return when {
            de >= 3 && de >= en * 2 -> "de"
            en >= 3 && en >= de * 2 -> "en"
            else -> null
        }
    }
}
