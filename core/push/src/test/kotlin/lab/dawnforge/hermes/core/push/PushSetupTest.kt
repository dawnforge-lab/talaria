package lab.dawnforge.hermes.core.push

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.core.push.e2e.NotificationE2E
import lab.dawnforge.hermes.core.push.relay.RelayClient
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Turning notifications on, against a relay that follows the fork's protocol and a plugin that
 * answers as `dashboard/plugin_api.py` does. The relay's nonce comes back through the inbox, as
 * it would through FCM.
 */
class PushSetupTest {
    private val server = MockWebServer()
    private val relayRequests = CopyOnWriteArrayList<RecordedRequest>()
    private val challenges = TokenChallengeInbox()
    private val store = testPushStore()
    private val tokens = FakeTokens()
    private var pairedInstallation = ""
    private val plugin = FakePlugin { pairedInstallation }
    private val dashboard = DashboardId("3f1c3c1e-5b2a-4c1e-9f1a-0a1b2c3d4e5f")
    private var registrations = 0
    private var forgetPhoneOnce = false
    private lateinit var setup: PushSetup

    @BeforeTest
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                relayRequests += request
                val path = request.url.encodedPath
                return when {
                    path == "/relay/v1/token-challenges" -> {
                        challenges.offer("nonce-for-" + request.json()["device_token"]!!.jsonPrimitive.content.take(12))
                        json(202, """{"challenge_sent":true,"expires_in":600}""")
                    }

                    path == "/relay/v1/installations" && request.method == "POST" -> {
                        registrations += 1
                        json(
                            201,
                            """{"installation":{"id":"inst-$registrations"},"credential":"inst-$registrations.secret"}""",
                        )
                    }

                    path.endsWith("/pairings") && forgetPhoneOnce -> {
                        forgetPhoneOnce = false
                        json(401, """{"error":"unauthorized"}""")
                    }

                    path.endsWith("/pairings") -> {
                        pairedInstallation = request.url.pathSegments[3]
                        json(201, """{"pairing_code":"ABCDE-FGHJK","expires_at":"soon"}""")
                    }

                    request.method == "PUT" || request.method == "DELETE" -> json(200, "{}")

                    else -> json(404, """{"error":"not_found"}""")
                }
            }
        }
        server.start()
        val config =
            PushConfig(
                "lab.dawnforge.hermes.debug",
                defaultRelayUrl = server.url("/relay/").toString(),
                firebase = null,
            )
        // The app only takes https relays; this one is local.
        setup =
            PushSetup(store, RelayClient(OkHttpClient()), tokens, challenges, plugin, config, { it.toHttpUrlOrNull() })
    }

    @AfterTest
    fun stop() = server.close()

    @Test
    fun `turning notifications on registers, proves the token, pairs and hands the plugin a fresh key`() = runBlocking {
        val pairing = assertIs<PushResult.Done<PushStore.Pairing>>(setup.enable(dashboard, "work")).value

        val register = relayRequests.single { it.url.encodedPath == "/relay/v1/installations" }.json()
        assertEquals("fcm", register.text("platform"))
        assertEquals("lab.dawnforge.hermes.debug", register.text("bundle_id"))
        assertEquals(tokens.current, register.text("device_token"))
        assertEquals("nonce-for-" + tokens.current.take(12), register.text("token_proof"))

        val pairingCall = relayRequests.single { it.url.encodedPath.endsWith("/pairings") }
        assertEquals("Bearer inst-1.secret", pairingCall.headers["Authorization"])
        assertEquals(dashboard.value, pairingCall.json().text("dashboard_id"))

        val claimed = plugin.posts.single { it.first == "pair" }.second
        assertEquals("ABCDE-FGHJK", claimed.text("pairing_code"))
        assertEquals(server.url("/relay").toString(), claimed.text("relay_url"))

        val key = plugin.posts.single { it.first == "e2e" }.second
        assertEquals("inst-1", key.text("installation_id"))
        assertEquals("gw-1", key.text("gateway_id"))
        assertEquals(pairing.kid, key.text("kid"))
        assertTrue(NotificationE2E.validKid(pairing.kid))
        assertContentEquals(NotificationE2E.unb64u(key.text("secret")!!), store.secret("gw-1"))
        assertEquals(plugin.profiles.toSet(), setOf("work"))

        assertEquals(
            PushStore.Pairing(dashboard, "work", "gw-1", "home Hermes (work)", pairing.kid),
            store.pairing("gw-1"),
        )
        assertEquals("inst-1", store.current().installationId)
        assertEquals(
            ProfilePush(pairedHere = true, pairedElsewhere = false),
            (setup.check(dashboard, "work") as PushResult.Done).value,
        )
    }

    @Test
    fun `the push id is never asked for on the caller's thread, which from a screen is the main thread`() {
        // On the Pixel, turning notifications on for a second profile failed with Firebase's
        // "Must not be called on the main application thread" (2026-10-25).
        val main = Executors.newSingleThreadExecutor { Thread(it, "pretend-main") }.asCoroutineDispatcher()
        main.use {
            runBlocking(main) {
                assertIs<PushResult.Done<*>>(setup.enable(dashboard, "work"))
                assertIs<PushResult.Done<*>>(setup.enable(dashboard, "default"))
                setup.syncToken()
                setup.disable(dashboard, "work")
                setup.disable(dashboard, "default")
            }
        }

        assertTrue(tokens.deleted == 1 && tokens.threads.size >= 4, "calls: ${tokens.threads}")
        // A debug build of coroutines appends " @coroutine#n" to the thread's name.
        assertTrue(
            tokens.threads.none {
                it.startsWith("pretend-main")
            },
            "Asked Firebase on the main thread: ${tokens.threads}",
        )
    }

    @Test
    fun `a second profile reuses the installation, and a relay that forgot the phone gets a new one`() = runBlocking {
        assertIs<PushResult.Done<*>>(setup.enable(dashboard, "work"))
        assertIs<PushResult.Done<*>>(setup.enable(dashboard, "default"))
        assertEquals(1, registrations)

        forgetPhoneOnce = true
        assertIs<PushResult.Done<*>>(setup.enable(dashboard, "default"))
        assertEquals(2, registrations)
        assertEquals("inst-2", store.current().installationId)
    }

    @Test
    fun `a plugin that is missing or can't encrypt is reported, and a refused key leaves nothing behind`() =
        runBlocking {
            plugin.capabilities = null
            assertEquals(PushResult.Failed(PushProblem.PluginMissing), setup.enable(dashboard, "work"))
            plugin.capabilities = listOf("pairing")
            assertEquals(PushResult.Failed(PushProblem.PluginMissing), setup.enable(dashboard, "work"))

            plugin.capabilities = listOf("pairing", "e2e-notifications")
            plugin.refuseKey = true
            assertIs<PushResult.Failed>(setup.enable(dashboard, "work"))
            assertNull(store.pairing("gw-1"))
            assertNull(store.secret("gw-1"))
        }

    @Test
    fun `without Firebase or a relay there is nothing to set up`() = runBlocking {
        tokens.available = false
        assertEquals(PushResult.Failed(PushProblem.NoFirebase), setup.enable(dashboard, "work"))
        tokens.available = true
        val noRelay = PushSetup(
            store,
            RelayClient(OkHttpClient()),
            tokens,
            challenges,
            plugin,
            PushConfig("lab.dawnforge.hermes", defaultRelayUrl = "http://not-https.example", firebase = null),
        )
        assertEquals(PushResult.Failed(PushProblem.NoRelay), noRelay.enable(dashboard, "work"))
    }

    @Test
    fun `a profile paired to another device says so`() = runBlocking {
        plugin.status =
            """{"ok":true,"paired":true,"installation_id":"someone-else","gateway_id":"gw-9","e2e":{"kid":"x"}}"""
        assertEquals(
            ProfilePush(pairedHere = false, pairedElsewhere = true),
            (setup.check(dashboard, "work") as PushResult.Done).value,
        )
        // The relay already refused that device's pairing: it is notified no more.
        plugin.status = """{"ok":true,"paired":true,"installation_id":"someone-else","relay_rejected":true}"""
        assertEquals(
            ProfilePush(pairedHere = false, pairedElsewhere = false),
            (setup.check(dashboard, "work") as PushResult.Done).value,
        )
        plugin.status = """{"ok":true,"paired":false,"crypto":true}"""
        assertEquals(
            ProfilePush(pairedHere = false, pairedElsewhere = false),
            (setup.check(dashboard, "work") as PushResult.Done).value,
        )
    }

    @Test
    fun `turning the last pairing off ends the installation, and signing out forgets the dashboard's pairings`() =
        runBlocking {
            setup.enable(dashboard, "work")
            setup.enable(dashboard, "default")
            setup.disable(dashboard, "work")
            assertEquals(listOf("default"), store.current().pairings.map { it.profile })
            assertTrue(relayRequests.none { it.method == "DELETE" })

            setup.forget(dashboard)
            val deleted = relayRequests.single { it.method == "DELETE" }
            assertEquals("/relay/v1/installations/inst-1", deleted.url.encodedPath)
            assertEquals("Bearer inst-1.secret", deleted.headers["Authorization"])
            assertNull(store.current().installationId)
            assertNull(store.installation())
            assertEquals(1, tokens.deleted)
            // The plugin names the deleted installation until its next delivery is refused: no one is notified.
            assertEquals(
                ProfilePush(pairedHere = false, pairedElsewhere = false),
                (setup.check(dashboard, "work") as PushResult.Done).value,
            )
        }

    @Test
    fun `each profile has its own preferences, kept when it pairs again`() = runBlocking {
        setup.enable(dashboard, "work")
        val quiet = PushStore.NotifyPreferences(muted = setOf("response.ready"), hideText = true)
        setup.setPreferences(dashboard, "work", quiet)
        assertEquals(quiet, store.pairingFor(dashboard, "work")?.preferences)

        setup.enable(dashboard, "default")
        assertEquals(PushStore.NotifyPreferences(), store.pairingFor(dashboard, "default")?.preferences)

        // Moved here again (a new gateway id): the profile's choices stay.
        setup.enable(dashboard, "work")
        assertEquals(quiet, store.pairingFor(dashboard, "work")?.preferences)
    }

    @Test
    fun `two dashboards notify the phone, and forgetting one leaves the other`() = runBlocking {
        val other = DashboardId("9a1b2c3d-0000-4000-8000-000000000001")
        setup.enable(dashboard, "work")
        setup.enable(other, "default")
        assertEquals(1, registrations, "one installation for the phone")
        assertEquals(setOf(dashboard, other), store.current().pairings.map { it.dashboardId }.toSet())

        setup.forget(dashboard)
        assertEquals(listOf(other), store.current().pairings.map { it.dashboardId })
        assertTrue(relayRequests.none { it.method == "DELETE" }, "the other dashboard still needs the installation")
        assertEquals("inst-1", store.current().installationId)
        val kept = store.current().pairings.single()
        assertTrue(store.secret(kept.gatewayId) != null, "the other pairing keeps its key")
    }

    @Test
    fun `a rotated token is moved at the relay with its own proof`() = runBlocking {
        setup.enable(dashboard, "work")
        tokens.current = "fcmToken_2:APA91b" + "b".repeat(140)
        setup.onNewToken(tokens.current)
        val moved = relayRequests.single { it.method == "PUT" }
        assertEquals("/relay/v1/installations/inst-1", moved.url.encodedPath)
        assertEquals(tokens.current, moved.json().text("device_token"))
        assertEquals("nonce-for-" + tokens.current.take(12), moved.json().text("token_proof"))
        assertEquals(tokens.current, store.current().token)
        assertEquals(false, store.current().tokenPending)

        // Nothing new to tell: no second request.
        setup.syncToken()
        assertEquals(1, relayRequests.count { it.method == "PUT" })
    }

    private fun json(code: Int, body: String) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content

    /** The plugin's dashboard routes, answering as `plugin_api.py` does. */
    private class FakePlugin(private val claimedInstallation: () -> String) : PushPluginApi {
        var capabilities: List<String>? = listOf("pairing", "e2e-notifications", "watch-tools")
        var status: String? = null
        var refuseKey = false
        val posts = mutableListOf<Pair<String, JsonObject>>()
        val profiles = mutableListOf<String>()
        private var paired: Pair<String, String>? = null
        private var claims = 0

        override suspend fun get(dashboardId: DashboardId, profile: String, route: String): NetResult<JsonElement> {
            profiles += profile
            return when (route) {
                "capabilities" -> capabilities?.let { list ->
                    ok("""{"ok":true,"capabilities":[${list.joinToString(",") { "\"$it\"" }}]}""")
                } ?: NetResult.Failure(ConnectionFailure.NotFound(null))

                "e2e" -> ok(
                    status
                        ?: paired?.let { (inst, gw) ->
                            """{"ok":true,"paired":true,"installation_id":"$inst","gateway_id":"$gw"}"""
                        }
                        ?: """{"ok":true,"paired":false}""",
                )

                else -> NetResult.Failure(ConnectionFailure.NotFound(null))
            }
        }

        override suspend fun post(
            dashboardId: DashboardId,
            profile: String,
            route: String,
            body: JsonObject,
        ): NetResult<JsonElement> {
            profiles += profile
            posts += route to body
            return when (route) {
                "pair" -> {
                    // The real plugin claims the code at the relay, which names the code's installation
                    // and a new gateway for every claim.
                    val installation = claimedInstallation()
                    claims += 1
                    val gateway = "gw-$claims"
                    paired = installation to gateway
                    ok(
                        """{"ok":true,"paired":true,"installation_id":"$installation","gateway_id":"$gateway","gateway_name":"home Hermes ($profile)"}""",
                    )
                }

                "e2e" -> if (refuseKey) {
                    NetResult.Failure(
                        ConnectionFailure.UnexpectedResponse(
                            409,
                            "This Hermes profile is paired with a different device",
                        ),
                    )
                } else {
                    ok("""{"ok":true,"kid":"${body["kid"]!!.jsonPrimitive.content}"}""")
                }

                else -> NetResult.Failure(ConnectionFailure.NotFound(null))
            }
        }

        private fun ok(text: String) = NetResult.Success(Json.parseToJsonElement(text))
    }
}
