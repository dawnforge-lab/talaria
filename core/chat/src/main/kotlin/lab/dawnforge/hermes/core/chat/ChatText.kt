package lab.dawnforge.hermes.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import lab.dawnforge.hermes.core.model.ChatAttachment
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.model.RichCard

// Text rules shared by the stored history and the live stream, so a tool card or a message reads
// the same whichever way it arrived.

/** Tool output beyond this is cut: a card is not the place to read a 64 KiB result. */
internal const val TOOL_TEXT_LIMIT = 20_000

private const val SUMMARY_LIMIT = 120

/** The argument that says what a call does, for tools whose name alone does not. */
private val SUMMARY_KEYS = listOf("command", "path", "file_path", "url", "query", "pattern", "goal", "name")

/** Result fields that hold a tool's main text, in the order they are looked for. */
private val OUTPUT_KEYS = listOf("output", "content", "stdout", "text", "result")

/** Arguments as an object: Hermes stores them as JSON text, and sends them parsed. */
internal fun toolArguments(args: JsonElement?): JsonObject? = when (args) {
    is JsonObject -> args
    is JsonPrimitive -> args.contentOrNull?.parseJsonOrNull() as? JsonObject
    else -> null
}

/** One line for the collapsed card: the command, the path, the query… */
internal fun toolSummary(args: JsonObject?): String? {
    args ?: return null
    val value = SUMMARY_KEYS.firstNotNullOfOrNull { args.string(it)?.takeIf(String::isNotBlank) }
        ?: args.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
        ?: return null
    val line = value.trim().lineSequence().first()
    return if (line.length > SUMMARY_LIMIT) line.take(SUMMARY_LIMIT) + "…" else line
}

/** The arguments, readable; null when there are none. */
internal fun toolInputText(args: JsonObject?): String? =
    args?.takeIf { it.isNotEmpty() }?.let { prettyJson.encodeToString(JsonObject.serializer(), it) }

/**
 * A result, readable: the tool's main text when it has one (a command's output), else the JSON
 * laid out. Hermes stores results as JSON text and sends them parsed; both read the same here.
 */
internal fun toolOutputText(result: JsonElement?): String? {
    val text = when (result) {
        null, JsonNull -> return null

        is JsonPrimitive -> {
            val raw = result.contentOrNull ?: return null
            val parsed = raw.parseJsonOrNull()
            if (parsed != null) return toolOutputText(parsed)
            raw
        }

        is JsonObject -> {
            val main = OUTPUT_KEYS.firstNotNullOfOrNull { result.string(it)?.takeIf(String::isNotBlank) }
            val error = result.string("error")?.takeIf(String::isNotBlank)
            when {
                main != null && error != null && error != main -> "$main\n$error"
                main != null -> main
                error != null -> error
                else -> prettyJson.encodeToString(JsonElement.serializer(), result)
            }
        }

        is JsonArray -> prettyJson.encodeToString(JsonElement.serializer(), result)
    }
    val clean = text.stripAnsi().trimEnd()
    if (clean.isEmpty()) return null
    return if (clean.length > TOOL_TEXT_LIMIT) clean.take(TOOL_TEXT_LIMIT) + "\n…" else clean
}

/**
 * Whether a result says the call failed. Hermes has no flag for it; like its own progress
 * renderer, read `success: false`, `ok: false`, an `error`, or a non-zero `exit_code`.
 */
internal fun toolFailed(result: JsonElement?): Boolean {
    val obj = when (result) {
        is JsonObject -> result
        is JsonPrimitive -> result.contentOrNull?.parseJsonOrNull() as? JsonObject
        else -> null
    } ?: return false
    if (obj.flag("success") == false || obj.flag("ok") == false) return true
    if (!obj.string("error").isNullOrBlank()) return true
    val exit = (obj["exit_code"] as? JsonPrimitive)?.longOrNull
    return exit != null && exit != 0L
}

private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

internal fun String.stripAnsi(): String = if ('\u001B' in this) replace(ANSI, "") else this

private val THINK_BLOCK = Regex(
    "<(think|thinking|reasoning|thought|REASONING_SCRATCHPAD)>.*?(</\\1>|$)",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

/** Hermes strips reasoning tags from live text; old stored rows may still hold them. */
internal fun String.withoutThinkBlocks(): String = if ('<' in this) replace(THINK_BLOCK, "").trim() else this

/** A user message as typed, and the files attached to it. */
internal data class UserText(val text: String, val attachments: List<ChatAttachment>)

private val CONTEXT_APPENDICES = listOf("\n\n--- Attached Context ---", "\n\n--- Context Warnings ---")

private val PLACEHOLDER_LINES = setOf("[screenshot]", "[image]")

/**
 * A stored user message, split back into what the user typed and what they attached. Hermes
 * keeps an image as an `@image:<path>` line after the caption (and a `[screenshot]` placeholder),
 * and appends the expanded `@file:` references after `--- Attached Context ---`; the reference
 * itself stays in the text where the user wrote it.
 */
internal fun parseUserText(stored: String): UserText {
    // Hermes writes the image lines last, after any expanded context: take them off the end first.
    val lines = stored.lines().toMutableList()
    val trailing = ArrayList<String>()
    while (lines.isNotEmpty()) {
        val last = lines.last().trim()
        if (!(last.startsWith("@image:") || last in PLACEHOLDER_LINES || last.isEmpty())) break
        trailing.add(0, lines.removeAt(lines.lastIndex))
    }
    var text = lines.joinToString("\n")
    for (marker in CONTEXT_APPENDICES) {
        val at = text.indexOf(marker)
        if (at >= 0) text = text.substring(0, at)
    }
    val attachments = mutableListOf<ChatAttachment>()
    val kept = (text.lines() + trailing).filter { line ->
        val trimmed = line.trim()
        when {
            trimmed.startsWith("@image:") -> {
                val path = trimmed.removePrefix("@image:").trim().trim('`', '"', '\'')
                if (path.isNotEmpty()) attachments += ChatAttachment(fileName(path), path, isImage = true)
                false
            }

            trimmed in PLACEHOLDER_LINES -> false

            trimmed.startsWith("[Triggering message id:") -> false

            // A picture from a messaging app or a bot.
            trimmed.startsWith("[Image attached at:") && trimmed.endsWith("]") -> {
                val path = trimmed.removePrefix("[Image attached at:").removeSuffix("]").trim()
                if (path.isNotEmpty()) attachments += ChatAttachment(fileName(path), path, isImage = true)
                false
            }

            else -> true
        }
    }
    // Files the app (or Hermes' desktop app) attached are referenced on the lines before the text.
    val body = kept.toMutableList()
    var fileAt = 0
    while (body.isNotEmpty()) {
        val first = body.first().trim()
        if (first.isEmpty()) {
            body.removeAt(0)
            continue
        }
        if (!first.startsWith("@file:") || (first.contains(' ') && !first.isQuotedReference())) break
        val path = first.removePrefix("@file:").trim().trim('`', '"', '\'')
        // Ahead of the images, as they come first in the message.
        if (path.isNotEmpty()) attachments.add(fileAt++, ChatAttachment(fileName(path), path, isImage = false))
        body.removeAt(0)
    }
    return UserText(body.joinToString("\n").trim(), attachments)
}

/** `@file:"a b.pdf"` or `` @file:`a b.pdf` ``: a reference to a path with spaces in it. */
private fun String.isQuotedReference(): Boolean {
    val value = removePrefix("@file:")
    return value.length > 1 && value.first() in "`\"'" && value.last() == value.first()
}

private fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

/**
 * A `talaria_show` call's cards, from its result, else its arguments ([call]'s input, laid out as
 * JSON). Hermes may run it through its `tool_call` wrapper; the result then names the tool it ran
 * ([resultName]), and the wrapper's arguments list each call.
 */
internal fun cardsOf(call: ChatItem.Tool, result: JsonElement?, resultName: String? = null): List<RichCard> {
    if (toolFailed(result)) return emptyList()
    val args = toolArguments(call.input?.let(::JsonPrimitive))
    if (call.name.equals(RichCards.TOOL, ignoreCase = true)) return RichCards.fromTool(args, result)
    if (!call.name.equals(ToolCalls.WRAPPER, ignoreCase = true)) return emptyList()
    val inner = ToolCalls.calls(args).filter { it.first.equals(RichCards.TOOL, ignoreCase = true) }
    val named = resultName.equals(RichCards.TOOL, ignoreCase = true)
    // The wrapper's result is one call's: its cards, or the arguments of its only talaria_show.
    return if (named || inner.isNotEmpty()) RichCards.fromTool(inner.singleOrNull()?.second, result) else emptyList()
}

/**
 * Hermes' `tool_call` wrapper, which runs tools found with `tool_search`:
 * `{"calls": [{"name", "arguments"}]}`, the list sometimes sent as JSON text.
 */
object ToolCalls {
    const val WRAPPER = "tool_call"

    /** The tools a call runs: itself, or each one its wrapper lists. */
    fun names(name: String?, args: JsonElement?): List<String> = if (name.equals(WRAPPER, ignoreCase = true)) {
        calls(toolArguments(args)).map {
            it.first
        }
    } else {
        listOfNotNull(name)
    }

    internal fun calls(args: JsonObject?): List<Pair<String, JsonObject?>> {
        val list = when (val calls = args?.get("calls")) {
            is JsonArray -> calls
            is JsonPrimitive -> calls.contentOrNull?.parseJsonOrNull() as? JsonArray
            else -> null
        } ?: return emptyList()
        return list.mapNotNull { entry ->
            val call = entry as? JsonObject ?: return@mapNotNull null
            val name = call.string("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            name to toolArguments(call["arguments"])
        }
    }
}
