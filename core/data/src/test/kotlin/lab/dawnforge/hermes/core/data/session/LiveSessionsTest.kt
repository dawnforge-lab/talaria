package lab.dawnforge.hermes.core.data.session

import java.io.File
import java.util.Properties
import java.util.UUID
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import lab.dawnforge.hermes.core.data.LiveHermes
import lab.dawnforge.hermes.core.data.TestStores
import lab.dawnforge.hermes.core.data.signin.SignInResult
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.gateway.GatewayClient
import lab.dawnforge.hermes.core.gateway.GatewayPolicy
import lab.dawnforge.hermes.core.gateway.GatewayState
import lab.dawnforge.hermes.core.gateway.OkHttpGatewayConnector
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.model.SessionSearchHit
import lab.dawnforge.hermes.core.model.SessionSummary
import lab.dawnforge.hermes.core.model.SignInMethod
import lab.dawnforge.hermes.core.net.AddressCheck
import lab.dawnforge.hermes.core.net.ConnectionUrlPolicy
import lab.dawnforge.hermes.core.net.DashboardApi
import lab.dawnforge.hermes.core.net.DashboardAuthClient
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import lab.dawnforge.hermes.core.net.NetResult
import org.junit.Assume.assumeTrue

/**
 * The session list against a real Hermes dashboard, through the app's own repositories. Skipped
 * unless `-Phermes.live.file=<path>` names a properties file with `url`, `username`, `password`.
 *
 * It touches only chats it creates itself, seeded with two short messages so no model is called.
 */
class LiveSessionsTest {
    private val config = LiveHermes.config

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() = scope.cancel()

    private suspend fun connect() = LiveHermes.connect(scope)

    private suspend fun seed(gateway: GatewayClient, title: String, marker: String) =
        LiveHermes.seed(gateway, title, marker)

    @Test
    fun `lists, searches and changes a chat of its own`() = runBlocking {
        assumeTrue("set -Phermes.live.file to run against a real Hermes", config != null)
        val live = connect()
        val id = live.id
        val gateway = live.gateway
        val sessions = live.sessions
        val profiles = live.profiles

        val list = assertIs<NetResult.Success<List<Profile>>>(profiles.profiles(id)).value
        println("live: profiles ${list.map { "${it.name}(${it.label})" }}")
        val first = assertIs<NetResult.Success<SessionPage>>(sessions.page(id, Profile.DEFAULT, false, 0, 50)).value
        println("live: first page has ${first.rows.size} chats, total=${first.total}")

        val marker = "androidcheck" + UUID.randomUUID().toString().take(6)
        val storedId =
            assertNotNull(
                (
                    seed(
                        gateway,
                        "Android client test $marker",
                        marker,
                    )["stored_session_id"] as? JsonPrimitive
                    )?.contentOrNull,
            )
        println("live: created test chat $storedId")

        suspend fun find(archived: Boolean): SessionSummary? {
            repeat(10) {
                val page = (sessions.page(id, Profile.DEFAULT, archived, 0, 200) as NetResult.Success).value
                page.rows.firstOrNull { it.id == storedId }?.let { return it }
                delay(500)
            }
            return null
        }

        try {
            val listed = assertNotNull(find(archived = false), "the seeded chat is in the list")
            assertEquals("Android client test $marker", listed.title)
            assertTrue(listed.messageCount >= 1)

            val hits = assertIs<NetResult.Success<List<SessionSearchHit>>>(
                sessions.search(id, Profile.DEFAULT, marker),
            ).value
            assertTrue(hits.any { it.sessionId == storedId }, "search finds it by a word in its messages")
            println("live: search found it; snippet=${hits.first { it.sessionId == storedId }.snippet}")

            assertEquals(
                NetResult.Success("Renamed $marker"),
                sessions.rename(id, Profile.DEFAULT, storedId, "Renamed $marker"),
            )
            assertEquals("Renamed $marker", find(archived = false)?.title)

            assertIs<NetResult.Success<Unit>>(sessions.setPinned(id, Profile.DEFAULT, storedId, pinned = true))
            assertEquals(true, find(archived = false)?.pinned)

            assertIs<NetResult.Success<Unit>>(sessions.setArchived(id, Profile.DEFAULT, storedId, archived = true))
            val archivedRow = assertNotNull(find(archived = true), "archived chats are listed in the archive")
            assertEquals(false, archivedRow.pinned, "archiving unpinned it")

            assertIs<NetResult.Success<Unit>>(sessions.setArchived(id, Profile.DEFAULT, storedId, archived = false))
            assertNotNull(find(archived = false), "restored")
            println("live: rename, pin, archive and restore all show in the list")
        } finally {
            assertIs<NetResult.Success<Unit>>(sessions.delete(id, Profile.DEFAULT, storedId))
            val page = (sessions.page(id, Profile.DEFAULT, false, 0, 200) as NetResult.Success).value
            assertTrue(page.rows.none { it.id == storedId }, "deleted")
            println("live: deleted the test chat")
            gateway.stop()
        }
    }

    /**
     * For checking the app's screens by hand: with `-Phermes.live.seed=N` it leaves N throwaway
     * chats titled "Android client test …" for the app to show (and delete).
     */
    @Test
    fun `seeds chats for a manual check`() = runBlocking {
        val count = System.getProperty("hermes.live.seed")?.toIntOrNull()
        assumeTrue("set -Phermes.live.seed=N to leave N test chats", config != null && count != null)
        val live = connect()
        repeat(count!!) { n ->
            val marker = "androidcheck" + UUID.randomUUID().toString().take(6)
            val created = seed(live.gateway, "Android client test ${n + 1}", marker)
            println("live: seeded ${(created["stored_session_id"] as? JsonPrimitive)?.contentOrNull} ($marker)")
        }
        live.gateway.stop()
    }
}
