package lab.dawnforge.hermes.testing.fakegateway

import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * A scriptable stand-in for Hermes' dashboard chat socket (`/api/ws`), modelled on the
 * Hermes 0.22.2 server:
 *
 * - Upgrades need a single-use ticket in the `hermes-gateway-ticket.<t>` subprotocol next to
 *   `hermes-gateway-v1`; anything else is refused with HTTP 403, as uvicorn does.
 * - The first frame is the `gateway.ready` event with a per-process `replay_epoch`.
 * - `gateway.ping`, `client.capabilities`, `session.create`, `session.activate`, `session.resume`
 *   and `session.events.since` behave like the real ones; [handle] adds or overrides methods.
 * - A chat `session.create` starts gets the id its stored row will have, but, as in Hermes, it is
 *   stored only once its first message is ([markStored]): until then `session.resume` by that id
 *   finds nothing once the live session is gone.
 * - Session events get a per-session `seq` and are kept in a bounded replay ring.
 *
 * Every method is thread-safe; socket callbacks run on OkHttp's threads.
 */
class FakeGateway(
    /** How many events each session's replay ring keeps (Hermes keeps 512). */
    private val ringSize: Int = 512,
) : Closeable {
    private val json = Json { ignoreUnknownKeys = true }
    private val server = MockWebServer()
    private val lock = Any()

    private val validTickets = mutableSetOf<String>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val sessions = mutableMapOf<String, FakeSession>()
    private val openRequests = linkedMapOf<String, JsonObject>()
    private val handlers = mutableMapOf<String, (JsonObject) -> Reply>()
    private val frames = LinkedBlockingQueue<JsonObject>()
    private val runtimeIds = AtomicInteger(0)
    private val requestIds = AtomicInteger(0)
    private val storedIds = AtomicInteger(0)

    /** Stored ids reserved by `session.create` for chats not stored yet. */
    private val unstored = mutableSetOf<String>()

    /** How many chats `session.create` started. */
    val createdChats = AtomicInteger(0)

    /** Every upgrade attempt, accepted or refused. */
    val upgradeRequests: MutableList<RecordedRequest> = CopyOnWriteArrayList()

    /** The current process "boot"; [restart] changes it. */
    @Volatile
    var replayEpoch: String = newEpoch()
        private set

    /** When false the gateway reads frames but answers nothing, like a half-open socket. */
    @Volatile
    var responsive: Boolean = true

    /** Status for `HEAD`/`GET /api/status`, the readiness probe. */
    @Volatile
    var statusCode: Int = 200

    /** What a `/api/ws` upgrade gets when set, instead of the ticket check (e.g. 302 or 503). */
    @Volatile
    var upgradeOverride: MockResponse? = null

    val port: Int get() = server.port
    val hostName: String get() = server.hostName

    /** Answers to server→client requests, keyed by request id. */
    val answers: MutableMap<String, JsonObject> = java.util.concurrent.ConcurrentHashMap()

    val connectedSockets: Int get() = sockets.size

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = route(request)
        }
        server.start()
    }

    override fun close() {
        sockets.forEach { it.close(GOING_AWAY, null) }
        server.close()
    }

    // ---- Scripting -----------------------------------------------------------------------------

    /** A ticket the next upgrade may present once. */
    fun issueTicket(): String {
        val ticket = "t-" + UUID.randomUUID().toString().replace("-", "")
        synchronized(lock) { validTickets += ticket }
        return ticket
    }

    /** Adds or replaces an RPC handler. */
    fun handle(method: String, handler: (params: JsonObject) -> Reply) =
        synchronized(lock) { handlers[method] = handler }

    /** Creates a live session; returns its runtime id. */
    fun createSession(storedId: String): String = synchronized(lock) {
        val runtime = nextRuntimeId()
        sessions[runtime] = FakeSession(runtime, storedId)
        runtime
    }

    /**
     * Adds [fields] to the session's snapshot (`session.resume`, `session.activate`), e.g. `running`
     * and `inflight` for a turn that is running.
     */
    fun updateSnapshot(runtimeId: String, fields: JsonObject) = synchronized(lock) {
        val session = sessions[runtimeId] ?: error("No session $runtimeId")
        session.extras = JsonObject(session.extras + fields)
    }

    /** The chat stored as [storedId] has its first message: Hermes has stored it. */
    fun markStored(storedId: String) {
        synchronized(lock) { unstored -= storedId }
    }

    /** The stored id of the live session [runtimeId]. */
    fun storedIdOf(runtimeId: String): String = synchronized(lock) {
        sessions[runtimeId]?.storedId ?: error("No session $runtimeId")
    }

    /** Forgets a live session, as the orphan reaper does; its runtime id then answers 4001. */
    fun reap(runtimeId: String) {
        synchronized(lock) { sessions.remove(runtimeId) }
    }

    /** Records [type] for the session (giving it the next seq) and sends it to attached sockets. */
    fun pushEvent(sessionId: String, type: String, payload: JsonObject? = null): Long {
        val (params, targets) = synchronized(lock) {
            val session = sessions[sessionId] ?: error("No session $sessionId")
            val params = buildJsonObject {
                put("type", type)
                put("session_id", sessionId)
                if (payload != null) put("payload", payload)
                put("seq", ++session.seq)
            }
            session.ring.addLast(params)
            while (session.ring.size > ringSize) session.ring.removeFirst()
            params to session.attached.toList()
        }
        val frame = eventFrame(params)
        targets.forEach { it.send(frame) }
        return params["seq"]!!.jsonPrimitive.longOrNull!!
    }

    /** A session-less broadcast such as `sessions.changed`, with no seq. */
    fun broadcast(type: String) {
        val frame = eventFrame(
            buildJsonObject {
                put("type", type)
                put("session_id", "")
            },
        )
        sockets.forEach { it.send(frame) }
    }

    /** Sends a server→client request to the session's sockets; returns its `srq-` id. */
    fun sendServerRequest(sessionId: String, method: String, params: JsonObject = JsonObject(emptyMap())): String {
        val id = "srq-%012x".format(requestIds.incrementAndGet())
        val full = JsonObject(params + ("session_id" to JsonPrimitive(sessionId)))
        val targets = synchronized(lock) {
            openRequests[id] = buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", full)
            }
            sessions[sessionId]?.attached?.toList().orEmpty()
        }
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", full)
        }.toString()
        targets.forEach { it.send(frame) }
        return id
    }

    /** Withdraws a server request with a `request.cancel` event. */
    fun cancelServerRequest(sessionId: String, id: String, reason: String) {
        val request = synchronized(lock) { openRequests.remove(id) } ?: return
        pushEvent(
            sessionId,
            "request.cancel",
            buildJsonObject {
                put("id", id)
                put("method", request["method"]!!)
                put("reason", reason)
            },
        )
    }

    /**
     * Drops every socket. A server-side OkHttp socket cannot be cut without a close frame, so this
     * sends 1001; the client treats it like any lost connection.
     */
    fun dropConnections() {
        sockets.forEach { it.close(GOING_AWAY, "dropped") }
        sockets.clear()
        synchronized(lock) { sessions.values.forEach { it.attached.clear() } }
    }

    /** A gateway restart: new epoch, every live session and open request gone. */
    fun restart() {
        dropConnections()
        synchronized(lock) {
            sessions.clear()
            openRequests.clear()
            replayEpoch = newEpoch()
        }
    }

    /** Waits for the next frame the client sent that matches [predicate]; frames before it are skipped. */
    fun awaitFrame(timeoutMs: Long = 5_000, predicate: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            check(remaining > 0) { "No matching frame within ${timeoutMs}ms" }
            val frame = frames.poll(remaining, TimeUnit.MILLISECONDS) ?: continue
            if (predicate(frame)) return frame
        }
    }

    fun awaitCall(method: String, timeoutMs: Long = 5_000): JsonObject =
        awaitFrame(timeoutMs) { (it["method"] as? JsonPrimitive)?.contentOrNull == method }

    // ---- Server side ---------------------------------------------------------------------------

    private fun route(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        return when {
            path.endsWith("/api/ws") -> upgrade(request)
            path.endsWith("/api/status") -> MockResponse(code = statusCode, body = """{"version":"fake"}""")
            else -> MockResponse(code = 404)
        }
    }

    private fun upgrade(request: RecordedRequest): MockResponse {
        upgradeRequests += request
        upgradeOverride?.let { return it }
        val protocols = request.headers["Sec-WebSocket-Protocol"].orEmpty()
            .split(',').map(String::trim).filter(String::isNotEmpty)
        val tickets = protocols.filter { it.startsWith(TICKET_PREFIX) }
        val ticket = tickets.singleOrNull()?.removePrefix(TICKET_PREFIX)
        // The ticket is spent on presentation, valid or not, as in Hermes.
        val accepted = PROTOCOL in protocols && ticket != null && synchronized(lock) { validTickets.remove(ticket) }
        if (!accepted) return MockResponse(code = 403)
        return MockResponse.Builder()
            .setHeader("Sec-WebSocket-Protocol", PROTOCOL)
            .webSocketUpgrade(Listener())
            .build()
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets += webSocket
            webSocket.send(
                eventFrame(
                    buildJsonObject {
                        put("type", "gateway.ready")
                        put(
                            "payload",
                            buildJsonObject {
                                put("skin", JsonObject(emptyMap()))
                                put("change_events", true)
                                put("heartbeat", true)
                                put("replay_epoch", replayEpoch)
                            },
                        )
                    },
                ),
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            frames += frame
            if (!responsive) return
            val method = (frame["method"] as? JsonPrimitive)?.contentOrNull
            val id = frame["id"]
            if (method == null) {
                // A response to one of our server requests.
                val requestId = (id as? JsonPrimitive)?.contentOrNull ?: return
                answers[requestId] = frame
                synchronized(lock) { openRequests.remove(requestId) }
                return
            }
            val params = frame["params"] as? JsonObject ?: JsonObject(emptyMap())
            val reply = dispatch(webSocket, method, params)
            if (reply is Reply.Drop) {
                dropConnections()
                return
            }
            webSocket.send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id ?: JsonNull)
                    when (reply) {
                        Reply.Drop -> Unit

                        is Reply.Result -> put("result", reply.value)

                        is Reply.Error -> put(
                            "error",
                            buildJsonObject {
                                put("code", reply.code)
                                put("message", reply.message)
                            },
                        )
                    }
                }.toString(),
            )
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            detach(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = detach(webSocket)
    }

    private fun detach(socket: WebSocket) {
        sockets -= socket
        synchronized(lock) { sessions.values.forEach { it.attached -= socket } }
    }

    private fun dispatch(socket: WebSocket, method: String, params: JsonObject): Reply {
        synchronized(lock) { handlers[method] }?.let { return it(params) }
        return when (method) {
            "gateway.ping" -> Reply.Result(buildJsonObject { put("ok", true) })

            "client.capabilities" -> Reply.Result(
                buildJsonObject {
                    put("server_requests", buildJsonArray { SERVER_REQUESTS.forEach { add(JsonPrimitive(it)) } })
                    put("declines_not_shown", true)
                },
            )

            "session.create" -> create(socket)

            "session.activate" -> activate(socket, params)

            "session.resume" -> resume(socket, params)

            "session.events.since" -> eventsSince(params)

            else -> Reply.Error(-32601, "unknown method: $method")
        }
    }

    private fun activate(socket: WebSocket, params: JsonObject): Reply = synchronized(lock) {
        val id = params.string("session_id")
        val session = sessions[id] ?: return Reply.Error(4001, "session not found")
        session.attached += socket
        Reply.Result(snapshot(session))
    }

    private fun create(socket: WebSocket): Reply = synchronized(lock) {
        val stored = "20261024_%06d_new".format(storedIds.incrementAndGet())
        val session = FakeSession(nextRuntimeId(), stored).also { sessions[it.runtimeId] = it }
        unstored += stored
        createdChats.incrementAndGet()
        session.attached += socket
        Reply.Result(snapshot(session))
    }

    private fun resume(socket: WebSocket, params: JsonObject): Reply = synchronized(lock) {
        val stored = params.string("session_id")
        val live = sessions.values.firstOrNull { it.storedId == stored }
        // A chat never stored, and no longer live: Hermes has nothing to resume.
        if (live == null && stored in unstored) return Reply.Error(4007, "session not found")
        val session = live ?: FakeSession(nextRuntimeId(), stored).also { sessions[it.runtimeId] = it }
        session.attached += socket
        Reply.Result(snapshot(session))
    }

    private fun eventsSince(params: JsonObject): Reply = synchronized(lock) {
        val id = params.string("session_id")
        val lastSeen = (params["last_seen"] as? JsonPrimitive)?.longOrNull ?: 0
        val session = sessions[id]
        val events = session?.ring?.filter { it["seq"]!!.jsonPrimitive.longOrNull!! > lastSeen }.orEmpty()
        val oldest = session?.ring?.firstOrNull()?.get("seq")?.jsonPrimitive?.longOrNull
        Reply.Result(
            buildJsonObject {
                put("events", JsonArray(events))
                put("latest_seq", session?.seq ?: 0)
                put("truncated", oldest != null && oldest > lastSeen + 1)
                put("count", events.size)
                put("epoch", replayEpoch)
                put("open_requests", openRequestsFor(id))
            },
        )
    }

    private fun snapshot(session: FakeSession) = buildJsonObject {
        put("session_id", session.runtimeId)
        put("stored_session_id", session.storedId)
        put("message_count", 0)
        put("messages", JsonArray(emptyList()))
        put("info", JsonObject(emptyMap()))
        put("open_requests", openRequestsFor(session.runtimeId))
        session.extras.forEach { (key, value) -> put(key, value) }
    }

    private fun openRequestsFor(sessionId: String): JsonArray = JsonArray(
        openRequests.values.filter { (it["params"] as JsonObject).string("session_id") == sessionId },
    )

    private fun nextRuntimeId() = "%08x".format(0x5e550000 + runtimeIds.incrementAndGet())

    private fun eventFrame(params: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "event")
        put("params", params)
    }.toString()

    private class FakeSession(val runtimeId: String, val storedId: String) {
        var seq = 0L
        val ring = ArrayDeque<JsonObject>()
        val attached = mutableSetOf<WebSocket>()
        var extras = JsonObject(emptyMap())
    }

    sealed interface Reply {
        data class Result(val value: JsonElement) : Reply

        data class Error(val code: Int, val message: String) : Reply

        /** A fault: the call is read, then every connection drops before an answer goes out. */
        data object Drop : Reply
    }

    companion object {
        const val PROTOCOL = "hermes-gateway-v1"
        private const val GOING_AWAY = 1001
        const val TICKET_PREFIX = "hermes-gateway-ticket."
        private val SERVER_REQUESTS = listOf("approval", "clarify", "secret", "sudo", "vault.unlock_prompt")

        private fun newEpoch() = UUID.randomUUID().toString().replace("-", "")

        private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}
