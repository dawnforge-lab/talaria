package lab.dawnforge.hermes.core.gateway

import java.io.File
import java.util.Collections
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.model.SignInMethod
import lab.dawnforge.hermes.core.net.AddressCheck
import lab.dawnforge.hermes.core.net.ConnectionUrlPolicy
import lab.dawnforge.hermes.core.net.DashboardAuthClient
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import lab.dawnforge.hermes.core.net.DashboardCookieJar
import lab.dawnforge.hermes.core.net.NetResult
import org.junit.Assume.assumeTrue

/**
 * Talks to a real Hermes dashboard. Skipped unless `-Phermes.live.file=<path>` names a properties
 * file with `url`, `username` and `password` (test credentials only; never commit one).
 *
 * Creates one hidden, empty session, so nothing shows in the dashboard's session lists and no
 * model is called.
 */
class LiveHermesTest {
    private val config: Properties? = System.getProperty("hermes.live.file")
        ?.let(::File)
        ?.takeIf(File::exists)
        ?.let { file -> Properties().apply { file.reader(Charsets.UTF_8).use(::load) } }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `connects, re-attaches after a reconnect and resumes a reaped session`() = runBlocking {
        assumeTrue("set -Phermes.live.file to run against a real Hermes", config != null)
        val config = config!!
        val url = (ConnectionUrlPolicy.check(config.getProperty("url")) as AddressCheck.Valid).candidates.first()
        val factory = DashboardClientFactory(DashboardClientFactory.defaultBaseClient())
        val auth = DashboardAuthClient(factory)

        val method = assertIs<SignInMethod.Password>((auth.discoverSignInMethod(url, null) as NetResult.Success).value)
        val jar = DashboardCookieJar()
        val login = auth.passwordLogin(
            url,
            jar,
            null,
            method.providerName,
            config.getProperty("username"),
            config.getProperty("password"),
        )
        assertIs<NetResult.Success<Unit>>(login, "password sign-in")

        val policy = GatewayPolicy()
        val client = GatewayClient(
            scope = scope,
            connector = OkHttpGatewayConnector(factory.createWebSocketClient(url) { null }, url, policy),
            tickets = {
                when (val ticket = auth.mintTicket(url, jar, null)) {
                    is NetResult.Success -> TicketResult.Ticket(ticket.value)
                    is NetResult.Failure -> TicketResult.Unavailable(ticket.failure)
                }
            },
            readiness = { auth.probe(url, null) },
            policy = policy,
        )
        val notices = Collections.synchronizedList(mutableListOf<GatewayNotice>())
        scope.launch { client.notices.collect { notices += it } }

        client.start()
        val connected = withTimeout(20_000) {
            client.state.first { it is GatewayState.Connected }
        } as GatewayState.Connected
        val epoch = assertNotNull(connected.ready.replayEpoch, "gateway.ready carries a replay epoch")
        println("live: connected, epoch=$epoch heartbeat=${connected.ready.heartbeat}")

        val capabilities = client.call("gateway.capabilities", JsonObject(emptyMap())) as JsonObject
        println("live: gateway.capabilities keys=${capabilities.keys}")
        assertEquals(JsonPrimitive(true), (client.call("gateway.ping", JsonObject(emptyMap())) as JsonObject)["ok"])

        val created = client.call(
            "session.create",
            buildJsonObject {
                put("cols", 96)
                put("source", "desktop")
                put("hidden", true)
            },
            timeoutMs = 60_000,
        ) as JsonObject
        val runtimeId = assertNotNull((created["session_id"] as? JsonPrimitive)?.contentOrNull)
        val storedId = (created["stored_session_id"] as? JsonPrimitive)?.contentOrNull
        println("live: created hidden session runtime=$runtimeId stored=${storedId != null}")
        client.trackSession(runtimeId, storedId)

        val since = client.call(
            "session.events.since",
            buildJsonObject {
                put("session_id", runtimeId)
                put("last_seen", 0)
            },
        ) as JsonObject
        assertEquals(JsonPrimitive(epoch), since["epoch"])
        assertIs<JsonArray>(since["events"])

        // A quick reconnect, inside Hermes' 20 s orphan grace: session.activate re-attaches it.
        client.stop()
        client.start()
        withTimeout(20_000) { client.state.first { it is GatewayState.Connected } }
        delay(1_000)
        assertTrue(notices.isEmpty(), "a quick reconnect needs no recovery, got $notices")
        println("live: quick reconnect re-attached the session with no gap")

        // A long absence: the idle session is reaped, so it comes back through session.resume.
        client.stop()
        println("live: waiting 30 s for Hermes to reap the idle session")
        delay(30_000)
        client.start()
        withTimeout(30_000) { client.state.first { it is GatewayState.Connected } }
        withTimeout(30_000) { while (notices.isEmpty()) delay(200) }
        println("live: after the reap: $notices")
        val gap = notices.filterIsInstance<GatewayNotice.ReplayGap>().first()
        assertEquals(GatewayNotice.GapReason.SessionLost, gap.reason)
        if (storedId != null) {
            val rebound = assertIs<GatewayNotice.SessionRebound>(notices.first())
            assertEquals(runtimeId, rebound.previousId)
            runCatching { client.call("session.close", buildJsonObject { put("session_id", rebound.sessionId) }) }
        }
        client.stop()
    }
}
