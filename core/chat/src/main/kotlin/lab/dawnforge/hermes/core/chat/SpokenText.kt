package lab.dawnforge.hermes.core.chat

/**
 * What a persona writes but nobody should hear, dropped before speech: emoji and narrated actions
 * in single asterisks or underscores ("*sets down the gavel*"). Hermes' own speech cleanup strips
 * the markers but keeps the words, so the action would still be read aloud. A port of the iOS
 * app's filter.
 *
 * Streaming: reply deltas split anywhere, so an opened marker holds text back until it closes, the
 * line ends, or the hold grows past what an action plausibly is (then it is released unchanged).
 * The chat's own transcript is never filtered.
 */
class SpokenText {
    private val pending = StringBuilder()

    /** The last character released, so a marker at the start of the next delta knows whether it sits inside a word. */
    private var before: Char? = null

    fun reset() {
        pending.clear()
        before = null
    }

    /** Speakable text that can be released now. */
    fun feed(text: String): String {
        pending.append(text)
        return drain(final = false)
    }

    /** Everything still held, at the end of the reply. */
    fun finish(): String {
        val text = drain(final = true)
        reset()
        return text
    }

    private fun drain(final: Boolean): String {
        val output = StringBuilder()
        var remaining = pending.toString()
        while (true) {
            val open = firstActionMarker(remaining, output.lastOrNull() ?: before) ?: break
            val marker = remaining[open]
            output.append(remaining, 0, open)
            val afterOpen = remaining.substring(open + 1)
            val close = closingMarker(marker, afterOpen, final)
            if (close != null) {
                if (isAction(afterOpen.substring(0, close))) {
                    output.append(' ')
                } else {
                    output.append(remaining, open, open + close + 2)
                }
                remaining = afterOpen.substring(close + 1)
                continue
            }
            val held = remaining.substring(open)
            if (final || held.length > MAX_HELD || held.any { it == '\n' }) {
                // Never closed: not an action. The marker is text; scanning goes on after it.
                output.append(marker)
                remaining = afterOpen
                continue
            }
            pending.setLength(0)
            pending.append(held)
            return release(output)
        }
        val last = remaining.lastOrNull()
        if (!final && (last == '*' || last == '_')) {
            // A trailing run of markers may grow into "**" or open an action with the next delta:
            // it is held with the character before it.
            val run = remaining.takeLastWhile { it == last }.length
            val keep = minOf(run + 1, remaining.length)
            output.append(remaining, 0, remaining.length - keep)
            pending.setLength(0)
            pending.append(remaining.takeLast(keep))
        } else {
            output.append(remaining)
            pending.setLength(0)
        }
        return release(output)
    }

    private fun release(output: StringBuilder): String {
        output.lastOrNull()?.let { before = it }
        return speakable(output.toString())
    }

    companion object {
        /** An emphasised word or two ("*really*") is speech; an action is longer. */
        const val MIN_ACTION_WORDS = 3
        const val MAX_HELD = 400

        fun filter(text: String): String = SpokenText().let { it.feed(text) + it.finish() }

        /** A single `*` or `_` that can open an action: not doubled (bold), and `_` only at a word's start. */
        private fun firstActionMarker(text: String, before: Char?): Int? {
            var index = 0
            while (index < text.length) {
                val character = text[index]
                if (character != '*' && character != '_') {
                    index++
                    continue
                }
                val previous = if (index > 0) text[index - 1] else before
                val doubled = (index + 1 < text.length && text[index + 1] == character) || previous == character
                if (doubled) {
                    while (index < text.length && text[index] == character) index++
                    continue
                }
                val wordStart = previous?.let { !it.isLetterOrDigit() } ?: true
                if (character == '*' || wordStart) return index
                index++
            }
            return null
        }

        private fun closingMarker(marker: Char, text: String, final: Boolean): Int? {
            var index = 0
            val limit = minOf(text.length, MAX_HELD - 1)
            while (index < limit) {
                val character = text[index]
                if (character == '\n') return null
                if (character != marker) {
                    index++
                    continue
                }
                val next = index + 1
                if (next < text.length && text[next] == marker) {
                    index = next + 1
                    continue
                }
                if (marker == '_') {
                    // Whether "_" ends a word is only known once the next character arrives.
                    if (next >= text.length && !final) return null
                    if (next < text.length && text[next].isLetterOrDigit()) {
                        index = next
                        continue
                    }
                }
                return index
            }
            return null
        }

        private fun isAction(inner: String): Boolean =
            inner.split(Regex("""\s+""")).count(String::isNotEmpty) >= MIN_ACTION_WORDS

        /** Removes emoji, and collapses the double spaces they and dropped actions leave. */
        fun speakable(text: String): String {
            if (text.isEmpty()) return text
            val kept = StringBuilder(text.length)
            var index = 0
            while (index < text.length) {
                val point = text.codePointAt(index)
                if (!isEmoji(point)) kept.appendCodePoint(point)
                index += Character.charCount(point)
            }
            var result = kept.toString()
            while ("  " in result) result = result.replace("  ", " ")
            return result
        }

        /** Emoji and their joiners, selectors, skin tones and flag tags; pictographs from arrows on. */
        private fun isEmoji(point: Int): Boolean = when (point) {
            ZERO_WIDTH_JOINER, TEXT_SELECTOR, EMOJI_SELECTOR, KEYCAP -> true
            in SKIN_TONES, in TAGS, in PICTOGRAPHS, in SYMBOLS, in DINGBATS, in ARROWS, in MISC_TECHNICAL -> true
            in ARROWS_EXTRA, in REGIONAL_FLAGS -> true
            else -> false
        }

        private const val ZERO_WIDTH_JOINER = 0x200D
        private const val TEXT_SELECTOR = 0xFE0E
        private const val EMOJI_SELECTOR = 0xFE0F
        private const val KEYCAP = 0x20E3
        private val SKIN_TONES = 0x1F3FB..0x1F3FF
        private val TAGS = 0xE0020..0xE007F
        private val PICTOGRAPHS = 0x1F000..0x1FAFF
        private val SYMBOLS = 0x2600..0x26FF
        private val DINGBATS = 0x2700..0x27BF
        private val ARROWS = 0x2190..0x21FF
        private val MISC_TECHNICAL = 0x2300..0x23FF
        private val ARROWS_EXTRA = 0x2B00..0x2BFF
        private val REGIONAL_FLAGS = 0x1F1E6..0x1F1FF
    }
}
