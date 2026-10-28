package lab.dawnforge.hermes.core.push.live

import android.content.Context
import androidx.core.content.edit
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lab.dawnforge.hermes.core.data.gateway.AppForeground
import lab.dawnforge.hermes.core.data.live.LiveTurnStarter
import lab.dawnforge.hermes.core.gateway.GatewayEvent
import lab.dawnforge.hermes.core.gateway.ServerRequest
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.push.PushSetup
import lab.dawnforge.hermes.core.push.PushStore
import lab.dawnforge.hermes.core.push.relay.RelayClient

/** A turn being followed: where its chat is, and what the notification needs to say. */
@Serializable
data class LiveTurn(
    val activity: String,
    val dashboard: String,
    val profile: String,
    val sessionId: String,
    val title: String?,
    val startedAtMs: Long,
)

/** One update the relay pushed for a live turn (`category: HERMES_LIVE_TURN`). */
data class LiveTurnUpdate(
    val activity: String,
    val ended: Boolean,
    val phase: String,
    val tool: String?,
    val tools: Int,
    val context: Int?,
    val endedAtSeconds: Long?,
) {
    companion object {
        const val CATEGORY = "HERMES_LIVE_TURN"
        private val PHASES = setOf("thinking", "tool", "approval", "input", "done", "failed", "stopped")

        fun parse(data: Map<String, String>): LiveTurnUpdate? {
            if (data["category"] != CATEGORY) return null
            val activity = data["activity"]?.takeIf(String::isNotEmpty) ?: return null
            val phase = data["phase"]?.takeIf { it in PHASES } ?: return null
            return LiveTurnUpdate(
                activity = activity,
                ended = data["event"] == "end",
                phase = phase,
                tool = data["tool"]?.takeIf(String::isNotEmpty),
                tools = data["tools"]?.toIntOrNull() ?: 0,
                context = data["context"]?.toIntOrNull(),
                endedAtSeconds = data["ended_at"]?.toLongOrNull(),
            )
        }
    }
}

/**
 * The live "turn in progress" notification, as iOS's Live Activity: a turn sent from this phone in a
 * chat whose profile notifies it is registered with the relay, which pushes the turn's coarse
 * progress (a phase, a tool category, a tool count, the context used) while it runs. It shows while
 * the app is out of sight; on screen the chat says it all.
 *
 * While the app has the chat's live events, they keep the turn's state current as iOS's stream
 * does ([LiveTurnTracker]): leaving the app shows where the turn is at once, even one that already
 * waits for an approval and so has nothing more for the relay to push.
 */
class LiveTurns(
    private val context: Context,
    private val store: PushStore,
    private val setup: PushSetup,
    private val relay: RelayClient,
    private val notifier: LiveTurnNotifier,
    private val foreground: AppForeground,
    private val scope: CoroutineScope,
    /** The live events of the dashboard the app is connected to. */
    events: Flow<GatewayEvent>,
    /** Its open requests (approvals, questions…), every chat's. */
    requests: Flow<Map<String, ServerRequest>>,
    private val clock: () -> Long = System::currentTimeMillis,
) : LiveTurnStarter {
    private val json = Json { ignoreUnknownKeys = true }
    private val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val tracker = LiveTurnTracker(clock)

    init {
        scope.launch { events.collect { event -> changed(tracker.observe(event)) } }
        scope.launch { requests.collect { pending -> changed(tracker.requests(pending.values)) } }
        scope.launch {
            // Leaving the app: each turn followed shows where it is now.
            foreground.isForeground.drop(1).filter { !it }.collect {
                tracker.current().forEach { update -> load(update.activity)?.let { notifier.show(it, update) } }
            }
        }
    }

    override fun submitting(sessionIds: List<String>) = tracker.submitting(sessionIds)

    override fun started(dashboard: DashboardId, profile: String, sessionIds: List<String>, title: String?) {
        val ids = sessionIds.filter(String::isNotBlank).distinct().take(MAX_SESSION_IDS)
        if (ids.isEmpty()) return
        val turn = LiveTurn(UUID.randomUUID().toString(), dashboard.value, profile, ids.last(), title, clock())
        // Followed at once: events stream while the relay is told.
        tracker.started(turn.activity, ids)
        scope.launch {
            // Only a profile that notifies this phone reports its turns to the relay.
            val relayUrl = setup.relayUrl()?.let(RelayClient::relayUrl)
            val installation = store.installation()
            if (store.pairingFor(dashboard, profile) == null || installation == null || relayUrl == null) {
                tracker.forget(turn.activity)
                return@launch
            }
            save(turn)
            val registered = runCatching { relay.registerLiveTurn(relayUrl, installation, turn.activity, ids) }
            if (registered.isFailure) forget(turn.activity)
        }
    }

    /** A pushed update: the notification follows it, while the app is out of sight. */
    fun onPush(data: Map<String, String>) {
        val update = LiveTurnUpdate.parse(data) ?: return
        val turn = load(update.activity) ?: return
        tracker.pushed(update)
        if (update.ended) forget(turn.activity)
        if (foreground.isForeground.value) {
            notifier.cancel(turn)
            return
        }
        notifier.show(turn, update)
    }

    /** The app came forward: the chat on screen says what the notification would. */
    fun onForeground() {
        all().forEach(notifier::cancel)
    }

    /** The chat's own events moved turns on: out of sight, their notifications follow. */
    private fun changed(updates: List<LiveTurnUpdate>) = updates.forEach { update ->
        val turn = load(update.activity) ?: return@forEach
        if (update.ended) forget(turn.activity)
        if (!foreground.isForeground.value) notifier.show(turn, update)
    }

    private fun save(turn: LiveTurn) = synchronized(saved) {
        val kept = all().filter { clock() - it.startedAtMs < MAX_AGE_MS }.takeLast(MAX_TURNS - 1) + turn
        saved.edit { putString(KEY, json.encodeToString(kept)) }
    }

    private fun load(activity: String): LiveTurn? = all().firstOrNull { it.activity == activity }

    private fun forget(activity: String) = synchronized(saved) {
        tracker.forget(activity)
        saved.edit { putString(KEY, json.encodeToString(all().filterNot { it.activity == activity })) }
    }

    private fun all(): List<LiveTurn> = saved.getString(KEY, null)?.let {
        runCatching { json.decodeFromString<List<LiveTurn>>(it) }.getOrNull()
    }.orEmpty()

    private companion object {
        const val PREFERENCES = "hermes_live_turns"
        const val KEY = "turns"
        const val MAX_SESSION_IDS = 6
        const val MAX_TURNS = 8

        /** The relay forgets an activity after 8 hours, as iOS ends one. */
        const val MAX_AGE_MS = 8 * 60 * 60 * 1000L
    }
}
