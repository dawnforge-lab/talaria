package lab.dawnforge.hermes.core.data.session

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lab.dawnforge.hermes.core.data.TestStores
import lab.dawnforge.hermes.core.data.dashboard.DashboardCredentials
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.DashboardApi
import lab.dawnforge.hermes.core.net.DashboardAuthClient
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.core.net.StoredCookie
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

class SessionRepositoryTest {
    private val server = MockWebServer()
    private val stores = TestStores()
    private val factory = DashboardClientFactory(DashboardClientFactory.defaultBaseClient())
    private val signIn = SignInService(DashboardAuthClient(factory), stores.repository, stores.cookies)
    private val requests = DashboardRequests(DashboardApi(factory), stores.repository, stores.cookies, signIn)
    private val sessions = SessionRepository(requests) { null }
    private val profiles = ProfileRepository(requests, stores.repository)
    private val id = DashboardId("d1")
    private lateinit var url: DashboardUrl

    /** A scripted Hermes: [routes] answers by "METHOD path"; logins and tickets are counted. */
    private val routes = mutableMapOf<String, (RecordedRequest) -> MockResponse>()
    private val logins = AtomicInteger()
    private val recorded = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()

    @BeforeTest
    fun setUp() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded += request
                if (request.url.encodedPath == "/auth/password-login") {
                    val n = logins.incrementAndGet()
                    return json("""{"ok":true}""", cookie = "hermes_session_at=renewed$n; Path=/")
                }
                if (request.url.encodedPath == "/api/auth/ws-ticket") return json("""{"ticket":"t"}""")
                if (request.url.encodedPath == "/api/status") return json("""{"version":"0.22.2"}""")
                val route = routes["${request.method} ${request.url.encodedPath}"] ?: return MockResponse(code = 404)
                return route(request)
            }
        }
        server.start()
        url = DashboardUrl("http", server.hostName, server.port, "")
        stores.repository.saveAndActivate(Dashboard(id, url, "me", 1), "basic")
        stores.repository.saveCredentials(id, DashboardCredentials("me", "pw"))
        stores.cookies.replace(id, listOf(cookie("hermes_session_at", "original")))
    }

    @AfterTest
    fun tearDown() = server.close()

    private fun cookie(name: String, value: String) = StoredCookie(
        name,
        value,
        server.hostName,
        "/",
        0,
        persistent = false,
        secure = false,
        httpOnly = true,
        hostOnly = true,
    )

    private fun json(body: String, code: Int = 200, cookie: String? = null) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }
        .body(body)
        .build()

    private fun RecordedRequest.bodyJson(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

    private val rowJson = """
        {"id":"20261021_142233_a1b2c3","title":null,"preview":"Fix the build","model":"m","source":"telegram",
         "started_at":1761050553.12,"last_active":1761051000.5,"message_count":14,
         "archived":false,"pinned":true,"hidden":0,"unread":true,"profile":"work","parent_session_id":null}
    """.trimIndent()

    @Test
    fun `a page asks for the profile's recent chats like the dashboard sidebar`() = runTest {
        routes["GET /api/profiles/sessions"] = { json("""{"sessions":[$rowJson],"total":1,"limit":50,"offset":0}""") }

        val result = sessions.page(id, "work", archived = false, offset = 0, limit = 50)
        val page = assertIs<NetResult.Success<SessionPage>>(result).value
        val query = recorded.last { it.url.encodedPath == "/api/profiles/sessions" }.url
        assertEquals("work", query.queryParameter("profile"))
        assertEquals("recent", query.queryParameter("order"))
        assertEquals("1", query.queryParameter("min_messages"))
        assertEquals("exclude", query.queryParameter("archived"))
        assertEquals("cron", query.queryParameter("exclude_sources"))
        assertEquals("50", query.queryParameter("limit"))

        val row = page.rows.single()
        assertEquals("20261021_142233_a1b2c3", row.id)
        assertEquals("Fix the build", row.displayTitle)
        assertEquals(1_761_051_000_500L, row.lastActiveAtMs)
        assertTrue(row.pinned && row.unread && !row.archived)
        assertTrue(row.isMessagingSource)
        assertEquals(1, page.total)
    }

    @Test
    fun `the archive is asked for with archived=only`() = runTest {
        routes["GET /api/profiles/sessions"] = { json("""{"sessions":[]}""") }
        sessions.page(id, Profile.DEFAULT, archived = true, offset = 50, limit = 50)
        val query = recorded.last { it.url.encodedPath == "/api/profiles/sessions" }.url
        assertEquals("only", query.queryParameter("archived"))
        assertEquals("50", query.queryParameter("offset"))
        assertEquals("default", query.queryParameter("profile"))
    }

    @Test
    fun `scheduled runs are asked for by source, in place of leaving them out`() = runTest {
        routes["GET /api/profiles/sessions"] = { json("""{"sessions":[]}""") }
        sessions.page(id, "work", archived = false, offset = 0, limit = 100, scheduled = true)
        val query = recorded.last { it.url.encodedPath == "/api/profiles/sessions" }.url
        assertEquals("cron", query.queryParameter("source"))
        assertEquals(null, query.queryParameter("exclude_sources"))
        assertEquals("exclude", query.queryParameter("archived"))
    }

    @Test
    fun `changes name the profile in the body and the session in the path`() = runTest {
        routes["PATCH /api/sessions/20261021_142233_a1b2c3"] = { json("""{"ok":true,"title":"Ship it"}""") }

        assertEquals(NetResult.Success("Ship it"), sessions.rename(id, "work", "20261021_142233_a1b2c3", "  Ship it "))
        assertEquals("""{"title":"Ship it","profile":"work"}""", recorded.last().bodyJson().toString())

        sessions.setPinned(id, "work", "20261021_142233_a1b2c3", pinned = true)
        assertEquals("""{"pinned":true,"profile":"work"}""", recorded.last().bodyJson().toString())

        sessions.setArchived(id, "work", "20261021_142233_a1b2c3", archived = true)
        assertEquals(
            """{"archived":true,"pinned":false,"profile":"work"}""",
            recorded.last().bodyJson().toString(),
            "archiving unpins, or Hermes keeps showing it",
        )

        sessions.setUnread(id, "work", "20261021_142233_a1b2c3", unread = false)
        assertEquals("""{"unread":false,"profile":"work"}""", recorded.last().bodyJson().toString())
    }

    @Test
    fun `a rejected title shows Hermes' reason`() = runTest {
        routes["PATCH /api/sessions/s1"] = { json("""{"detail":"Title too long (120 chars, max 100)"}""", code = 400) }
        assertEquals(
            NetResult.Failure(ConnectionFailure.Invalid("Title too long (120 chars, max 100)")),
            sessions.rename(id, "work", "s1", "x".repeat(120)),
        )
    }

    @Test
    fun `delete names the profile in the query`() = runTest {
        routes["DELETE /api/sessions/s1"] = { json("""{"ok":true}""") }
        assertEquals(NetResult.Success(Unit), sessions.delete(id, "work", "s1"))
        assertEquals("work", recorded.last().url.queryParameter("profile"))
    }

    @Test
    fun `search reads hits and their snippets`() = runTest {
        routes["GET /api/sessions/search"] = {
            json(
                """{"results":[{"snippet":"we fixed the >>>gateway<<<","session_id":"s9","id":"s9","title":"Ops",
                   "last_active":1761051000.5,"archived":false,"source":"desktop","profile":"default"}]}""",
            )
        }
        val hits = assertIs<NetResult.Success<List<lab.dawnforge.hermes.core.model.SessionSearchHit>>>(
            sessions.search(id, "default", "gateway"),
        ).value
        assertEquals("s9", hits.single().sessionId)
        assertEquals("we fixed the >>>gateway<<<", hits.single().snippet)
        assertEquals("gateway", recorded.last().url.queryParameter("q"))
    }

    @Test
    fun `profiles read display names and plain names`() = runTest {
        routes["GET /api/profiles"] = {
            json(
                """{"profiles":[{"name":"default","is_default":true,"display_name":""},
                   {"name":"work","display_name":"Work"},"old"]}""",
            )
        }
        val list = assertIs<NetResult.Success<List<Profile>>>(profiles.profiles(id)).value
        // The main profile shows as "Hermes", first; the rest by name, as on iOS.
        assertEquals(listOf("Hermes", "old", "Work"), list.map { it.label })
        assertTrue(list.first().isDefault)
    }

    @Test
    fun `an expired session is renewed with the saved password and the call retried`() = runTest {
        routes["GET /api/profiles"] = { request ->
            if (request.headers["Cookie"]?.contains("original") == true) {
                json("""{"error":"session_expired"}""", code = 401)
            } else {
                json("""{"profiles":["default"]}""")
            }
        }
        assertIs<NetResult.Success<List<Profile>>>(profiles.profiles(id))
        assertEquals(1, logins.get())
        stores.cookies.flush()
        assertEquals("renewed1", stores.repository.cookies(id).single().value)
    }

    @Test
    fun `calls rejected together renew the session once`() = runTest {
        routes["GET /api/profiles"] = { request ->
            if (request.headers["Cookie"]?.contains("original") == true) {
                Thread.sleep(100)
                json("""{"error":"session_expired"}""", code = 401)
            } else {
                json("""{"profiles":["default"]}""")
            }
        }
        val results = List(4) { async { profiles.profiles(id) } }.awaitAll()
        assertTrue(results.all { it is NetResult.Success })
        assertEquals(1, logins.get(), "one password sign-in for all four")
    }

    @Test
    fun `cookies rotated by any response are kept`() = runTest {
        routes["GET /api/profiles"] = {
            json("""{"profiles":["default"]}""", cookie = "hermes_session_at=rotated; Path=/")
        }
        profiles.profiles(id)
        stores.cookies.flush()
        assertEquals("rotated", stores.repository.cookies(id).single().value)
        assertEquals(0, logins.get())
    }

    @Test
    fun `starting a chat needs the live connection`() = runTest {
        assertEquals(
            NetResult.Failure(ConnectionFailure.NetworkError("The live connection is not open")),
            sessions.startChat("default"),
        )
    }
}
