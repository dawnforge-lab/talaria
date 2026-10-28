package lab.dawnforge.hermes.core.chat

import kotlinx.serialization.json.JsonObject

/** A haptic the phone plays for a reply, as the iOS app does. */
enum class HapticEffect { ResponseStarted, ToolStarted, ResponseConcluded, Error }

/** What an event means for the reply's haptics. */
sealed interface HapticSignal {
    /** The agent is at work; [playsStart] when its reply text begins. */
    data class Activity(val playsStart: Boolean) : HapticSignal

    data object Tool : HapticSignal

    /** The turn ended: the reply's end is felt shortly after, unless more comes. */
    data object Ended : HapticSignal

    data object Failure : HapticSignal

    /** Stopped: nothing more is felt for this turn. */
    data object Reset : HapticSignal

    companion object {
        /** The signal of a live event of the chat, if it has one (the iOS app's mapping). */
        fun of(type: String, payload: JsonObject?): HapticSignal? = when (type) {
            "message.start", "reasoning.delta" -> Activity(playsStart = false)

            "message.delta" -> if (payload?.string("text").isNullOrEmpty()) null else Activity(playsStart = true)

            // A question the agent asks is felt as the question, not as a tool.
            "tool.start" -> if (payload?.string("name").equals("clarify", ignoreCase = true)) null else Tool

            "message.complete" -> when (payload?.string("status")) {
                "error" -> if (payload.flag("partial") == true) Ended else Failure
                "interrupted" -> Reset
                else -> Ended
            }

            "error" -> Failure

            else -> null
        }
    }
}

/**
 * The reply's haptics: a start when its text begins, a tick per tool (at most every
 * [TOOL_GAP_MS]), and its end, once, or an error. An end is felt only if nothing else arrives
 * within [CONCLUSION_DELAY_MS]: the caller asks [conclude] with the token [ended] returns, once
 * that time has passed.
 */
class ResponseHaptics {
    private var active = false
    private var startPlayed = false
    private var lastToolMs: Long? = null
    private var pendingEnd = 0L

    /** The effects [signal] plays at [nowMs], and the token of an end to [conclude] later, if any. */
    fun on(signal: HapticSignal, nowMs: Long): Pair<List<HapticEffect>, Long?> = when (signal) {
        is HapticSignal.Activity -> activity(signal.playsStart) to null
        HapticSignal.Tool -> tool(nowMs) to null
        HapticSignal.Ended -> emptyList<HapticEffect>() to ended()
        HapticSignal.Failure -> fail() to null
        HapticSignal.Reset -> reset() to null
    }

    private fun activity(playsStart: Boolean): List<HapticEffect> {
        pendingEnd = 0
        active = true
        if (!playsStart || startPlayed) return emptyList()
        startPlayed = true
        return listOf(HapticEffect.ResponseStarted)
    }

    private fun tool(nowMs: Long): List<HapticEffect> {
        val effects = activity(playsStart = false)
        val last = lastToolMs
        if (last != null && nowMs - last < TOOL_GAP_MS) return effects
        lastToolMs = nowMs
        return effects + HapticEffect.ToolStarted
    }

    /** A token for the end, or null when nothing was felt of this turn. */
    private fun ended(): Long? {
        if (!active) return null
        pendingEnd += 1
        return pendingEnd
    }

    /** The end's effect, if [token] is still the pending end (no activity came after it). */
    fun conclude(token: Long): HapticEffect? {
        if (token != pendingEnd || token == 0L) return null
        reset()
        return HapticEffect.ResponseConcluded
    }

    private fun fail(): List<HapticEffect> {
        val notify = active
        reset()
        return if (notify) listOf(HapticEffect.Error) else emptyList()
    }

    private fun reset(): List<HapticEffect> {
        active = false
        startPlayed = false
        lastToolMs = null
        pendingEnd = 0
        return emptyList()
    }

    companion object {
        const val TOOL_GAP_MS = 250L
        const val CONCLUSION_DELAY_MS = 180L
    }
}
