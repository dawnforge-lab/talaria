package lab.dawnforge.hermes.core.data.plugin

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.net.DashboardApi
import lab.dawnforge.hermes.core.net.DashboardAuthClient
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import lab.dawnforge.hermes.core.net.NetResult
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

class PushPluginRepositoryTest {
    private val server = MockWebServer()
    private val stores = TestStores()
    private val factory = DashboardClientFactory(DashboardClientFactory.defaultBaseClient())
    private val signIn = SignInService(DashboardAuthClient(factory), stores.repository, stores.cookies)
    private val requests = DashboardRequests(DashboardApi(factory), stores.repository, stores.cookies, signIn)
    private val plugin =
        PushPluginRepository(requests, ConnectorsRepository(requests), DashboardClientFactory.defaultBaseClient())
    private val id = DashboardId("d1")

    private val routes = mutableMapOf<String, (RecordedRequest) -> MockResponse>()
    private val recorded = CopyOnWriteArrayList<RecordedRequest>()

    @BeforeTest
    fun setUp() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded += request
                if (request.url.encodedPath == "/auth/password-login") {
                    return json("""{"ok":true}""", cookie = "hermes_session_at=s; Path=/")
                }
                val route = routes["${request.method} ${request.url.encodedPath}"]
                    ?: return json("""{"detail":"Not Found"}""", code = 404)
                return route(request)
            }
        }
        server.start()
        val url = DashboardUrl("http", server.hostName, server.port, "")
        stores.repository.saveAndActivate(Dashboard(id, url, "me", 1), "basic")
        stores.repository.saveCredentials(id, DashboardCredentials("me", "pw"))
    }

    @AfterTest
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200, cookie: String? = null) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }
        .body(body)
        .build()

    private fun last(path: String) = recorded.last { it.url.encodedPath == path }

    private val api = "/api/plugins/talaria_push"

    @Test
    fun `the plugin reports its versions and what it serves`() = runTest {
        routes["GET $api/capabilities"] = {
            json(
                """{"ok":true,"version":"0.54.1","loaded_version":"0.53.0","restart_needed":true,"boot_id":"b1",
                   "capabilities":["session-takeover","desktop-views"]}""",
            )
        }

        val read = assertIs<NetResult.Success<PluginState>>(plugin.state(id, "default"))
        val state = assertIs<PluginState.Reported>(read.value)

        assertEquals("0.54.1", state.status.version)
        assertEquals("0.53.0", state.status.runningVersion)
        assertTrue(state.status.restartNeeded)
        assertTrue(state.status.reports("desktop-views"))
        assertFalse(state.status.reports("artifacts"))
    }

    @Test
    fun `without the capabilities route Hermes' plugin list says why`() = runTest {
        routes["GET /api/dashboard/plugins/hub"] = { json("""{"plugins":[]}""") }
        assertEquals(PluginState.Absent(HostedPlugin.Missing), (plugin.state(id, "default") as NetResult.Success).value)

        routes["GET /api/dashboard/plugins/hub"] = {
            json("""{"plugins":[{"name":"talaria_push","version":"0.30.0","source":"git","status":"enabled"}]}""")
        }
        assertEquals(
            PluginState.Absent(HostedPlugin.NotServed),
            (plugin.state(id, "default") as NetResult.Success).value,
        )

        routes["GET /api/dashboard/plugins/hub"] = {
            json("""{"plugins":[{"name":"talaria_push","version":"0.3.2","source":"git","status":"enabled"}]}""")
        }
        assertEquals(PluginState.Absent(HostedPlugin.Old), (plugin.state(id, "default") as NetResult.Success).value)
    }

    @Test
    fun `a chat is taken over by every id it's known by, at most four`() = runTest {
        routes["POST $api/sessions/takeover"] = { json("""{"ok":true,"status":"taken_over","surface":"desktop"}""") }

        val status = plugin.takeover(id, "work", listOf("rt", "s1", "rt", "", "s2", "s3", "s4"))

        assertEquals(TakeoverStatus.TakenOver, (status as NetResult.Success).value)
        val request = last("$api/sessions/takeover")
        assertEquals("work", request.url.queryParameter("profile"))
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        val ids = body["session_ids"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("rt", "s1", "s2", "s3"), ids)
    }

    @Test
    fun `desktop views read what Desktop had on screen since the cursor`() = runTest {
        routes["GET $api/sessions/desktop-views"] = {
            json(
                """{"ok":true,"observing":true,"reason":null,"views":{
                   "s1":{"opened_at":1.0,"seen_through":1792690000.5,"client":"desktop"},
                   "s2":{"seen_through":1792690100,"client":"browser","open":true},"bad":{}}}""",
            )
        }

        val views = (plugin.desktopViews(id, "default", since = 1792680000.0) as NetResult.Success).value

        assertEquals("1792680000", last("$api/sessions/desktop-views").url.queryParameter("since"))
        assertTrue(views.observing)
        assertEquals(DesktopView(1792690000.5, open = false), views.views["s1"])
        assertTrue(views.views.getValue("s2").open)
        assertNull(views.views["bad"])
    }

    @Test
    fun `reply sources are read and changed one at a time`() = runTest {
        routes["GET $api/notifications/reply-sources"] = {
            json("""{"ok":true,"sources":{"talaria":true,"desktop":false}}""")
        }
        routes["PUT $api/notifications/reply-sources"] = {
            json("""{"ok":true,"sources":{"talaria":false,"desktop":false,"messaging":true,"terminal":true}}""")
        }

        val read = (plugin.replySources(id, "default") as NetResult.Success).value
        // A source the host doesn't name notifies.
        assertEquals(ReplySources(app = true, desktop = false, messaging = true, terminal = true), read)

        val changed = (plugin.setReplySource(id, "default", ReplySource.App, notify = false) as NetResult.Success).value
        assertFalse(changed.app)
        val body = Json.parseToJsonElement(last("$api/notifications/reply-sources").body!!.utf8()).jsonObject
        assertEquals("talaria", body["source"]!!.jsonPrimitive.content)
        assertFalse(body["notify"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a refusal names the chat and who holds it`() {
        assertEquals(
            "20261022_1" to "desktop",
            PushPluginJson.ownerOf("Session is owned elsewhere. Details: session 20261022_1 opened by desktop 5m ago."),
        )
        assertEquals("abc" to null, PushPluginJson.ownerOf("Details: session abc."))
        assertEquals(null to null, PushPluginJson.ownerOf("busy"))
    }

    @Test
    fun `plugin versions compare by number, ignoring a suffix`() {
        assertTrue(PluginVersions.older("0.9.0", "0.48.0"))
        assertFalse(PluginVersions.older("0.54.1", "0.48.0"))
        assertFalse(PluginVersions.older("0.48.0-beta", "0.48.0"))
        assertTrue(PluginVersions.older("junk", "0.1.0"))
        assertEquals("0.54.1", PluginVersions.max("0.54.1", "0.48.0"))
        assertEquals("0.48.0", PluginVersions.max(null, "0.48.0"))
    }

    @Test
    fun `an exchange reports answers and this phone, and reads what still waits`() = runTest {
        routes["POST $api/present/exchange"] = {
            json(
                """{"ok":true,"prompts":[{"id":"p1234567","title":" Send the email? ","preview":"To **Ana**",
                   "action_label":"Send","spoken_yes":["send it"," "],"session_id":"s1","created_at":1,
                   "expires_at":2000000000.5},{"id":"bad"},{"id":"p7654321","title":"Delete it?"}]}""",
            )
        }
        val exchange = PresentExchange(
            "install-1",
            "Pixel 9",
            "0.1.0",
            listOf(ConfirmAnswer("p0000001", ConfirmOutcome.Declined)),
        )

        val prompts = assertIs<NetResult.Success<List<ConfirmPrompt>>>(plugin.exchange(id, "work", exchange)).value

        assertEquals(listOf("p1234567", "p7654321"), prompts.map { it.id })
        assertEquals("Send the email?", prompts[0].title)
        assertEquals("Send", prompts[0].actionLabel)
        assertEquals(listOf("send it"), prompts[0].spokenYes)
        assertEquals("s1", prompts[0].sessionId)
        assertFalse(prompts[0].isExpired(1_999_999_999_000))
        assertTrue(prompts[0].isExpired(2_000_000_001_000))
        assertNull(prompts[1].actionLabel)
        val request = last("$api/present/exchange")
        assertEquals("work", request.url.queryParameter("profile"))
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("install-1", body["installation_id"]!!.jsonPrimitive.content)
        assertEquals("Pixel 9", body["device"]!!.jsonPrimitive.content)
        assertEquals(PresentJson.FEATURES, body["features"]!!.jsonArray.map { it.jsonPrimitive.content })
        val result = body["results"]!!.jsonArray.single().jsonObject
        assertEquals("p0000001", result["id"]!!.jsonPrimitive.content)
        assertEquals("declined", result["outcome"]!!.jsonPrimitive.content)
        assertEquals("tap", result["via"]!!.jsonPrimitive.content)
    }

    @Test
    fun `confirmations come down, and an answer leaves the screen and stays kept until the host hears it`() =
        runBlocking {
            val waiting = """[{"id":"p1234567","title":"Send it?","session_id":"s1"}]"""
            val bodies = CopyOnWriteArrayList<JsonObject>()
            var hostDown = false
            routes["POST $api/present/exchange"] = { request ->
                bodies += Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                if (hostDown) json("""{"detail":"busy"}""", code = 503) else json("""{"ok":true,"prompts":$waiting}""")
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val settings = AppSettings(stores.settings)
            val device = AppDevice("Pixel", "0.1.0")
            val confirms = PresentedConfirms(stores.repository, plugin, settings, device, MutableStateFlow(true), scope)
            try {
                val prompt = withTimeout(TIMEOUT_MS) { confirms.current.filterNotNull().first() }
                assertEquals("p1234567", prompt.id)

                hostDown = true
                confirms.answer(prompt, ConfirmOutcome.Confirmed)
                assertNull(confirms.current.first())
                // The host didn't hear it: it's kept, and goes up again with the next exchange.
                withTimeout(TIMEOUT_MS) { while (bodies.none { it.answered("p1234567") }) delay(POLL_MS) }
                assertEquals(listOf("p1234567"), settings.confirmAnswers(id, "default").map { it.id })

                hostDown = false
                val sent = bodies.size
                confirms.sync()
                withTimeout(TIMEOUT_MS) { while (settings.confirmAnswers(id, "default").isNotEmpty()) delay(POLL_MS) }
                assertTrue(bodies.drop(sent).any { it.answered("p1234567") })
                // Still listed by the host in the round that reported it: not shown again.
                assertNull(confirms.current.first())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a host whose plugin can't exchange isn't asked again until the app comes back`() = runBlocking {
        var calls = 0
        routes["POST $api/present/exchange"] = {
            calls++
            json("""{"detail":"This Hermes profile isn't paired with Talaria"}""", code = 409)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val foreground = MutableStateFlow(false)
        val confirms = PresentedConfirms(
            stores.repository,
            plugin,
            AppSettings(stores.settings),
            AppDevice("Pixel", "0.1.0"),
            foreground,
            scope,
        )
        try {
            withTimeout(TIMEOUT_MS) { while (calls == 0) delay(POLL_MS) }
            confirms.sync()
            delay(SETTLE_MS)
            assertEquals(1, calls)

            foreground.value = true
            withTimeout(TIMEOUT_MS) { while (calls < 2) delay(POLL_MS) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a job's runs notify unless it's muted, and the switch answers the muted list`() = runTest {
        routes["GET $api/cron/notifications"] = { json("""{"ok":true,"muted":["j1"]}""") }
        routes["PUT $api/cron/notifications"] = { json("""{"ok":true,"muted":["j1","j2"]}""") }

        assertEquals(setOf("j1"), (plugin.mutedJobs(id, "work") as NetResult.Success).value)
        val after = plugin.setJobNotifies(id, "work", "j2", notify = false)

        assertEquals(setOf("j1", "j2"), (after as NetResult.Success).value)
        val body = Json.parseToJsonElement(last("$api/cron/notifications").body!!.utf8()).jsonObject
        assertEquals("j2", body["job_id"]!!.jsonPrimitive.content)
        assertFalse(body["notify"]!!.jsonPrimitive.boolean)
        assertEquals("work", last("$api/cron/notifications").url.queryParameter("profile"))
    }

    private fun JsonObject.answered(prompt: String) =
        this["results"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == prompt }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
        const val SETTLE_MS = 500L
    }
}
