package lab.dawnforge.hermes.core.data.live

import lab.dawnforge.hermes.core.model.DashboardId

/**
 * A turn the phone just sent, for its live "turn in progress" notification. The push module keeps
 * it current from the chat's live events while the app has them, and through the relay while the
 * app is out of sight.
 */
fun interface LiveTurnStarter {
    /** A message is about to be sent in the chat known by [sessionIds]: what streams meanwhile is kept for its turn. */
    fun submitting(sessionIds: List<String>) {}

    /** Hermes accepted a message in the chat known by [sessionIds] (live and stored ids), titled [title]. */
    fun started(dashboard: DashboardId, profile: String, sessionIds: List<String>, title: String?)
}
