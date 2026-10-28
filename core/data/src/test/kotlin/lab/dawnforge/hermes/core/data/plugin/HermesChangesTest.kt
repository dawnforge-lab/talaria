package lab.dawnforge.hermes.core.data.plugin

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lab.dawnforge.hermes.core.data.TestStores
import lab.dawnforge.hermes.core.data.dashboard.DashboardCredentials
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.data.settings.ConnectorsRepository
import lab.dawnforge.hermes.core.data.settings.HermesChangesMode
import lab.dawnforge.hermes.core.data.settings.VoiceSettings
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.net.DashboardApi
import lab.dawnforge.hermes.core.net.DashboardAuthClient
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

class HermesChangesTest {
    private val server = MockWebServer()
    private val stores = TestStores()
    private val factory = DashboardClientFactory(DashboardClientFactory.defaultBaseClient())
    private val signIn = SignInService(DashboardAuthClient(factory), stores.repository, stores.cookies)
    private val requests = DashboardRequests(DashboardApi(factory), stores.repository, stores.cookies, signIn)
    private val plugin =
        PushPluginRepository(requests, ConnectorsRepository(requests), DashboardClientFactory.defaultBaseClient())
    private val app = AppSettings(stores.store("app"))
    private val voice = VoiceSettings(stores.store("voice"))
    private val foreground = MutableStateFlow(true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val id = DashboardId("d1")

    private val syncs = CopyOnWriteArrayList<JsonObject>()

    @Volatile
    private var reply: () -> MockResponse = { json("""{"ok":true,"pending":[]}""") }

    @BeforeTest
    fun setUp() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/auth/password-login") {
                    return json("""{"ok":true}""", cookie = "hermes_session_at=s; Path=/")
                }
                if (request.method == "PUT" && request.url.encodedPath == "$API/talaria-settings") {
                    syncs += Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                    return reply()
                }
                return json("""{"detail":"Not Found"}""", code = 404)
            }
        }
        server.start()
        val url = DashboardUrl("http", server.hostName, server.port, "")
        stores.repository.saveAndActivate(Dashboard(id, url, "me", 1), "basic")
        stores.repository.saveCredentials(id, DashboardCredentials("me", "pw"))
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun json(body: String, code: Int = 200, cookie: String? = null) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }
        .body(body)
        .build()

    private fun changes() =
        HermesChanges(stores.repository, plugin, app, voice, AppDevice("Pixel", "0.1.0"), foreground, scope)

    private fun pending(vararg rows: String) = json("""{"ok":true,"pending":[${rows.joinToString(",")}]}""")

    private suspend fun eventually(what: String, check: suspend () -> Boolean) {
        withTimeout(TIMEOUT_MS) { while (!check()) delay(POLL_MS) }
        assertTrue(check(), what)
    }

    private fun JsonObject.results() = this["results"]!!.jsonArray.map { it.jsonObject }

    private fun continuous() = runBlocking { voice.profile(id, "default").first().continuousConversation }

    @Test
    fun `the phone shares its settings, its mode and who it is`() = runBlocking {
        changes()
        eventually("a sync") { syncs.isNotEmpty() }

        val body = syncs.first()
        assertEquals("Pixel", body["device"]!!.jsonPrimitive.content)
        assertEquals("ask", body["mode"]!!.jsonPrimitive.content)
        val settings = body["settings"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("voice.continuous_conversation", "voice.keep_listening_when_locked"),
            settings.map { it["key"]!!.jsonPrimitive.content },
        )
        assertTrue(settings.first()["value"]!!.jsonPrimitive.boolean)
        assertEquals("bool", settings.first()["kind"]!!.jsonPrimitive.content)
        assertTrue(body["results"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `a change waits for Apply, then its outcome goes up once`() = runBlocking {
        reply = {
            pending(
                """{"id":"r1","key":"voice.continuous_conversation","value":false,"from":true,
                   "reason":"You asked me to."}""",
            )
        }
        val changes = changes()
        eventually("the change to review") { changes.review.value.isNotEmpty() }

        val item = changes.review.value.single()
        assertEquals("Continuous Conversation", item.title)
        assertEquals("On", item.oldValue)
        assertEquals("Off", item.newValue)
        assertEquals("You asked me to.", item.request.reason)
        assertTrue(continuous(), "nothing changes before Apply")

        reply = { pending() }
        changes.resolve(listOf(item), apply = true)
        eventually("the outcome reported") { syncs.any { body -> body.results().isNotEmpty() } }

        assertFalse(continuous())
        val reported = syncs.first { it.results().isNotEmpty() }.results().single()
        assertEquals("r1", reported["id"]!!.jsonPrimitive.content)
        assertEquals("applied", reported["status"]!!.jsonPrimitive.content)
        assertTrue(changes.review.value.isEmpty())
        eventually("the outcome forgotten once heard") { app.settingResults(id, "default").isEmpty() }
    }

    @Test
    fun `dismissing keeps the setting and says the user said no`() = runBlocking {
        reply = { pending("""{"id":"r1","key":"voice.continuous_conversation","value":false}""") }
        val changes = changes()
        eventually("the change to review") { changes.review.value.isNotEmpty() }

        reply = { pending() }
        changes.resolve(changes.review.value, apply = false)
        eventually("the outcome reported") { syncs.any { body -> body.results().isNotEmpty() } }

        assertTrue(continuous())
        assertEquals(
            "dismissed",
            syncs.first {
                it.results().isNotEmpty()
            }.results().single()["status"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `a setting the phone doesn't share, a wrong kind of value, or the mode off are refused`() = runBlocking {
        voice.updateDevice { it.copy(hermesChanges = HermesChangesMode.Off) }
        reply = {
            pending(
                """{"id":"r1","key":"app.theme","value":"dark"}""",
                """{"id":"r2","key":"voice.keep_listening_when_locked","value":"yes"}""",
                """{"id":"r3","key":"voice.continuous_conversation","value":false}""",
            )
        }
        val changes = changes()
        eventually("the refusals reported") { syncs.any { body -> body.results().size == 3 } }

        val statuses = syncs.first { it.results().size == 3 }.results()
            .associate { it["id"]!!.jsonPrimitive.content to it["status"]!!.jsonPrimitive.content }
        assertEquals(mapOf("r1" to "refused", "r2" to "refused", "r3" to "refused"), statuses)
        assertTrue(changes.review.value.isEmpty())
        assertTrue(continuous())
    }

    @Test
    fun `a value the user changed since Hermes read it is kept`() = runBlocking {
        voice.updateProfile(id, "default") { it.copy(continuousConversation = false) }
        reply = {
            pending("""{"id":"r1","key":"voice.keep_listening_when_locked","value":true,"from":true}""")
        }
        changes()
        eventually("the conflict reported") { syncs.any { body -> body.results().isNotEmpty() } }

        val reported = syncs.first { it.results().isNotEmpty() }.results().single()
        assertEquals("conflict", reported["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a profile not paired with the plugin isn't asked again until the app comes back`() = runBlocking {
        reply = { json("""{"detail":"not paired"}""", code = 409) }
        val changes = changes()
        eventually("the first sync") { syncs.size == 1 }

        changes.sync()
        delay(QUIET_MS)
        assertEquals(1, syncs.size)

        foreground.value = false
        delay(QUIET_MS)
        foreground.value = true
        eventually("a sync once back") { syncs.size == 2 }
    }

    @Test
    fun `the plugin's answer keeps only well-formed requests`() {
        val parsed = SettingsJson.pending(
            Json.parseToJsonElement(
                """{"ok":true,"pending":[
                    {"id":"a","key":"k","value":true,"from":false,"reason":"why"},
                    {"id":"b","key":"k","value":"choice"},
                    {"id":"","key":"k","value":true},
                    {"id":"c","value":true},
                    {"id":"d","key":"k","value":{"nested":1}},
                    "junk"
                ]}""",
            ),
        )!!

        assertEquals(listOf("a", "b"), parsed.map { it.id })
        assertEquals(SettingValue.Flag(true), parsed[0].value)
        assertEquals(SettingValue.Flag(false), parsed[0].from)
        assertEquals(SettingValue.Choice("choice"), parsed[1].value)
        assertNull(parsed[1].from)
        assertNull(SettingsJson.pending(Json.parseToJsonElement("""{"ok":false}""")))
        assertEquals(SettingValue.Choice("true"), SettingValue.of(Json.parseToJsonElement("\"true\"")))
    }

    private companion object {
        const val API = "/api/plugins/talaria_push"
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
        const val QUIET_MS = 500L
    }
}
