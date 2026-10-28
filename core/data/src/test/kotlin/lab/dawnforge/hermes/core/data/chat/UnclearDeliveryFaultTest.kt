package lab.dawnforge.hermes.core.data.chat

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.chat.UnclearDelivery
import lab.dawnforge.hermes.core.data.TestStores
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.gateway.GatewayClient
import lab.dawnforge.hermes.core.gateway.GatewayPolicy
import lab.dawnforge.hermes.core.gateway.GatewayState
import lab.dawnforge.hermes.core.gateway.OkHttpGatewayConnector
import lab.dawnforge.hermes.core.gateway.TicketResult
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.DashboardApi
import lab.dawnforge.hermes.core.net.DashboardAuthClient
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.testing.fakegateway.FakeGateway
import okhttp3.OkHttpClient

/**
 * The unclear-delivery branch (stage-1 plan, M6) against a fault-injected gateway: a message's
 * reply is lost with the connection, and the reconnect decides, without sending it blindly again.
 * The chat screen runs the same steps: `send`, then on the reconnect `open` and
 * [UnclearDelivery.settle] with the stored history.
 */
class UnclearDeliveryFaultTest {
    private val policy = GatewayPolicy(
        openTimeoutMs = 3_000,
        // Shorter than giving up on a silent socket, so a silent Hermes times the call out.
        callTimeoutMs = 500,
        keepaliveAfterMs = 300,
        deadAfterMs = 900,
        backoffBaseMs = 50,
        foregroundBackoffCapMs = 200,
        backgroundBackoffCapMs = 200,
        minimumRetryDelayMs = 20,
    )

    private val gateway = FakeGateway().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = GatewayClient(
        scope = scope,
        connector = OkHttpGatewayConnector(
            OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build(),
            DashboardUrl("http", gateway.hostName, gateway.port, ""),
            policy,
        ),
        tickets = { TicketResult.Ticket(gateway.issueTicket()) },
        readiness = { null },
        policy = policy,
    )
    private val stores = TestStores()
    private val factory = DashboardClientFactory(DashboardClientFactory.defaultBaseClient())
    private val requests = DashboardRequests(
        DashboardApi(factory),
        stores.repository,
        stores.cookies,
        SignInService(DashboardAuthClient(factory), stores.repository, stores.cookies),
    )
    private val chats = ChatRepository(requests) { client }

    /** Each `prompt.submit` Hermes answered or dropped. */
    private val submits = AtomicInteger()

    @AfterTest
    fun tearDown() {
        runBlocking { client.stop() }
        scope.cancel()
        gateway.close()
    }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private suspend fun connect(): OpenedChat {
        client.start()
        withTimeout(5_000) { client.state.first { it is GatewayState.Connected } }
        return assertIs<NetResult.Success<OpenedChat>>(chats.open("default", STORED)).value
    }

    /** Waits for the client's next connection after the one that dropped, then reopens the chat. */
    private suspend fun reconnect(): OpenedChat {
        val deadline = System.currentTimeMillis() + 5_000
        while (gateway.upgradeRequests.size < 2 || client.state.value !is GatewayState.Connected) {
            check(System.currentTimeMillis() < deadline) { "No reconnect" }
            delay(20)
        }
        return assertIs<NetResult.Success<OpenedChat>>(chats.open("default", STORED)).value
    }

    private fun running(runtimeId: String, prompt: String) = gateway.updateSnapshot(
        runtimeId,
        buildJsonObject {
            put("running", true)
            put("inflight", buildJsonObject { put("user", prompt) })
        },
    )

    private fun settle(opened: OpenedChat) =
        UnclearDelivery.settle(TEXT, emptySet(), emptyList(), opened.inflightPrompt, opened.queuedPrompt)

    private fun mayHaveArrived(result: NetResult<*>) {
        val failure = assertIs<NetResult.Failure>(result).failure
        assertTrue(
            failure is ConnectionFailure.NetworkError || failure is ConnectionFailure.TimedOut,
            "A lost reply must read as maybe delivered, not failed: $failure",
        )
    }

    @Test
    fun `a message Hermes took before the connection dropped is not sent again`() = test {
        val opened = connect()
        gateway.handle("prompt.submit") { params ->
            submits.incrementAndGet()
            running(opened.runtimeId, params["text"]!!.jsonPrimitive.content)
            FakeGateway.Reply.Drop
        }

        mayHaveArrived(chats.send(opened.runtimeId, TEXT))

        assertEquals(UnclearDelivery.Received, settle(reconnect()))
        assertEquals(1, submits.get())
    }

    @Test
    fun `a message lost on the way is sent again, once`() = test {
        val opened = connect()
        gateway.handle("prompt.submit") {
            // Dropped before Hermes started a turn for it.
            if (submits.incrementAndGet() == 1) {
                FakeGateway.Reply.Drop
            } else {
                FakeGateway.Reply.Result(buildJsonObject { put("status", "streaming") })
            }
        }

        mayHaveArrived(chats.send(opened.runtimeId, TEXT))

        val reopened = reconnect()
        assertEquals(UnclearDelivery.Lost, settle(reopened))
        assertIs<NetResult.Success<Sent>>(chats.send(reopened.runtimeId, TEXT))
        assertEquals(2, submits.get())
    }

    @Test
    fun `a message to a Hermes gone silent is settled by the snapshot once it answers`() = test {
        val opened = connect()
        gateway.responsive = false
        val sent = scope.async { chats.send(opened.runtimeId, TEXT) }
        gateway.awaitCall("prompt.submit")
        running(opened.runtimeId, TEXT)

        mayHaveArrived(sent.await())
        gateway.responsive = true

        val reopened = assertIs<NetResult.Success<OpenedChat>>(
            withTimeout(5_000) {
                var result: NetResult<OpenedChat>
                do {
                    result = chats.open("default", STORED)
                    if (result !is NetResult.Success) delay(50)
                } while (result !is NetResult.Success)
                result
            },
        ).value
        assertEquals(UnclearDelivery.Received, settle(reopened))
        // Nothing went again on its own.
        assertFailsWith<IllegalStateException> { gateway.awaitCall("prompt.submit", timeoutMs = 300) }
    }

    private companion object {
        const val STORED = "20261024_120000_abcdef"
        const val TEXT = "What's on my calendar tomorrow?"
    }
}
