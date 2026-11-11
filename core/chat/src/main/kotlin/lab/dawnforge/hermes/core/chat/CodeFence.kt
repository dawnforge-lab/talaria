package lab.dawnforge.hermes.core.chat

/**
 * A fenced code block's opening: three or more backticks or tildes. Only a run of the same
 * character, at least as long and with nothing after it, closes it, so a ```` ```` ```` block can
 * hold ```` ``` ```` lines (a reply showing Markdown) and ```` ```kotlin ```` never closes a block.
 */
internal class CodeFence private constructor(private val char: Char, private val length: Int) {
    fun closedBy(line: String): Boolean {
        val trimmed = line.trim()
        val run = trimmed.takeWhile { it == char }
        return run.length >= length && run.length == trimmed.length
    }

    companion object {
        /** The fence [line] opens, if it starts one. */
        fun openedBy(line: String): CodeFence? {
            val run = OPENING.find(line.trimStart())?.value ?: return null
            return CodeFence(run.first(), run.length)
        }

        private val OPENING = Regex("^(`{3,}|~{3,})")
    }
}
