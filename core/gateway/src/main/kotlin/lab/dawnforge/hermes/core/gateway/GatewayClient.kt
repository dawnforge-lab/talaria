package lab.dawnforge.hermes.core.gateway

import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.net.ConnectionFailure
import okhttp3.OkHttpClient

/** A fresh single-use WebSocket ticket, minted right before each connection attempt. */
sealed interface TicketResult {
    data class Ticket(val value: String) : TicketResult {
        override fun toString() = "Ticket(<redacted>)"
    }

    /** The dashboard session is gone and could not be renewed. */
    data class SignInRequired(val reason: ConnectionFailure?) : TicketResult

    data class Unavailable(val failure: ConnectionFailure) : TicketResult
}

fun interface TicketSource {
    suspend fun mint(): TicketResult
}

/** Whether the dashboard answers at all, checked before each retry so no ticket is wasted: null when up. */
fun interface ReadinessProbe {
    suspend fun check(): ConnectionFailure?
}

fun interface GatewayConnector {
    suspend fun connect(ticket: String): GatewayConnection
}

/** Connects with OkHttp to the dashboard at [dashboard]. */
class OkHttpGatewayConnector(
    private val client: OkHttpClient,
    private val dashboard: DashboardUrl,
    private val policy: GatewayPolicy,
    private val log: GatewayLog = GatewayLog.None,
) : GatewayConnector {
    override suspend fun connect(ticket: String) =
        GatewayConnection.open(client, dashboard, ticket, policy.openTimeoutMs, log)
}

/** Why the last attempt or connection failed. */
sealed interface GatewayFailure {
    data class Handshake(val failure: HandshakeFailure) : GatewayFailure

    data class Ticket(val failure: ConnectionFailure) : GatewayFailure

    /** The readiness probe found the dashboard down, for [failure]. */
    data class NotReachable(val failure: ConnectionFailure) : GatewayFailure

    data class Lost(val reason: CloseReason) : GatewayFailure
}

sealed interface GatewayState {
    data object Stopped : GatewayState

    /** [attempt] is 0 for the first try after starting, then counts failed tries. */
    data class Connecting(val attempt: Int) : GatewayState

    data class Connected(val sinceMs: Long, val ready: GatewayReady) : GatewayState

    data class Waiting(val attempt: Int, val retryAtMs: Long, val failure: GatewayFailure) : GatewayState

    /** Waits for [GatewayClient.reconnectNow] after the user signs in again. */
    data class SignInRequired(val reason: ConnectionFailure?) : GatewayState
}

sealed interface GatewayNotice {
    /** Events after the last one delivered could not be replayed; refetch the session's state. */
    data class ReplayGap(val sessionId: String, val reason: GapReason) : GatewayNotice

    /** A tracked session came back under a new runtime id (after `session.resume`). */
    data class SessionRebound(val previousId: String, val sessionId: String) : GatewayNotice

    enum class GapReason {
        /** Hermes restarted: a new `replay_epoch`, all runtime sessions gone. */
        GatewayRestarted,

        /** The replay ring no longer holds every missed event. */
        Truncated,

        /** The runtime session was reaped while disconnected. */
        SessionLost,
    }
}

/**
 * Keeps one dashboard's chat socket alive.
 *
 * - **Connecting:** mints a fresh ticket for every attempt, opens the socket, waits for
 *   `gateway.ready`, then sends `client.capabilities {server_requests: true}` so Hermes routes
 *   approvals and questions to this client.
 * - **Keepalive:** after [GatewayPolicy.keepaliveAfterMs] without any frame it sends
 *   `gateway.ping`; after [GatewayPolicy.deadAfterMs] it gives the socket up.
 * - **Reconnecting:** backs off ([GatewayPolicy.retryDelayMs]), probes that the dashboard is up
 *   before spending a ticket, and stops at [GatewayState.SignInRequired] when the session is gone.
 * - **Replay:** sessions passed to [attachSession]/[trackSession] are re-attached after a
 *   reconnect (`session.activate`, or `session.resume` by stored id when the runtime session was
 *   reaped), and the events missed meanwhile come from `session.events.since`, deduplicated by
 *   their per-session `seq`. A gap that cannot be filled is reported in [notices].
 * - **Server requests** reach [serverRequests]; desktop-window ones (previews, terminal, tour,
 *   theme) are declined with 4404 so a desktop attached to the same session can still answer.
 */
class GatewayClient(
    private val scope: CoroutineScope,
    private val connector: GatewayConnector,
    private val tickets: TicketSource,
    private val readiness: ReadinessProbe,
    private val policy: GatewayPolicy = GatewayPolicy(),
    /** Added as `params.profile` to session-scoped calls; null or "default" adds nothing. */
    private val profile: String? = null,
    private val log: GatewayLog = GatewayLog.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private val _state = MutableStateFlow<GatewayState>(GatewayState.Stopped)
    val state: StateFlow<GatewayState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private val _serverRequests = MutableSharedFlow<ServerRequest>(extraBufferCapacity = REQUEST_BUFFER)
    val serverRequests: SharedFlow<ServerRequest> = _serverRequests.asSharedFlow()

    private val _pendingRequests = MutableStateFlow<Map<String, ServerRequest>>(emptyMap())

    /**
     * Server requests delivered and not yet answered, declined or withdrawn, by id. A screen
     * opened after a request arrived reads it here; [serverRequests] only tells who is listening.
     */
    val pendingRequests: StateFlow<Map<String, ServerRequest>> = _pendingRequests.asStateFlow()

    private val _notices = MutableSharedFlow<GatewayNotice>(extraBufferCapacity = REQUEST_BUFFER)
    val notices: SharedFlow<GatewayNotice> = _notices.asSharedFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    @Volatile
    private var connection: GatewayConnection? = null

    @Volatile
    var foreground: Boolean = true

    private val tracked = ConcurrentHashMap<String, TrackedSession>()
    private val watermarks = ConcurrentHashMap<String, Long>()
    private var knownEpoch: String? = null

    private data class TrackedSession(val runtimeId: String, val storedId: String?)

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        connection = null
        _state.value = GatewayState.Stopped
    }

    /**
     * Retries now instead of waiting out the backoff, and restarts the backoff from its first step:
     * the network came back, or the user signed in.
     */
    fun reconnectNow() {
        wake.trySend(Unit)
    }

    /** Calls [method] on the open connection. Throws [GatewayClosedException] when not connected. */
    suspend fun call(method: String, params: JsonObject? = null, timeoutMs: Long = policy.callTimeoutMs): JsonElement {
        val open = connection?.takeIf { it.isOpen } ?: throw GatewayClosedException("Not connected")
        return open.call(method, scoped(method, params), timeoutMs)
    }

    /**
     * Attaches this socket to a live session (`session.activate`), so its events come here, and
     * keeps it attached across reconnects. Returns the session snapshot.
     */
    suspend fun attachSession(runtimeId: String, storedId: String?): JsonObject {
        val snapshot = call("session.activate", sessionParams(runtimeId)) as JsonObject
        trackSession(runtimeId, storedId ?: snapshot.string("stored_session_id"))
        snapshot.openRequests().forEach { deliver(it, connection) }
        return snapshot
    }

    /**
     * Delivers the requests still open in a session snapshot (`session.resume`/`session.activate`
     * results carry them as `open_requests`), for a caller that attached the session itself.
     */
    suspend fun offerOpenRequests(snapshot: JsonObject) {
        snapshot.openRequests().forEach { deliver(it, connection) }
    }

    /** Keeps an already-attached session (e.g. one just made by `session.create`) attached across reconnects. */
    fun trackSession(runtimeId: String, storedId: String?) {
        tracked[runtimeId] = TrackedSession(runtimeId, storedId)
    }

    fun untrackSession(runtimeId: String) {
        tracked.remove(runtimeId)
    }

    fun respond(request: ServerRequest, result: JsonElement): Boolean {
        val sent = connection?.respond(request.id, result) ?: false
        if (sent) settle(request.id)
        return sent
    }

    fun decline(request: ServerRequest, code: Int = NOT_SHOWN, message: String = NOT_SHOWN_MESSAGE): Boolean {
        val sent = connection?.decline(request.id, code, message) ?: false
        if (sent) settle(request.id)
        return sent
    }

    // ---- Connection loop -----------------------------------------------------------------------

    private suspend fun run() {
        var attempt = 0
        var lastFailure: GatewayFailure? = null
        while (currentCoroutineContext().isActive) {
            if (attempt > 0) {
                val delayMs = policy.retryDelayMs(attempt, foreground, random)
                _state.value = GatewayState.Waiting(attempt, clock() + delayMs, checkNotNull(lastFailure))
                val woken = withTimeoutOrNull(delayMs) { wake.receive() } != null
                // Woken early (network back, signed in): the old failures say nothing about the new
                // situation, so the backoff starts again from its first step.
                if (woken) attempt = 1
                val down = readiness.check()
                if (down != null) {
                    lastFailure = GatewayFailure.NotReachable(down)
                    attempt++
                    continue
                }
            }

            _state.value = GatewayState.Connecting(attempt)
            val ticket = when (val minted = tickets.mint()) {
                is TicketResult.Ticket -> minted.value

                is TicketResult.SignInRequired -> {
                    waitForSignIn(minted.reason)
                    attempt = 0
                    continue
                }

                is TicketResult.Unavailable -> {
                    lastFailure = GatewayFailure.Ticket(minted.failure)
                    attempt++
                    continue
                }
            }

            val conn = try {
                connector.connect(ticket)
            } catch (e: GatewayHandshakeException) {
                log.warn("gateway handshake failed: ${e.failure}")
                if (e.failure == HandshakeFailure.SignInRequired) {
                    waitForSignIn(null)
                    attempt = 0
                    continue
                }
                lastFailure = GatewayFailure.Handshake(e.failure)
                attempt++
                continue
            }

            val openedAt = clock()
            val reason = try {
                serve(conn)
            } finally {
                connection = null
                if (conn.isOpen) conn.close(CloseReason.Local)
            }
            log.info("gateway connection ended after ${clock() - openedAt}ms: $reason")
            if (clock() - openedAt >= policy.stableAfterMs) attempt = 0
            lastFailure = GatewayFailure.Lost(reason)
            attempt++
        }
    }

    private suspend fun waitForSignIn(reason: ConnectionFailure?) {
        _state.value = GatewayState.SignInRequired(reason)
        // Drop a wake-up sent before sign-in was needed (the channel holds at most one).
        wake.tryReceive()
        wake.receive()
    }

    private suspend fun serve(conn: GatewayConnection): CloseReason = coroutineScope {
        try {
            conn.call(
                "client.capabilities",
                buildJsonObject { put("server_requests", true) },
                policy.callTimeoutMs,
            )
        } catch (e: RpcException) {
            log.warn("client.capabilities refused (${e.code}); server requests will not reach this client")
        } catch (_: RpcTimeoutException) {
            log.warn("client.capabilities timed out")
        } catch (_: GatewayClosedException) {
            return@coroutineScope conn.awaitClosed()
        }

        val epoch = conn.ready.replayEpoch
        val restarted = knownEpoch != null && epoch != null && epoch != knownEpoch
        if (restarted) {
            log.info("gateway restarted (new replay epoch); runtime sessions are gone")
            watermarks.clear()
            _pendingRequests.value = emptyMap()
        }
        if (epoch != null) knownEpoch = epoch

        for (session in tracked.values.toList()) {
            try {
                reattach(conn, session, restarted)
            } catch (_: GatewayClosedException) {
                return@coroutineScope conn.awaitClosed()
            }
        }

        connection = conn
        _state.value = GatewayState.Connected(clock(), conn.ready)
        val keepalive = launch { keepalive(conn) }
        // Frames that arrived during the re-attach waited in the channel, so replayed events
        // are emitted before the live ones that followed them.
        for (frame in conn.frames) {
            when (frame) {
                is Inbound.Event -> emitEvent(frame.event)
                is Inbound.Request -> deliver(frame.request, conn)
                else -> Unit
            }
        }
        keepalive.cancel()
        conn.awaitClosed()
    }

    private suspend fun keepalive(conn: GatewayConnection) = coroutineScope {
        var ping: Job? = null
        while (isActive && conn.isOpen) {
            delay(policy.keepaliveCheckMs)
            val idle = clock() - conn.lastInboundAtMs
            if (idle >= policy.deadAfterMs) {
                log.warn("no frame for ${idle}ms; giving the socket up")
                conn.close(CloseReason.KeepaliveTimeout)
                return@coroutineScope
            }
            if (idle >= policy.keepaliveAfterMs && ping?.isActive != true) {
                ping = launch {
                    try {
                        conn.call("gateway.ping", JsonObject(emptyMap()), policy.deadAfterMs)
                    } catch (_: Exception) {
                        // A missing pong is judged by the idle time above, not here.
                    }
                }
            }
        }
    }

    // ---- Replay --------------------------------------------------------------------------------

    private suspend fun reattach(conn: GatewayConnection, session: TrackedSession, restarted: Boolean) {
        val snapshot = if (restarted) null else activateOrNull(conn, session.runtimeId)
        if (snapshot != null) {
            snapshot.openRequests().forEach { deliver(it, conn) }
            replay(conn, session.runtimeId)
            return
        }

        tracked.remove(session.runtimeId)
        val reason = if (restarted) GatewayNotice.GapReason.GatewayRestarted else GatewayNotice.GapReason.SessionLost
        val storedId = session.storedId
        if (storedId == null) {
            _notices.emit(GatewayNotice.ReplayGap(session.runtimeId, reason))
            return
        }
        val resumed = try {
            conn.call(
                "session.resume",
                scoped(
                    "session.resume",
                    buildJsonObject {
                        put("session_id", storedId)
                        put("omit_messages", true)
                    },
                ),
                policy.callTimeoutMs,
            ) as JsonObject
        } catch (e: RpcException) {
            log.warn("session.resume failed (${e.code}) for a tracked session")
            _notices.emit(GatewayNotice.ReplayGap(session.runtimeId, reason))
            return
        }
        val newId = resumed.string("session_id") ?: return
        tracked[newId] = TrackedSession(newId, storedId)
        _notices.emit(GatewayNotice.SessionRebound(session.runtimeId, newId))
        _notices.emit(GatewayNotice.ReplayGap(newId, reason))
        resumed.openRequests().forEach { deliver(it, conn) }
    }

    private suspend fun activateOrNull(conn: GatewayConnection, runtimeId: String): JsonObject? = try {
        conn.call(
            "session.activate",
            scoped("session.activate", sessionParams(runtimeId)),
            policy.callTimeoutMs,
        ) as JsonObject
    } catch (e: RpcException) {
        if (e.code != SESSION_NOT_FOUND && e.code != SESSION_NOT_LIVE) log.warn("session.activate failed (${e.code})")
        null
    }

    private suspend fun replay(conn: GatewayConnection, runtimeId: String) {
        val since = watermarks[runtimeId] ?: 0
        val result = try {
            conn.call(
                "session.events.since",
                scoped(
                    "session.events.since",
                    buildJsonObject {
                        put("session_id", runtimeId)
                        put("last_seen", since)
                    },
                ),
                policy.callTimeoutMs,
            ) as JsonObject
        } catch (e: RpcException) {
            log.warn("session.events.since failed (${e.code})")
            _notices.emit(GatewayNotice.ReplayGap(runtimeId, GatewayNotice.GapReason.Truncated))
            return
        }
        val events = (result["events"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toEvent() }
        events.forEach { emitEvent(it.copy(replayed = true)) }
        if ((result["truncated"] as? JsonPrimitive)?.booleanOrNull == true) {
            (result["latest_seq"] as? JsonPrimitive)?.longOrNull?.let { latest ->
                watermarks.merge(runtimeId, latest, ::maxOf)
            }
            _notices.emit(GatewayNotice.ReplayGap(runtimeId, GatewayNotice.GapReason.Truncated))
        }
        result.openRequests().forEach { deliver(it, conn) }
    }

    private suspend fun emitEvent(event: GatewayEvent) {
        val sessionId = event.sessionId
        val seq = event.seq
        if (sessionId != null && seq != null) {
            // Live and replayed events overlap after a reconnect; the seq keeps each one once.
            val last = watermarks[sessionId] ?: 0
            if (seq <= last) return
            watermarks[sessionId] = seq
        }
        if (event.type == "request.cancel") {
            (event.payload?.get("id") as? JsonPrimitive)?.contentOrNull?.let(::settle)
        }
        _events.emit(event)
    }

    private suspend fun deliver(request: ServerRequest, conn: GatewayConnection?) {
        if (request.method in WINDOW_OWNED) {
            conn?.decline(request.id, NOT_SHOWN, NOT_SHOWN_MESSAGE)
            return
        }
        var added = false
        _pendingRequests.update { pending ->
            if (request.id in pending) pending else pending.plus(request.id to request).also { added = true }
        }
        if (added) _serverRequests.emit(request)
    }

    private fun settle(requestId: String) = _pendingRequests.update { it - requestId }

    // ---- Helpers -------------------------------------------------------------------------------

    private fun sessionParams(runtimeId: String) = buildJsonObject {
        put("session_id", runtimeId)
        put("omit_messages", true)
    }

    private fun scoped(method: String, params: JsonObject?): JsonObject? {
        val name = profile?.takeIf { it.isNotEmpty() && it != DEFAULT_PROFILE } ?: return params
        if (method in UNSCOPED || UNSCOPED_PREFIXES.any(method::startsWith)) return params
        val base = params ?: JsonObject(emptyMap())
        if ("profile" in base) return base
        return JsonObject(base + ("profile" to JsonPrimitive(name)))
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.openRequests(): List<ServerRequest> =
        (this["open_requests"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id") ?: return@mapNotNull null
            val method = obj.string("method") ?: return@mapNotNull null
            ServerRequest(id, method, obj["params"] as? JsonObject ?: JsonObject(emptyMap()), replayed = true)
        }

    private fun JsonObject.toEvent(): GatewayEvent? {
        val type = string("type") ?: return null
        return GatewayEvent(
            type = type,
            sessionId = string("session_id")?.takeIf(String::isNotEmpty),
            payload = this["payload"] as? JsonObject,
            seq = (this["seq"] as? JsonPrimitive)?.longOrNull,
        )
    }

    companion object {
        /** "No window here shows this session": lets another client (the desktop) answer instead. */
        const val NOT_SHOWN = 4404
        const val NOT_SHOWN_MESSAGE = "no window here shows this session"

        private const val SESSION_NOT_FOUND = 4001
        private const val SESSION_NOT_LIVE = 4007
        private const val DEFAULT_PROFILE = "default"
        private const val EVENT_BUFFER = 1024
        private const val REQUEST_BUFFER = 64

        /** Requests only a desktop window can serve. */
        private val WINDOW_OWNED = setOf(
            "preview.read",
            "preview.act",
            "terminal.read",
            "window.read",
            "tour",
            "theme.apply",
            "theme.pick",
        )

        private val UNSCOPED = setOf("gateway.ping", "ping", "client.capabilities", "gateway.capabilities")
        private val UNSCOPED_PREFIXES = listOf("profiles.", "groups.")
    }
}
