package lab.dawnforge.hermes.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import lab.dawnforge.hermes.core.model.Reaction

/** The reactions the app offers, as the iOS and desktop apps do. */
val REACTIONS = listOf("👍", "👎", "❤️", "😂", "😮", "😢")

/** A stored row's id: `id` on REST rows, `row_id` on the gateway's. */
internal fun JsonObject.rowId(): Long? = (scalar("row_id") ?: scalar("id"))?.toLongOrNull()

/** The row's reactions, kept in its `display_metadata.reactions`. */
internal fun JsonObject.reactions(): List<Reaction> = parseReactions(displayMetadata()?.get("reactions") as? JsonArray)

/** `[{emoji, author: "user" | "agent", …}]`, as `message.react` and `message.reaction` give them. */
fun parseReactions(list: JsonArray?): List<Reaction> = list.orEmpty().mapNotNull { entry ->
    val reaction = entry as? JsonObject ?: return@mapNotNull null
    val emoji = reaction.string("emoji")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
    Reaction(emoji, byAgent = reaction.string("author") == "agent")
}

/**
 * The user's reactions after picking [emoji]: one per author, so it replaces theirs, and picking
 * the one they have takes it back (Hermes applies the same rule).
 */
fun List<Reaction>.picking(emoji: String): List<Reaction> {
    val mine = firstOrNull { !it.byAgent }
    val others = filter { it.byAgent }
    return if (mine?.emoji == emoji) others else others + Reaction(emoji, byAgent = false)
}

/** How a reply-to quote starts the message it is sent in; the desktop and iOS apps use the same words. */
const val REPLY_MARKER = "[Replying to your earlier message]"

/** [text] sent as a reply to [quoted]: the marker, the quote as Markdown, then the message. */
fun replyEnvelope(quoted: String, text: String): String {
    val quote = quoted.trim().lines().joinToString("\n") { line ->
        val trimmed = line.trimEnd()
        if (trimmed.isEmpty()) ">" else "> $trimmed"
    }
    return "$REPLY_MARKER\n$quote\n\n$text"
}

/** A message sent as a reply: what it quoted, and the user's own words. */
data class ReplyText(val quoted: String?, val text: String) {
    companion object {
        /** Splits a reply envelope back into its quote and text; any other message is all text. */
        fun parse(message: String): ReplyText {
            if (!message.startsWith(REPLY_MARKER)) return ReplyText(null, message)
            val lines = message.removePrefix(REPLY_MARKER).trimStart('\n').lines()
            val quoteLines = lines.takeWhile { it.startsWith(">") }
            val rest = lines.drop(quoteLines.size).joinToString("\n").trim()
            val quoted = quoteLines.joinToString("\n") { it.removePrefix(">").removePrefix(" ") }.trim()
            return ReplyText(quoted.ifEmpty { null }, rest)
        }
    }
}

/**
 * How many messages a branch that ends at the row [rowId] keeps, counted as Hermes counts them for
 * `session.branch`: user and assistant rows with text, hidden ones too, oldest first, up to and
 * including that row. [rows] must be the whole stored history; null when the row is not in it.
 */
fun branchCount(rows: List<JsonObject>, rowId: Long): Int? {
    var count = 0
    for (row in rows) {
        val role = row.string("role")
        if ((role == "user" || role == "assistant") && row["content"].contentText().isNotBlank()) count++
        if (row.rowId() == rowId) return if (role == "user" || role == "assistant") count else null
    }
    return null
}
