package lab.dawnforge.hermes.core.chat

/**
 * Inline Markdown as the text it reads as, to rework in the composer: a link keeps its words, and
 * emphasis, strikethrough and code marks go. Line breaks and everything else stay as written.
 */
object PlainText {
    private val LINK = Regex("""!?\[([^\]]*)]\([^)\s]*(?:\s+"[^"]*")?\)""")
    private val STRONG = Regex("""(\*\*|__)(\S(?:.*?\S)?)\1""")
    private val STRIKE = Regex("""~~(\S(?:.*?\S)?)~~""")

    // A mark inside a word (snake_case, 2*3) isn't emphasis.
    private val EMPHASIS = Regex("""(?<![\w*])\*(\S(?:[^*]*?\S)?)\*(?![\w*])|(?<!\w)_(\S(?:[^_]*?\S)?)_(?!\w)""")
    private val CODE = Regex("""`([^`]+)`""")

    fun of(markdown: String): String = markdown
        .replace(LINK, "$1")
        .replace(STRONG, "$2")
        .replace(STRIKE, "$1")
        .replace(EMPHASIS) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        .replace(CODE, "$1")
}
