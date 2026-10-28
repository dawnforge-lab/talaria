package lab.dawnforge.hermes.core.push.live

import lab.dawnforge.hermes.core.gateway.GatewayEvent
import lab.dawnforge.hermes.core.gateway.ServerRequest

/**
 * The turns this run of the app follows from their chat's live events, as iOS's
 * `LiveTurnActivities` keeps its activities current from the stream: so the notification shows
 * where the turn is the moment the app leaves the screen, not only after the relay's next push.
 */
class LiveTurnTracker(private val nowMs: () -> Long = System::currentTimeMillis) {
    private class Running(val sessionIds: Set<String>, val startedAtMs: Long, var state: LiveTurnUpdate) {
        /** The methods of the chat's open requests, as last seen. */
        var requests: List<String> = emptyList()
    }

    private val running = LinkedHashMap<String, Running>()

    /**
     * Events of chats whose message is still being sent: the gateway can stream before the send
     * is answered, and those events belong to the turn that starts once it is.
     */
    private val early = ArrayList<Pair<Set<String>, MutableList<GatewayEvent>>>()

    /** A message is about to be sent in the chat known by [sessionIds]: its events are held. */
    @Synchronized
    fun submitting(sessionIds: Collection<String>) {
        val ids = sessionIds.filter(String::isNotBlank).toSet()
        early.removeAll { (held, _) -> held.any(ids::contains) }
        if (ids.isEmpty()) return
        if (early.size >= EARLY_CHATS) early.removeAt(0)
        early += ids to ArrayList()
    }

    /**
     * The message started a turn ([activity]): it is followed from now on, with what streamed
     * while it was being sent. Another turn of the same chat is no longer followed.
     */
    @Synchronized
    fun started(activity: String, sessionIds: Collection<String>): LiveTurnUpdate {
        val ids = sessionIds.filter(String::isNotBlank).toSet()
        val held = early.firstOrNull { (chat, _) -> chat.any(ids::contains) }?.second.orEmpty()
        early.removeAll { (chat, _) -> chat.any(ids::contains) }
        running.entries.removeAll { (_, turn) -> turn.sessionIds.any(ids::contains) }
        val turn = Running(ids, nowMs(), LiveTurnProgress.started(activity))
        running[activity] = turn
        LiveTurnProgress.newTurnEvents(held).forEach { event -> apply(turn, event) }
        return turn.state
    }

    /** A live event: the states it changed. */
    @Synchronized
    fun observe(event: GatewayEvent): List<LiveTurnUpdate> {
        val session = event.sessionId ?: return emptyList()
        early.firstOrNull { (chat, _) -> session in chat }?.second?.let { held ->
            if (held.size >= EARLY_EVENTS) held.removeAt(0)
            held += event
        }
        return running.values.filter { session in it.sessionIds }.mapNotNull { turn -> apply(turn, event) }
    }

    /** The gateway's open requests, every chat's: the states that changed with their chat's. */
    @Synchronized
    fun requests(pending: Collection<ServerRequest>): List<LiveTurnUpdate> = running.values.mapNotNull { turn ->
        val methods = pending.filter { it.sessionId in turn.sessionIds }.map(ServerRequest::method).sorted()
        if (methods == turn.requests) return@mapNotNull null
        turn.requests = methods
        LiveTurnProgress.withRequests(turn.state, methods)?.also { turn.state = it }
    }

    /** The relay pushed [update]: it is where the turn is now. */
    @Synchronized
    fun pushed(update: LiveTurnUpdate) {
        running[update.activity]?.state = update
    }

    /** The turns still running, as they stand. */
    @Synchronized
    fun current(): List<LiveTurnUpdate> = running.values.map { it.state }.filterNot(LiveTurnUpdate::ended)

    @Synchronized
    fun forget(activity: String) {
        running.remove(activity)
    }

    private fun apply(turn: Running, event: GatewayEvent): LiveTurnUpdate? {
        // An idle report read just as the message went out can predate the turn.
        if (LiveTurnProgress.isIdleSignal(event) && nowMs() - turn.startedAtMs < IDLE_SIGNAL_GRACE_MS) return null
        val next = LiveTurnProgress.applying(event, turn.state, nowMs() / MILLIS) ?: return null
        turn.state = next
        return next
    }

    private companion object {
        const val EARLY_CHATS = 4
        const val EARLY_EVENTS = 64
        const val IDLE_SIGNAL_GRACE_MS = 5_000L
        const val MILLIS = 1000L
    }
}
