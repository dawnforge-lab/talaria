package lab.dawnforge.hermes.core.gateway

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.toConnectionFailure
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** What `gateway.ready`, the first frame of every connection, said. */
data class GatewayReady(
    /** Per gateway process; a different value after a reconnect means Hermes restarted. */
    val replayEpoch: String?,
    /** The gateway broadcasts `sessions.changed` and similar, so polling is only a backstop. */
    val changeEvents: Boolean,
    /** The gateway answers `gateway.ping`. */
    val heartbeat: Boolean,
)

/** Why opening the socket failed. */
sealed interface HandshakeFailure {
    /** HTTP 403: a spent or expired ticket, or a refused Host/Origin. Hermes gives no detail. */
    data class Rejected(val status: Int) : HandshakeFailure

    /** 401, or a redirect to a sign-in page: the dashboard session is gone. */
    data object SignInRequired : HandshakeFailure

    /** 502/503/504 and the like from a proxy in front of a dead backend. */
    data class Unavailable(val status: Int) : HandshakeFailure

    data class UnexpectedStatus(val status: Int) : HandshakeFailure

    data class Network(val failure: ConnectionFailure) : HandshakeFailure

    /** No `gateway.ready` within the open timeout. */
    data object Timeout : HandshakeFailure
}

class GatewayHandshakeException(val failure: HandshakeFailure) : Exception(failure.toString())

/** Why an open connection ended. */
sealed interface CloseReason {
    data class Remote(val code: Int, val reason: String) : CloseReason

    data class Failure(val failure: ConnectionFailure) : CloseReason

    /** No frame for longer than the policy's dead-after time, despite pings. */
    data object KeepaliveTimeout : CloseReason

    data object Local : CloseReason
}

/**
 * One open chat socket (`/api/ws`): JSON-RPC calls matched to their replies by id, and the
 * stream of events and server requests in arrival order. Text frames only, one JSON object per
 * frame, as the gateway requires. Reconnecting is [GatewayClient]'s job; a connection never
 * reopens.
 */
class GatewayConnection private constructor(private val log: GatewayLog, private val clock: () -> Long) {
    private lateinit var socket: WebSocket
    private val ids = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val inbound = Channel<Inbound>(Channel.UNLIMITED)
    private val readyFrame = CompletableDeferred<GatewayReady>()
    private val opened = CompletableDeferred<Unit>()
    private val closedReason = CompletableDeferred<CloseReason>()

    @Volatile
    var lastInboundAtMs: Long = clock()
        private set

    lateinit var ready: GatewayReady
        private set

    /** Events and server requests, in arrival order. Closed when the connection ends. */
    internal val frames: ReceiveChannel<Inbound> get() = inbound

    val isOpen: Boolean get() = !closedReason.isCompleted

    suspend fun awaitClosed(): CloseReason = closedReason.await()

    /** Calls [method] and returns its `result`; throws [RpcException] for an error reply. */
    suspend fun call(method: String, params: JsonObject?, timeoutMs: Long): JsonElement {
        val id = "c${ids.incrementAndGet()}"
        val reply = CompletableDeferred<JsonElement>()
        pending[id] = reply
        try {
            if (!isOpen || !socket.send(Outbound.request(id, method, params))) {
                throw GatewayClosedException("Not connected")
            }
            return try {
                withTimeout(timeoutMs) { reply.await() }
            } catch (e: TimeoutCancellationException) {
                throw RpcTimeoutException(method, timeoutMs, e)
            }
        } finally {
            pending.remove(id)
        }
    }

    /** Answers a server request with a result. */
    fun respond(requestId: String, result: JsonElement): Boolean =
        isOpen && socket.send(Outbound.result(requestId, result))

    /** Declines a server request, e.g. with 4404 ("no window here shows this session"). */
    fun decline(requestId: String, code: Int, message: String): Boolean =
        isOpen && socket.send(Outbound.error(requestId, code, message))

    fun close(reason: CloseReason = CloseReason.Local) {
        // Record the reason first: cancelling makes OkHttp report a failure of its own.
        finish(reason)
        if (reason == CloseReason.Local) socket.close(NORMAL_CLOSURE, null) else socket.cancel()
    }

    private fun finish(reason: CloseReason) {
        if (!closedReason.complete(reason)) return
        log.info("gateway socket closed: $reason")
        val closed = GatewayClosedException("Connection closed: $reason")
        pending.values.forEach { it.completeExceptionally(closed) }
        inbound.close()
        if (!readyFrame.isCompleted) readyFrame.completeExceptionally(closed)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) = receive(text)

        // The gateway sends text, but a binary frame holding UTF-8 JSON is read the same way.
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = receive(bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            finish(CloseReason.Remote(code, reason))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            finish(CloseReason.Remote(code, reason))

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!opened.isCompleted) {
                opened.completeExceptionally(GatewayHandshakeException(handshakeFailure(t, response)))
            }
            val failure = (t as? IOException)?.toConnectionFailure() ?: ConnectionFailure.NetworkError(t.message)
            finish(CloseReason.Failure(failure))
        }
    }

    private fun receive(text: String) {
        lastInboundAtMs = clock()
        when (val frame = Inbound.parse(text)) {
            is Inbound.Response -> {
                val waiter = pending[frame.id] ?: return
                if (frame.error !=
                    null
                ) {
                    waiter.completeExceptionally(frame.error)
                } else {
                    waiter.complete(frame.result ?: JsonPrimitive(true))
                }
            }

            is Inbound.Event -> if (frame.event.type == "gateway.ready" && !readyFrame.isCompleted) {
                readyFrame.complete(parseReady(frame.event.payload))
            } else {
                inbound.trySend(frame)
            }

            is Inbound.Request -> inbound.trySend(frame)

            Inbound.Ignored -> Unit
        }
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000
        private const val PROTOCOL = "hermes-gateway-v1"
        private const val TICKET_PROTOCOL_PREFIX = "hermes-gateway-ticket."

        /**
         * Opens the socket with [ticket] and waits for `gateway.ready`. The ticket goes in the
         * `Sec-WebSocket-Protocol` header rather than the URL, so it never appears in proxy or
         * server access logs; Hermes accepts it there and answers with `hermes-gateway-v1` only.
         */
        suspend fun open(
            client: OkHttpClient,
            dashboard: DashboardUrl,
            ticket: String,
            openTimeoutMs: Long,
            log: GatewayLog = GatewayLog.None,
            clock: () -> Long = System::currentTimeMillis,
        ): GatewayConnection {
            val connection = GatewayConnection(log, clock)
            val request = Request.Builder()
                .url(dashboard.resolve("/api/ws"))
                .header("Sec-WebSocket-Protocol", "$PROTOCOL, $TICKET_PROTOCOL_PREFIX$ticket")
                // The push plugin counts turns from a socket marked so as a phone's, not Desktop's:
                // its reply-source mute and rich cards follow it (decided with the user, 2026-10-22).
                .header("X-Talaria-Client", "talaria")
                .build()
            connection.socket = client.newWebSocket(request, connection.listener)
            try {
                withTimeout(openTimeoutMs) {
                    connection.opened.await()
                    connection.ready = connection.readyFrame.await()
                }
            } catch (_: TimeoutCancellationException) {
                connection.close(CloseReason.Local)
                throw GatewayHandshakeException(HandshakeFailure.Timeout)
            } catch (e: GatewayClosedException) {
                throw GatewayHandshakeException(HandshakeFailure.Network(ConnectionFailure.NetworkError(e.message)))
            }
            log.info("gateway socket open, epoch=${connection.ready.replayEpoch}")
            return connection
        }

        private fun handshakeFailure(t: Throwable, response: Response?): HandshakeFailure {
            val status = response?.code ?: return HandshakeFailure.Network(
                (t as? IOException)?.toConnectionFailure() ?: ConnectionFailure.NetworkError(t.message),
            )
            return when (status) {
                401 -> HandshakeFailure.SignInRequired
                403 -> HandshakeFailure.Rejected(status)
                in 300..399 -> HandshakeFailure.SignInRequired
                502, 503, 504, 520, 521, 522, 523, 524, 530 -> HandshakeFailure.Unavailable(status)
                else -> HandshakeFailure.UnexpectedStatus(status)
            }
        }

        private fun parseReady(payload: JsonObject?) = GatewayReady(
            replayEpoch = (payload?.get("replay_epoch") as? JsonPrimitive)?.contentOrNull,
            changeEvents = (payload?.get("change_events") as? JsonPrimitive)?.booleanOrNull ?: false,
            heartbeat = (payload?.get("heartbeat") as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }
}

/** A call got no reply in time. The connection may still be fine. */
class RpcTimeoutException(val method: String, timeoutMs: Long, cause: Throwable) :
    Exception("$method: no reply within ${timeoutMs}ms", cause)
