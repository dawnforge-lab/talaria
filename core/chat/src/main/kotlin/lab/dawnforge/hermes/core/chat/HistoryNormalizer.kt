package lab.dawnforge.hermes.core.chat

import kotlinx.serialization.json.JsonObject
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.model.NoticeKind
import lab.dawnforge.hermes.core.model.ToolStatus

/**
 * Hermes' stored history (`GET /api/sessions/{id}/messages`, oldest first) as transcript rows.
 *
 * An assistant row reads as its reasoning, its text, then one card per tool call; each call's
 * result is a later `tool` row with the call's id, folded into that card. Rows Hermes marks
 * `hidden` are model scaffolding and never shown; its other `display_kind`s are timeline notes.
 *
 * [running] says the session's turn is still going: a call with no result yet is then running,
 * where in a finished chat it was stopped.
 */
fun normalizeHistory(rows: List<JsonObject>, running: Boolean = false): List<ChatItem> {
    val items = ArrayList<ChatItem>(rows.size)
    // Calls waiting for their result, by call id; and their place in [items].
    val openCalls = HashMap<String, Int>()
    var lastUserIndex = -1

    rows.forEachIndexed { index, row ->
        val kind = row.string("display_kind")?.trim()?.takeIf(String::isNotEmpty)
        if (kind == "hidden") return@forEachIndexed
        val key = "row-" + (row.scalar("id") ?: row.scalar("row_id") ?: "i$index")
        val timestamp = row.epochMs("timestamp")
        val text = (row.string("display_content") ?: row["content"].contentText())

        when (row.string("role")) {
            "user" -> {
                val notice = timelineNotice(kind, key, row, text)
                if (notice != null) {
                    items += notice
                } else {
                    val parsed = parseUserText(text)
                    if (parsed.text.isNotEmpty() || parsed.attachments.isNotEmpty()) {
                        lastUserIndex = items.size
                        items += ChatItem.User(
                            key,
                            parsed.text,
                            parsed.attachments,
                            timestamp,
                            isSteer = kind == "steer",
                            rowId = row.rowId(),
                            reactions = row.reactions(),
                        )
                    }
                }
            }

            "assistant" -> {
                if (kind == "failed_turn") {
                    val error = row.displayMetadata()?.string("error")?.takeIf(String::isNotBlank)
                    items += ChatItem.Notice(key, NoticeKind.Failed, error ?: text.takeIf(String::isNotBlank))
                    return@forEachIndexed
                }
                val reply = text.withoutThinkBlocks()
                val reasoning = (
                    row.string("display_reasoning") ?: row.string("reasoning")
                        ?: row.string("reasoning_content")
                    )
                    ?.trim()
                if (!reasoning.isNullOrEmpty() && reasoning != reply.trim()) {
                    items += ChatItem.Reasoning("$key-reasoning", reasoning)
                }
                if (reply.isNotBlank()) {
                    items += ChatItem.Assistant(
                        key,
                        reply.trim(),
                        timestamp,
                        rowId = row.rowId(),
                        reactions = row.reactions(),
                    )
                }
                row.array("tool_calls")?.forEachIndexed { callIndex, element ->
                    val call = element as? JsonObject ?: return@forEachIndexed
                    val function = call.obj("function")
                    val callId = call.string("id")?.trim()?.takeIf(String::isNotEmpty)
                    val name = function?.string("name") ?: call.string("name") ?: return@forEachIndexed
                    val args = toolArguments(function?.get("arguments") ?: call["arguments"])
                    if (callId != null) openCalls[callId] = items.size
                    items += ChatItem.Tool(
                        key = callId?.let(::toolKey) ?: "$key-tool-$callIndex",
                        callId = callId,
                        name = name,
                        summary = toolSummary(args),
                        input = toolInputText(args),
                        output = null,
                        status = ToolStatus.Running,
                    )
                }
                if (row.displayMetadata()?.flag("interrupted") == true) {
                    items += ChatItem.Notice("$key-stopped", NoticeKind.Stopped)
                }
            }

            "tool" -> {
                val callId = row.string("tool_call_id")?.trim()?.takeIf(String::isNotEmpty)
                val name = row.string("tool_name") ?: row.string("name")
                val result = row["content"]
                val status = if (toolFailed(result)) ToolStatus.Failed else ToolStatus.Complete
                // An id is authoritative; only a row without one falls back to the last open call of its name.
                val at = if (callId != null) {
                    openCalls.remove(callId)
                } else {
                    items.indexOfLast { it is ChatItem.Tool && it.status == ToolStatus.Running && it.name == name }
                        .takeIf { it >= 0 }
                }
                if (at != null) {
                    val call = items[at] as ChatItem.Tool
                    items[at] =
                        call.copy(output = toolOutputText(result), status = status, cards = cardsOf(call, result, name))
                } else if (name != null) {
                    items += ChatItem.Tool(
                        key = callId?.let(::toolKey) ?: key,
                        callId = callId,
                        name = name,
                        summary = null,
                        input = null,
                        output = toolOutputText(result),
                        status = status,
                    )
                }
            }

            // System rows are prompts written for the model.
            else -> Unit
        }
    }

    // Calls that never got a result: still running in the turn that is going, else stopped.
    for (i in items.indices) {
        val item = items[i]
        if (item is ChatItem.Tool && item.status == ToolStatus.Running && !(running && i > lastUserIndex)) {
            items[i] = item.copy(status = ToolStatus.Stopped)
        }
    }
    return items
}

internal fun toolKey(callId: String) = "tool-$callId"

private fun timelineNotice(kind: String?, key: String, row: JsonObject, text: String): ChatItem.Notice? {
    val metadata = row.displayMetadata()
    val displayText = metadata?.string("display_text")?.trim()?.takeIf(String::isNotEmpty)
    val projected = row.string("display_content")?.trim()?.takeIf(String::isNotEmpty)
    return when (kind) {
        "model_switch" -> ChatItem.Notice(key, NoticeKind.ModelSwitched, projected)

        "personality_switch" -> ChatItem.Notice(key, NoticeKind.PersonalitySwitched, projected)

        "auto_continue" -> ChatItem.Notice(key, NoticeKind.Resumed, projected)

        "async_delegation_complete" ->
            ChatItem.Notice(key, NoticeKind.BackgroundAgentsFinished, projected ?: displayText)

        "process_complete" -> ChatItem.Notice(key, NoticeKind.BackgroundProcessFinished, projected ?: displayText)

        "internal_notification" -> text.trim().takeIf(String::isNotEmpty)?.let {
            ChatItem.Notice(key, NoticeKind.Info, projected ?: it.removeSystemMarker())
        }

        else -> null
    }
}

/** `[System: …]` → `…`. */
private fun String.removeSystemMarker(): String {
    if (!startsWith("[System:", ignoreCase = true)) return this
    return removeRange(0, "[System:".length).trim().removeSuffix("]").trim()
}
