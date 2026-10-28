package lab.dawnforge.hermes.core.data.plugin

import java.util.concurrent.ConcurrentHashMap

/**
 * An answered call from Hermes, for the voice conversation it opens with: [intro] says who called
 * and why ("Hi, it's Hermes. “Check the server” finished."). Unless Hermes waits on the user, the
 * chat's latest reply is read after it, as on iOS.
 */
data class AnsweredCall(val intro: String, val waitsOnUser: Boolean)

/**
 * Calls from Hermes the user just answered, kept until their chat opens and starts its voice
 * conversation with what the call was about.
 */
class AnsweredCalls {
    private val waiting = ConcurrentHashMap<String, AnsweredCall>()

    fun offer(sessionId: String, call: AnsweredCall) {
        waiting[sessionId] = call
    }

    /** The answered call for any of the chat's ids, taken so it opens one conversation only. */
    fun take(vararg sessionIds: String?): AnsweredCall? =
        sessionIds.filterNotNull().firstNotNullOfOrNull { waiting.remove(it) }
}
