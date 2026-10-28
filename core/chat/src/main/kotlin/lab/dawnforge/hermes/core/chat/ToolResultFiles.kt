package lab.dawnforge.hermes.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.model.ToolStatus

/**
 * The files a finished tool call made on the Hermes host, which its opened row draws with the
 * chat's own media views (a voice message plays in place), read from the stored result so a
 * reloaded chat shows the same as a live one. As iOS does (`ToolResultPresentation`).
 *
 * `text_to_speech` answers `{"success": true, "file_path": "…", "file_paths": ["…", …]}`; a long
 * reading comes back in parts, `file_path` being the first. The turn's reply carries the same audio
 * ([replyAttachments]).
 *
 * Null when the tool isn't one that makes files, or the result isn't the shape Hermes writes.
 */
fun toolResultFiles(toolName: String, output: String?): List<String>? {
    if (toolName.trim().lowercase() != TEXT_TO_SPEECH) return null
    val text = output?.trim()?.takeIf { it.startsWith("{") && it.length <= PARSE_LIMIT } ?: return null
    val root = runCatching { chatJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    val parts = (root["file_paths"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.map { hostFile(it) }
    if (parts != null && parts.all { it != null }) return parts.filterNotNull()
    return hostFile(root["file_path"])?.let(::listOf)
}

/**
 * The audio each finished reply comes with, by the reply's key: what the `text_to_speech` calls of
 * its turn made, in order. On its messaging platforms (Telegram…) Hermes attaches that audio to the
 * turn's final reply whatever the reply says (`gateway/run.py` `_collect_auto_append_media_tags`);
 * the chat this app uses doesn't, so the app attaches it the same way.
 *
 * A turn runs from a user's message (a steer sent mid-turn doesn't end it) to the next; its last
 * reply after the audio carries it, once that reply is written. Files the reply already names with
 * `MEDIA:` are drawn there and not attached again.
 */
fun replyAttachments(items: List<ChatItem>): Map<String, List<String>> {
    val attached = LinkedHashMap<String, List<String>>()
    var audio = mutableListOf<String>()
    var carrier: ChatItem.Assistant? = null

    fun endTurn() {
        val reply = carrier
        if (reply != null && !reply.streaming) {
            val named = splitReply(reply.text).mapNotNullTo(HashSet()) { (it as? ReplyPart.Media)?.path }
            audio.filterNot { it in named }.takeIf { it.isNotEmpty() }?.let { attached[reply.key] = it }
        }
        audio = mutableListOf()
        carrier = null
    }

    items.forEach { item ->
        when (item) {
            is ChatItem.User -> if (!item.isSteer) endTurn()

            is ChatItem.Tool -> if (item.status == ToolStatus.Complete) {
                toolResultFiles(item.name, item.output)?.let { files ->
                    files.filterTo(audio) { it !in audio }
                    // Only a reply after the audio carries it.
                    carrier = null
                }
            }

            is ChatItem.Assistant -> if (audio.isNotEmpty()) carrier = item

            else -> Unit
        }
    }
    endTurn()
    return attached
}

/**
 * An absolute path on the Hermes host (POSIX, `~/` or a Windows drive, as `MEDIA:` tags take
 * them); null for anything else (a URL, a relative path, more than one line).
 */
private fun hostFile(value: Any?): String? {
    val path = ((value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null).trim()
    if (path.isEmpty() || '\n' in path || '`' in path) return null
    return path.takeIf { it.startsWith("/") || it.startsWith("~/") || WINDOWS_PATH.containsMatchIn(it) }
}

private const val TEXT_TO_SPEECH = "text_to_speech"

/** Above this size a result is left raw, as iOS does: parsing it would stall scrolling. */
private const val PARSE_LIMIT = 65_536

private val WINDOWS_PATH = Regex("""^[A-Za-z]:[\\/]""")
