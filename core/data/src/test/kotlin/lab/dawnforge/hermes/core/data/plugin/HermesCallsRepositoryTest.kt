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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lab.dawnforge.hermes.core.data.TestStores
import lab.dawnforge.hermes.core.data.dashboard.DashboardCredentials
import lab.dawnforge.hermes.core.data.session.DashboardRequests
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

class HermesCallsRepositoryTest {
    private val server = MockWebServer()
    private val stores = TestStores()
    private val factory = DashboardClientFactory(DashboardClientFactory.defaultBaseClient())
    private val signIn = SignInService(DashboardAuthClient(factory), stores.repository, stores.cookies)
    private val requests = DashboardRequests(DashboardApi(factory), stores.repository, stores.cookies, signIn)
    private var now = 1_800_000_000_000L
    private val calls = HermesCallsRepository({ requests }, stores.store("calls")) { now }
    private val id = DashboardId("d1")
    private val api = "/api/plugins/talaria_push"

    private val bodies = CopyOnWriteArrayList<Pair<String, JsonObject>>()

    @Volatile
    private var outcomes: () -> MockResponse = { json("""{"ok":true,"status":"recorded"}""") }

    private val status = """{"ok":true,"paired":true,
        "settings":{"enabled":true,"when_asked":true,"decides":false,"alerts":false,"min_gap_s":120,
          "per_hour":6,"per_day":20,"rules":"","avoid_places":[],"avoid_unknown_place":false},
        "bounds":{"min_gap_s":{"min":30,"max":3600},"per_hour":{"min":1,"max":30},"per_day":{"min":1,"max":60}},
        "rules_max":500,"watches":0}"""

    @BeforeTest
    fun setUp() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/auth/password-login") {
                    return json("""{"ok":true}""", cookie = "hermes_session_at=s; Path=/")
                }
                val body = request.body?.utf8()?.takeIf(String::isNotEmpty)?.let {
                    Json.parseToJsonElement(it).jsonObject
                }
                if (body != null) bodies += "${request.method} ${request.url.encodedPath}" to body
                return when ("${request.method} ${request.url.encodedPath}") {
                    "GET $api/calls" -> json(status)
                    "PUT $api/calls" -> json("""{"ok":true,"settings":${body!!["settings"]}}""")
                    "POST $api/calls/outcomes" -> outcomes()
                    else -> json("""{"detail":"Not Found"}""", code = 404)
                }
            }
        }
        server.start()
        stores.repository.saveAndActivate(
            Dashboard(id, DashboardUrl("http", server.hostName, server.port, ""), "me", 1),
            "basic",
        )
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

    @Test
    fun `the host's call settings, bounds and pairing are read`() = runBlocking {
        val read = assertIs<NetResult.Success<CallsStatus>>(calls.status(id, "default")).value
        assertTrue(read.paired)
        assertTrue(read.settings.enabled)
        assertEquals(false, read.settings.decides)
        assertEquals("", read.settings.rules)
        assertEquals(1..30, read.perHourBounds)
        assertEquals(500, read.rulesMax)
    }

    @Test
    fun `saving sends only the keys the host reported`() = runBlocking {
        val older = CallSettings(enabled = true, whenAsked = false, perHour = 3)
        val saved = assertIs<NetResult.Success<CallSettings>>(calls.save(id, "default", older)).value

        val sent = bodies.single { it.first == "PUT $api/calls" }.second["settings"]!!.jsonObject
        assertEquals(setOf("enabled", "when_asked", "min_gap_s", "per_hour", "per_day"), sent.keys)
        assertEquals(3, sent["per_hour"]!!.jsonPrimitive.int)
        assertFalse(saved.whenAsked)
        assertNull(saved.decides)
    }

    private fun outcome(callId: String = "0123456789abcdef01234567", at: Long = now) = PendingCallOutcome(
        dashboard = id.value,
        profile = "default",
        callId = callId,
        sessionIds = listOf("sess-1"),
        outcome = CallOutcome.Declined.key,
        kind = "done",
        title = "Check the server",
        at = at,
    )

    @Test
    fun `an unanswered call goes to the host with how long ago it was`() = runBlocking {
        calls.record(outcome(at = now - 90_000))

        val sent = bodies.single { it.first == "POST $api/calls/outcomes" }.second
        assertEquals("0123456789abcdef01234567", sent["call_id"]!!.jsonPrimitive.content)
        assertEquals("declined", sent["outcome"]!!.jsonPrimitive.content)
        assertEquals(90, sent["age_s"]!!.jsonPrimitive.int)
        assertEquals(listOf("sess-1"), sent["session_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("done", sent["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an outcome the host couldn't take now waits, one it refuses for good is dropped`() = runBlocking {
        outcomes = { json("""{"detail":"busy"}""", code = 503) }
        calls.record(outcome())
        outcomes = { json("""{"ok":true,"status":"recorded"}""") }
        calls.deliver()
        assertEquals(2, bodies.count { it.first == "POST $api/calls/outcomes" })
        calls.deliver()
        assertEquals(2, bodies.count { it.first == "POST $api/calls/outcomes" }, "delivered once, then forgotten")

        outcomes = { json("""{"detail":"This Hermes profile isn't paired with Talaria"}""", code = 409) }
        calls.record(outcome(callId = "abcdefabcdefabcdefabcdef"))
        calls.deliver()
        assertEquals(3, bodies.count { it.first == "POST $api/calls/outcomes" }, "a profile no longer paired drops it")
    }

    @Test
    fun `outcomes older than 23 hours, or of a dashboard signed out, are forgotten`() = runBlocking {
        outcomes = { json("""{"detail":"busy"}""", code = 503) }
        calls.record(outcome())
        now += 24L * 60 * 60 * 1000
        calls.deliver()
        assertEquals(1, bodies.count { it.first == "POST $api/calls/outcomes" })

        calls.record(outcome(callId = "abcdefabcdefabcdefabcdef"))
        calls.forget(id)
        calls.deliver()
        assertEquals(2, bodies.count { it.first == "POST $api/calls/outcomes" })
    }
}
