package lab.dawnforge.hermes.core.push.live

import kotlin.math.round
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import lab.dawnforge.hermes.core.gateway.GatewayEvent

/**
 * How a chat's live events move a running turn's notification, as iOS's `LiveTurnProgress`: the
 * phone works out what the relay would push, while the app has the chat's events. Pure, so the
 * mapping is tested without a notification.
 */
object LiveTurnProgress {
    const val THINKING = "thinking"
    const val WRITING = "writing"
    const val TOOL = "tool"
    const val APPROVAL = "approval"
    const val INPUT = "input"
    const val DONE = "done"
    const val FAILED = "failed"
    const val STOPPED = "stopped"

    /** The requests the chat shows that wait on the user (`ChatRequest`'s methods). */
    private val WAITING_REQUESTS = setOf("approval", "clarify", "sudo", "secret")

    /** A turn just accepted, before anything streamed. */
    fun started(activity: String): LiveTurnUpdate = LiveTurnUpdate(activity, false, THINKING, null, 0, null, null)

    /** The next state after [event], or null when the event changes nothing the notification shows. */
    fun applying(event: GatewayEvent, state: LiveTurnUpdate, nowSeconds: Long): LiveTurnUpdate? {
        val payload = event.payload
        val next = when (event.type) {
            "message.start", "reasoning.delta", "thinking.delta" -> state.copy(phase = THINKING, tool = null)

            "message.delta" -> state.copy(phase = WRITING, tool = null)

            "tool.start", "tool_call" -> {
                val name = payload?.text("name").orEmpty()
                if (name.trim().equals("clarify", ignoreCase = true)) {
                    state.copy(phase = INPUT, tool = null)
                } else {
                    state.copy(phase = TOOL, tool = toolCategory(name), tools = state.tools + 1)
                }
            }

            "tool.complete", "tool_result" -> state.copy(phase = THINKING, tool = null)

            "approval.request" -> state.copy(phase = APPROVAL, tool = null)

            "clarify", "clarify.request" -> state.copy(phase = INPUT, tool = null)

            "clarify.expire", "request.cancel" -> if (state.waitsOnUser) state.copy(phase = THINKING) else return null

            "context.update", "session.context" -> payload?.number("context_percent")
                ?.let { state.copy(context = percent(it) ?: return null) } ?: return null

            "session.usage" -> {
                // Hermes' live usage tick: no window is no reading.
                val usage = payload?.get("usage") as? JsonObject ?: return null
                val max = usage.number("context_max")?.takeIf { it > 0 } ?: return null
                val used = usage.number("context_used") ?: 0.0
                state.copy(context = percent(usage.number("context_percent") ?: (used / max * 100)) ?: return null)
            }

            "message.complete" -> return finished(state, DONE, nowSeconds)

            "error" -> return finished(state, FAILED, nowSeconds)

            "message.interrupted", "session.interrupted" -> return finished(state, STOPPED, nowSeconds)

            // An approval or answer still pending outlasts an idle report.
            "session.busy" ->
                return if ((payload?.flag("busy") ?: false) ||
                    state.waitsOnUser
                ) {
                    null
                } else {
                    finished(state, DONE, nowSeconds)
                }

            "session.info" ->
                return if (payload?.flag("running") != false ||
                    state.waitsOnUser
                ) {
                    null
                } else {
                    finished(state, DONE, nowSeconds)
                }

            else -> return null
        }
        return next.takeIf { it != state }
    }

    /**
     * The state once the chat's open requests became [methods]: an approval, or a question
     * (clarify, a password, a secret), waits on the user; none left goes back to thinking.
     */
    fun withRequests(state: LiveTurnUpdate, methods: Collection<String>): LiveTurnUpdate? {
        val waiting = methods.filter { it in WAITING_REQUESTS }
        val next = when {
            "approval" in waiting -> state.copy(phase = APPROVAL, tool = null)
            waiting.isNotEmpty() -> state.copy(phase = INPUT, tool = null)
            state.waitsOnUser -> state.copy(phase = THINKING)
            else -> return null
        }
        return next.takeIf { it != state }
    }

    /** An idle report: within a moment of the turn's start it can predate the turn. */
    fun isIdleSignal(event: GatewayEvent): Boolean = event.type == "session.busy" || event.type == "session.info"

    /** The held events that belong to the new turn: those from its `message.start` on. */
    fun newTurnEvents(events: List<GatewayEvent>): List<GatewayEvent> {
        val start = events.indexOfLast { it.type == "message.start" }
        return if (start < 0) emptyList() else events.subList(start, events.size)
    }

    /** What kind of tool [toolName] is, from the plugin's fixed list: never the tool's own name. */
    fun toolCategory(toolName: String): String {
        val name = toolName.trim().lowercase()
        return EXACT[name] ?: PREFIXES.firstOrNull { name.startsWith(it.first) }?.second ?: "tool"
    }

    private fun finished(state: LiveTurnUpdate, phase: String, nowSeconds: Long) =
        state.copy(phase = phase, tool = null, ended = true, endedAtSeconds = nowSeconds)

    /** A whole percent within 0–100; an unreadable one is none. */
    private fun percent(value: Double): Int? =
        value.takeIf(Double::isFinite)?.let { round(it).coerceIn(0.0, 100.0).toInt() }

    private val LiveTurnUpdate.waitsOnUser get() = phase == APPROVAL || phase == INPUT

    private val EXACT = mapOf(
        "terminal" to "terminal", "process" to "terminal", "execute_code" to "code",
        "read_file" to "files", "write_file" to "files", "patch" to "files", "search_files" to "files",
        "web_search" to "web", "web_extract" to "web", "web_crawl" to "web",
        "delegate_task" to "agents", "memory" to "memory", "session_search" to "memory",
        "vision_analyze" to "images", "image_generate" to "images", "text_to_speech" to "audio",
        "todo" to "planning", "cronjob" to "schedule", "send_message" to "messages",
        "skill_view" to "skills", "skills_list" to "skills", "skill_manage" to "skills",
    )
    private val PREFIXES = listOf(
        "browser_" to "browser",
        "mcp_" to "connector",
        "talaria_" to "phone",
        "ha_" to "home",
        "web_" to "web",
        "skill" to "skills",
    )

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
}
