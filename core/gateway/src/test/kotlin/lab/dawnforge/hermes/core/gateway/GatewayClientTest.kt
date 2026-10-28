package lab.dawnforge.hermes.core.gateway

import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.testing.fakegateway.FakeGateway
import okhttp3.OkHttpClient

class GatewayClientTest {
    private val fast = GatewayPolicy(
        openTimeoutMs = 3_000,
        callTimeoutMs = 3_000,
        keepaliveAfterMs = 300,
        deadAfterMs = 900,
        backoffBaseMs = 50,
        foregroundBackoffCapMs = 200,
        backgroundBackoffCapMs = 200,
        minimumRetryDelayMs = 20,
    )

    private var gateway = FakeGateway().apply { start() }
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val minted = AtomicInteger()
    private val clients = mutableListOf<GatewayClient>()

    @Volatile
    private var tickets: () -> TicketResult = { TicketResult.Ticket(gateway.issueTicket()) }

    @Volatile
    private var up = true

    private fun url() = DashboardUrl("http", gateway.hostName, gateway.port, "")

    private fun client(policy: GatewayPolicy = fast, profile: String? = null) = GatewayClient(
        scope = scope,
        connector = OkHttpGatewayConnector(http, url(), policy),
        tickets = {
            minted.incrementAndGet()
            tickets()
        },
        readiness = { if (up) null else ConnectionFailure.ConnectionRefused },
        policy = policy,
        profile = profile,
    ).also { clients += it }

    @AfterTest
    fun tearDown() {
        runBlocking { clients.forEach { it.stop() } }
        scope.cancel()
        gateway.close()
    }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private suspend fun GatewayClient.awaitConnected(): GatewayState.Connected =
        withTimeout(5_000) { state.first { it is GatewayState.Connected } as GatewayState.Connected }

    private suspend fun GatewayClient.awaitState(predicate: (GatewayState) -> Boolean): GatewayState =
        withTimeout(5_000) { state.first(predicate) }

    private suspend fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            delay(20)
        }
    }

    private fun <T> GatewayClient.collect(flow: kotlinx.coroutines.flow.Flow<T>): MutableList<T> {
        val items = Collections.synchronizedList(mutableListOf<T>())
        scope.launch { flow.collect { items += it } }
        return items
    }

    private fun JsonObject.params(): JsonObject = this["params"]!!.jsonObject

    // ---- Connecting ----------------------------------------------------------------------------

    @Test
    fun `connects with the ticket in the subprotocol and announces server requests`() = test {
        val client = client()
        client.start()
        val connected = client.awaitConnected()

        assertEquals(gateway.replayEpoch, connected.ready.replayEpoch)
        assertTrue(connected.ready.heartbeat)
        val capabilities = gateway.awaitCall("client.capabilities")
        assertEquals(JsonPrimitive(true), capabilities.params()["server_requests"])

        val upgrade = gateway.upgradeRequests.single()
        val protocols = upgrade.headers["Sec-WebSocket-Protocol"]!!.split(',').map(String::trim)
        assertEquals("hermes-gateway-v1", protocols[0])
        assertTrue(protocols[1].startsWith("hermes-gateway-ticket.t-"))
        assertNull(upgrade.url.queryParameter("ticket"), "the ticket must stay out of the URL")
        assertEquals("/api/ws", upgrade.url.encodedPath)
    }

    @Test
    fun `calls return results and errors`() = test {
        gateway.handle("echo") { FakeGateway.Reply.Result(it) }
        val client = client()
        client.start()
        client.awaitConnected()

        val params = buildJsonObject { put("a", 1) }
        assertEquals(params, client.call("echo", params))
        val error = assertFailsWith<RpcException> { client.call("no.such.method") }
        assertEquals(-32601, error.code)
    }

    @Test
    fun `calls fail fast when not connected`() = test {
        assertFailsWith<GatewayClosedException> { client().call("gateway.ping") }
    }

    // ---- Reconnecting --------------------------------------------------------------------------

    @Test
    fun `a dropped connection reconnects with a fresh ticket`() = test {
        val client = client()
        client.start()
        client.awaitConnected()

        gateway.dropConnections()
        client.awaitState { it is GatewayState.Waiting }
        client.awaitConnected()
        assertEquals(2, minted.get())
        assertEquals(2, gateway.upgradeRequests.size)
    }

    @Test
    fun `a rejected ticket is retried with a new one`() = test {
        var first = true
        tickets = {
            if (first) {
                first = false
                TicketResult.Ticket("spent")
            } else {
                TicketResult.Ticket(gateway.issueTicket())
            }
        }
        val client = client()
        client.start()
        val waiting = client.awaitState { it is GatewayState.Waiting } as GatewayState.Waiting
        assertEquals(GatewayFailure.Handshake(HandshakeFailure.Rejected(403)), waiting.failure)
        client.awaitConnected()
        assertEquals(2, minted.get())
    }

    @Test
    fun `sign-in required stops until told to reconnect`() = test {
        tickets = { TicketResult.SignInRequired(ConnectionFailure.SignInRequired) }
        val client = client()
        client.start()
        client.awaitState { it is GatewayState.SignInRequired }
        delay(300)
        assertEquals(1, minted.get(), "no retries while signed out")
        assertTrue(gateway.upgradeRequests.isEmpty())

        tickets = { TicketResult.Ticket(gateway.issueTicket()) }
        client.reconnectNow()
        client.awaitConnected()
    }

    @Test
    fun `an unreachable dashboard spends no tickets`() = test {
        val client = client()
        client.start()
        client.awaitConnected()

        up = false
        gateway.dropConnections()
        client.awaitState {
            (it as? GatewayState.Waiting)?.failure == GatewayFailure.NotReachable(ConnectionFailure.ConnectionRefused)
        }
        delay(300)
        assertEquals(1, minted.get())

        up = true
        client.awaitConnected()
        assertEquals(2, minted.get())
    }

    @Test
    fun `waking the client restarts the backoff from its first step`() = test {
        val client = client(fast.copy(foregroundBackoffCapMs = 5_000, backgroundBackoffCapMs = 5_000))
        tickets = { TicketResult.Unavailable(ConnectionFailure.ConnectionRefused) }
        up = false
        client.start()
        client.awaitState { (it as? GatewayState.Waiting)?.attempt?.let { attempt -> attempt >= 5 } == true }

        client.reconnectNow()
        // The wake-up resets the count to 1; the probe still fails, so the next wait is attempt 2.
        val next = client.awaitState { it is GatewayState.Waiting && it.attempt <= 2 } as GatewayState.Waiting
        assertEquals(2, next.attempt)
    }

    @Test
    fun `a silent socket is pinged, then given up and replaced`() = test {
        val client = client()
        client.start()
        client.awaitConnected()

        gateway.responsive = false
        assertTrue(gateway.awaitCall("gateway.ping", timeoutMs = 2_000).params().isEmpty())
        val waiting = client.awaitState { it is GatewayState.Waiting } as GatewayState.Waiting
        assertEquals(GatewayFailure.Lost(CloseReason.KeepaliveTimeout), waiting.failure)

        gateway.responsive = true
        client.awaitConnected()
    }

    @Test
    fun `stop closes the socket and stays stopped`() = test {
        val client = client()
        client.start()
        client.awaitConnected()
        client.stop()
        assertEquals(GatewayState.Stopped, client.state.value)
        eventually("socket closed") { gateway.connectedSockets == 0 }
    }

    // ---- Replay --------------------------------------------------------------------------------

    @Test
    fun `missed events are replayed once, in order, after a reconnect`() = test {
        val client = client(fast.copy(backoffBaseMs = 400, foregroundBackoffCapMs = 400, minimumRetryDelayMs = 400))
        val events = client.collect(client.events)
        client.start()
        client.awaitConnected()
        val session = gateway.createSession("stored-1")
        client.attachSession(session, "stored-1")

        gateway.pushEvent(session, "message.delta", buildJsonObject { put("text", "a") })
        gateway.pushEvent(session, "message.delta", buildJsonObject { put("text", "b") })
        eventually("live events") { events.size == 2 }

        gateway.dropConnections()
        gateway.pushEvent(session, "message.delta", buildJsonObject { put("text", "c") })
        gateway.pushEvent(session, "message.complete")
        client.awaitState { it is GatewayState.Waiting }
        client.awaitConnected()
        gateway.pushEvent(session, "message.start")

        eventually("replayed and live events") { events.size == 5 }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), events.map { it.seq })
        assertEquals(listOf(false, false, true, true, false), events.map { it.replayed })
        val since = gateway.awaitCall("session.events.since")
        assertEquals(2L, since.params()["last_seen"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a reaped session is resumed by its stored id`() = test {
        val client = client()
        val notices = client.collect(client.notices)
        val events = client.collect(client.events)
        client.start()
        client.awaitConnected()
        val first = gateway.createSession("stored-9")
        client.attachSession(first, "stored-9")

        gateway.dropConnections()
        gateway.reap(first)
        client.awaitState { it is GatewayState.Waiting }
        client.awaitConnected()

        eventually("notices") { notices.size == 2 }
        val rebound = assertIs<GatewayNotice.SessionRebound>(notices[0])
        assertEquals(first, rebound.previousId)
        assertEquals(GatewayNotice.ReplayGap(rebound.sessionId, GatewayNotice.GapReason.SessionLost), notices[1])

        gateway.pushEvent(rebound.sessionId, "message.start")
        eventually("event on the new id") { events.any { it.sessionId == rebound.sessionId } }
    }

    @Test
    fun `a gateway restart is reported and sessions are resumed`() = test {
        val client = client()
        val notices = client.collect(client.notices)
        client.start()
        val before = client.awaitConnected().ready.replayEpoch
        val session = gateway.createSession("stored-2")
        client.attachSession(session, "stored-2")

        gateway.restart()
        client.awaitState { it is GatewayState.Waiting }
        val after = client.awaitConnected().ready.replayEpoch
        assertTrue(before != after)

        eventually("notices") { notices.size == 2 }
        val rebound = assertIs<GatewayNotice.SessionRebound>(notices[0])
        assertEquals(GatewayNotice.ReplayGap(rebound.sessionId, GatewayNotice.GapReason.GatewayRestarted), notices[1])
        gateway.awaitCall("session.resume")
    }

    @Test
    fun `a truncated replay is reported`() = test {
        gateway.close()
        gateway = FakeGateway(ringSize = 2).apply { start() }
        val client = client(fast.copy(backoffBaseMs = 400, foregroundBackoffCapMs = 400, minimumRetryDelayMs = 400))
        val events = client.collect(client.events)
        val notices = client.collect(client.notices)
        client.start()
        client.awaitConnected()
        val session = gateway.createSession("stored-3")
        client.attachSession(session, "stored-3")
        gateway.pushEvent(session, "e1")
        eventually("live event") { events.size == 1 }

        gateway.dropConnections()
        listOf("e2", "e3", "e4").forEach { gateway.pushEvent(session, it) }
        client.awaitState { it is GatewayState.Waiting }
        client.awaitConnected()

        eventually("replay") { events.size == 3 && notices.isNotEmpty() }
        assertEquals(listOf("e1", "e3", "e4"), events.map { it.type })
        assertEquals(GatewayNotice.ReplayGap(session, GatewayNotice.GapReason.Truncated), notices.single())
    }

    // ---- Server requests -----------------------------------------------------------------------

    @Test
    fun `server requests are delivered and answered`() = test {
        val client = client()
        val requests = client.collect(client.serverRequests)
        client.start()
        client.awaitConnected()
        val session = gateway.createSession("stored-4")
        client.attachSession(session, "stored-4")

        val id = gateway.sendServerRequest(session, "approval", buildJsonObject { put("command", "rm -rf build") })
        eventually("request") { requests.isNotEmpty() }
        val request = requests.single()
        assertEquals(id, request.id)
        assertEquals(session, request.sessionId)
        assertFalse(request.replayed)
        assertEquals(setOf(id), client.pendingRequests.value.keys, "waiting for an answer")

        assertTrue(client.respond(request, buildJsonObject { put("choice", "once") }))
        eventually("answer") { gateway.answers.containsKey(id) }
        assertEquals("once", gateway.answers[id]!!["result"]!!.jsonObject["choice"]!!.jsonPrimitive.content)
        assertTrue(client.pendingRequests.value.isEmpty(), "answered")
    }

    @Test
    fun `a request Hermes withdraws is no longer pending`() = test {
        val client = client()
        client.start()
        client.awaitConnected()
        val session = gateway.createSession("stored-7")
        client.attachSession(session, "stored-7")

        val id = gateway.sendServerRequest(session, "clarify")
        eventually("pending") { id in client.pendingRequests.value }
        gateway.cancelServerRequest(session, id, "resolved")
        eventually("withdrawn") { client.pendingRequests.value.isEmpty() }
    }

    @Test
    fun `desktop-window requests are declined with 4404`() = test {
        val client = client()
        val requests = client.collect(client.serverRequests)
        client.start()
        client.awaitConnected()
        val session = gateway.createSession("stored-5")
        client.attachSession(session, "stored-5")

        val id = gateway.sendServerRequest(session, "preview.read")
        eventually("decline") { gateway.answers.containsKey(id) }
        assertEquals(4404, gateway.answers[id]!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `requests opened while disconnected are delivered once after reconnecting`() = test {
        val client = client(fast.copy(backoffBaseMs = 400, foregroundBackoffCapMs = 400, minimumRetryDelayMs = 400))
        val requests = client.collect(client.serverRequests)
        client.start()
        client.awaitConnected()
        val session = gateway.createSession("stored-6")
        client.attachSession(session, "stored-6")

        gateway.dropConnections()
        val id = gateway.sendServerRequest(session, "clarify")
        client.awaitState { it is GatewayState.Waiting }
        client.awaitConnected()

        eventually("replayed request") { requests.isNotEmpty() }
        delay(200)
        assertEquals(listOf(id), requests.map { it.id }, "activate and events.since both list it; deliver once")
        assertTrue(requests.single().replayed)
    }

    // ---- Profiles ------------------------------------------------------------------------------

    @Test
    fun `session calls carry the profile, gateway calls do not`() = test {
        gateway.handle("session.title") { FakeGateway.Reply.Result(JsonObject(emptyMap())) }
        val client = client(profile = "work")
        client.start()
        client.awaitConnected()

        client.call("session.title", buildJsonObject { put("session_id", "s1") })
        assertEquals("work", gateway.awaitCall("session.title").params()["profile"]!!.jsonPrimitive.content)
        client.call("gateway.ping", JsonObject(emptyMap()))
        assertTrue(gateway.awaitCall("gateway.ping").params().isEmpty())
    }
}
