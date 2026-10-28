package lab.dawnforge.hermes.core.voice

/**
 * The spoken commands a voice conversation answers itself, as on iOS: a Stop phrase interrupts
 * Hermes, an End phrase closes the conversation. A command fires only when the whole utterance is
 * one (a few in a row, or with a courtesy around it: "okay, goodbye"); "goodbye to the old server"
 * never does.
 */
object SpokenCommands {
    val STOP = listOf("stop", "stop talking", "be quiet")
    val END = listOf("goodbye", "bye", "end conversation", "that's all")

    private val LEADING = listOf(
        listOf("all", "right"),
        listOf("thank", "you"),
        listOf("ok"),
        listOf("okay"),
        listOf("alright"),
        listOf("thanks"),
        listOf("cool"),
        listOf("great"),
        listOf("well"),
        listOf("so"),
        listOf("please"),
    )
    private val TRAILING = listOf(
        listOf("for", "now"),
        listOf("thank", "you"),
        listOf("thanks"),
        listOf("now"),
        listOf("then"),
        listOf("please"),
    )
    private const val MAX_REPEATS = 3

    /** Lowercase, the typographic apostrophe folded, outer spaces and punctuation trimmed. */
    fun canonical(text: String): String = text.lowercase().replace('’', '\'')
        .trim { it.isWhitespace() || (!it.isLetterOrDigit() && it != '\'') }

    fun matches(utterance: String, phrases: List<String>): Boolean {
        val said = canonical(utterance)
        if (said.isEmpty()) return false
        if (phrases.any { canonical(it) == said }) return true
        val spoken = trimmed(words(utterance)).joinToString("")
        if (spoken.isEmpty()) return false
        val keys = phrases.map { trimmed(words(it)).joinToString("") }.filter(String::isNotEmpty).toSet()
        return sequence(spoken, keys, MAX_REPEATS)
    }

    private fun words(text: String): MutableList<String> = canonical(text)
        .split(Regex("""[^\p{L}\p{N}']+"""))
        .filter(String::isNotEmpty)
        .toMutableList()

    private fun trimmed(words: MutableList<String>): List<String> {
        var changed = true
        while (changed && words.isNotEmpty()) {
            changed = false
            LEADING.firstOrNull { words.size > it.size && words.subList(0, it.size) == it }?.let {
                repeat(it.size) { words.removeAt(0) }
                changed = true
            }
            TRAILING.firstOrNull {
                words.size > it.size && words.subList(words.size - it.size, words.size) == it
            }?.let {
                repeat(it.size) { words.removeAt(words.size - 1) }
                changed = true
            }
        }
        return words
    }

    private fun sequence(text: String, keys: Set<String>, limit: Int): Boolean {
        if (limit == 0) return false
        return keys.any { key ->
            text.startsWith(key) && (text.length == key.length || sequence(text.substring(key.length), keys, limit - 1))
        }
    }
}

/**
 * A spoken answer to the one thing waiting on the user (a confirmation, an approval), in English.
 * Only a plain yes or no counts: "yes", "go ahead", "no thanks", "never mind". A question, or a yes
 * with a refusal in it ("yes, but don't send it"), never approves; anything else is just speech.
 */
object SpokenAnswer {
    enum class Answer { Yes, No, Other }

    private val YES = listOf(
        listOf("yes"), listOf("yeah"), listOf("yep"), listOf("yup"), listOf("sure"), listOf("ok"),
        listOf("okay"), listOf("alright"), listOf("absolutely"), listOf("definitely"), listOf("correct"),
        listOf("confirm"), listOf("confirmed"), listOf("approve"), listOf("approved"), listOf("go", "ahead"),
        listOf("do", "it"), listOf("please", "do"), listOf("of", "course"), listOf("sounds", "good"),
        listOf("approve", "it"), listOf("allow", "it"), listOf("that's", "right"), listOf("go", "for", "it"),
    )
    private val NO = listOf(
        listOf("no"), listOf("nope"), listOf("nah"), listOf("cancel"), listOf("cancel", "it"),
        listOf("don't"), listOf("do", "not"), listOf("never", "mind"), listOf("not", "now"), listOf("deny"),
        listOf("decline"), listOf("forget", "it"), listOf("no", "way"),
    )
    private val COURTESIES = setOf("please", "thanks", "thank", "you", "it", "that", "then", "now", "oh", "well", "so")
    private val REFUSALS = setOf("no", "not", "don't", "dont", "never", "cancel", "stop", "wait", "but")

    fun of(words: String): Answer {
        if ('?' in words) return Answer.Other
        val said = SpokenCommands.canonical(words).split(Regex("""[^\p{L}\p{N}']+""")).filter(String::isNotEmpty)
        if (said.isEmpty()) return Answer.Other
        NO.firstOrNull { said.take(it.size) == it }?.let { lead ->
            return if (said.drop(lead.size).all { it in COURTESIES }) Answer.No else Answer.Other
        }
        YES.sortedByDescending { it.size }.firstOrNull { said.take(it.size) == it }?.let { lead ->
            val rest = said.drop(lead.size)
            // Yes words in a row ("yes, sure, go ahead") and courtesies; never a refusal.
            val yesWords = YES.flatten().toSet()
            if (rest.any { it in REFUSALS }) return Answer.Other
            return if (rest.all { it in COURTESIES || it in yesWords }) Answer.Yes else Answer.Other
        }
        return Answer.Other
    }

    /** A confirmation's own phrases ("send it") count as a yes, said on their own or after a yes. */
    fun confirms(words: String, phrases: List<String>): Boolean {
        if (of(words) == Answer.Yes) return true
        if ('?' in words) return false
        val said = SpokenCommands.canonical(words)
        if (said.split(Regex("""[^\p{L}\p{N}']+""")).any { it in REFUSALS }) return false
        val stripped = said.removePrefix("yes").removePrefix("yeah").removePrefix("okay").removePrefix("ok")
            .trim { !it.isLetterOrDigit() }
            .removeSuffix("please").trim { !it.isLetterOrDigit() }
        return phrases.any { SpokenCommands.canonical(it) == stripped }
    }
}
