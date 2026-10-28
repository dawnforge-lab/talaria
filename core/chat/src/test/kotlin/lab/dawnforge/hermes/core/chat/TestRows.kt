package lab.dawnforge.hermes.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import lab.dawnforge.hermes.core.gateway.GatewayEvent
import lab.dawnforge.hermes.core.model.ChatItem

internal fun rows(json: String): List<JsonObject> = (chatJson.parseToJsonElement(json) as JsonArray).map {
    it.jsonObject
}

internal fun event(type: String, payload: String? = null, seq: Long? = null) = GatewayEvent(
    type = type,
    sessionId = "rt-1",
    payload = payload?.let { chatJson.parseToJsonElement(it).jsonObject },
    seq = seq,
)

/** Event frames' params (`{type, session_id, payload, seq}`), one per line, as a capture holds them. */
internal fun events(lines: String): List<GatewayEvent> = lines.lines().filter(String::isNotBlank).map { line ->
    val params = chatJson.parseToJsonElement(line).jsonObject
    GatewayEvent(
        type = (params["type"] as JsonPrimitive).content,
        sessionId = (params["session_id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty),
        payload = params["payload"] as? JsonObject,
        seq = (params["seq"] as? JsonPrimitive)?.longOrNull,
    )
}

internal fun List<GatewayEvent>.fold(start: ChatState = ChatState()): ChatState = fold(start) { state, e ->
    state.reduce(e)
}

/**
 * What a row shows, without what only one source has (keys, timestamps, durations): a streamed
 * turn and its stored copy must agree on this.
 */
internal fun ChatItem.shown(): String = when (this) {
    is ChatItem.User -> "user: $text" + if (attachments.isEmpty()) "" else " " + attachments.map { it.path }
    is ChatItem.Assistant -> "assistant: $text"
    is ChatItem.Reasoning -> "reasoning: $text"
    is ChatItem.Tool -> "tool $name [$status] ${summary.orEmpty()} -> ${output.orEmpty()}"
    is ChatItem.Notice -> "notice $kind ${text.orEmpty()}".trimEnd()
    is ChatItem.Note -> "note $kind ${title.orEmpty()}: $text"
}

internal fun List<ChatItem>.shown() = map { it.shown() }
