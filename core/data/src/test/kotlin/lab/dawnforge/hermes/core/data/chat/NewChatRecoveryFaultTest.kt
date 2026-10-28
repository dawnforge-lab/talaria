package lab.dawnforge.hermes.core.data.chat

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.chat.NewChatRecovery
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
 * A chat started on the phone whose connection drops before Hermes stored it, as on a phone that
 * cuts an app's network seconds after it leaves the screen. The chat screen runs these steps: on the
 * reconnect it opens the chat by the stored id Hermes gave it, and [NewChatRecovery] decides.
 */
class NewChatRecoveryFaultTest {
    private val policy = GatewayPolicy(
        openTimeoutMs = 3_000,
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

    /** Each `prompt.submit` Hermes took, by the session it went to. */
    private val submits = AtomicInteger()

    @AfterTest
    fun tearDown() {
        runBlocking { client.stop() }
        scope.cancel()
        gateway.close()
    }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    /** Connects, and starts a chat as the New chat button does. */
    private suspend fun startChat(): OpenedChat {
        client.start()
        withTimeout(5_000) { client.state.first { it is GatewayState.Connected } }
        return assertIs<NetResult.Success<OpenedChat>>(chats.startAgain("default", null)).value
    }

    /** Drops every connection and waits for the client's next one. */
    private suspend fun dropAndReconnect() {
        val before = gateway.upgradeRequests.size
        gateway.dropConnections()
        val deadline = System.currentTimeMillis() + 5_000
        while (gateway.upgradeRequests.size <= before || client.state.value !is GatewayState.Connected) {
            check(System.currentTimeMillis() < deadline) { "No reconnect" }
            delay(20)
        }
    }

    private fun acceptPrompts() = gateway.handle("prompt.submit") { params ->
        submits.incrementAndGet()
        // Hermes stores a chat with its first message.
        gateway.markStored(gateway.storedIdOf(params["session_id"]!!.jsonPrimitive.content))
        FakeGateway.Reply.Result(buildJsonObject { put("status", "streaming") })
    }

    @Test
    fun `a new chat Hermes let go before its first message starts again, and the message goes there`() = test {
        val started = startChat()
        val reserved = assertNotNull(started.storedId, "Hermes gives a new chat its stored id")
        // The phone went quiet before the message went: Hermes lets the unstored chat go.
        gateway.reap(started.runtimeId)
        dropAndReconnect()

        val failure = assertIs<NetResult.Failure>(chats.open("default", reserved)).failure
        assertIs<ConnectionFailure.NotFound>(failure)
        assertTrue(NewChatRecovery.startsAgain(startedHere = true, everStored = false, failure = failure))

        val again = assertIs<NetResult.Success<OpenedChat>>(chats.startAgain("default", null)).value
        assertNotEquals(started.runtimeId, again.runtimeId)
        assertEquals(2, gateway.createdChats.get())
        acceptPrompts()
        assertIs<NetResult.Success<Sent>>(chats.send(again.runtimeId, TEXT))
        assertEquals(1, submits.get())
    }

    @Test
    fun `a new chat whose first message Hermes stored is reopened, not started again`() = test {
        val started = startChat()
        val reserved = assertNotNull(started.storedId)
        acceptPrompts()
        assertIs<NetResult.Success<Sent>>(chats.send(started.runtimeId, TEXT))
        gateway.reap(started.runtimeId)
        dropAndReconnect()

        val reopened = assertIs<NetResult.Success<OpenedChat>>(chats.open("default", reserved)).value
        assertEquals(reserved, reopened.storedId)
        assertEquals(1, gateway.createdChats.get())
        // Had it failed, a chat with a stored message would still not start again.
        assertFalse(
            NewChatRecovery.startsAgain(true, everStored = true, ConnectionFailure.NotFound("session not found")),
        )
    }

    @Test
    fun `a new chat still live after the drop is simply reopened`() = test {
        val started = startChat()
        val reserved = assertNotNull(started.storedId)
        dropAndReconnect()

        val reopened = assertIs<NetResult.Success<OpenedChat>>(chats.open("default", reserved)).value
        assertEquals(started.runtimeId, reopened.runtimeId)
        assertEquals(1, gateway.createdChats.get())
    }

    private companion object {
        const val TEXT = "Push test: count slowly from one to twenty in words."
    }
}
