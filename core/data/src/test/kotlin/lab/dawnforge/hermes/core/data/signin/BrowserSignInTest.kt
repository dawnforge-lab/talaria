package lab.dawnforge.hermes.core.data.signin

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lab.dawnforge.hermes.core.data.TestStores
import lab.dawnforge.hermes.core.data.session.BrowserCookieStores
import lab.dawnforge.hermes.core.data.session.SessionCookies
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.model.SignInMethod
import lab.dawnforge.hermes.core.net.BrowserCookieStore
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.DashboardAuthClient
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/** A sign-in proxy in front of Hermes, signed in through its page in the app's browser. */
class BrowserSignInTest {
    /** The app browser's stores, one header string per dashboard. */
    private class FakeBrowser : BrowserCookieStores {
        val headers = mutableMapOf<DashboardId, String?>()
        val cleared = mutableListOf<DashboardId>()
        val discarded = mutableListOf<Pair<DashboardId, String>>()

        override suspend fun store(id: DashboardId): BrowserCookieStore = object : BrowserCookieStore {
            override fun cookieHeader(url: String): String? = headers[id]

            override fun save(url: String, headers: List<String>) = Unit
        }

        override suspend fun clear(id: DashboardId) {
            cleared += id
            headers.remove(id)
        }

        override suspend fun delete(id: DashboardId) = clear(id)

        override suspend fun discard(id: DashboardId, url: String) {
            discarded += id to url
            headers.remove(id)
        }

        override suspend fun sweep(kept: Set<DashboardId>) {
            headers.keys.retainAll(kept)
        }
    }

    private val server = MockWebServer()
    private val stores = TestStores()
    private val browser = FakeBrowser()
    private val cookies = SessionCookies(stores.repository, CoroutineScope(SupervisorJob() + Dispatchers.IO), browser)
    private val service = SignInService(
        auth = DashboardAuthClient(DashboardClientFactory(DashboardClientFactory.defaultBaseClient())),
        repository = stores.repository,
        cookies = cookies,
        newId = { "web-1" },
    )
    private lateinit var url: DashboardUrl

    @BeforeTest
    fun setUp() {
        // The proxy: its sign-in page for anything without its cookie, Hermes behind it otherwise.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val signedIn = request.headers["Cookie"].orEmpty().contains("_oauth2_proxy=ok")
                return when {
                    !signedIn -> MockResponse.Builder().code(302).addHeader("Location", "/oauth2/start").build()
                    request.url.encodedPath == "/api/auth/ws-ticket" -> json("""{"ticket":"t"}""")
                    else -> json("""{"version":"0.22.2"}""")
                }
            }
        }
        server.start()
        url = DashboardUrl("http", server.hostName, server.port, "")
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun json(body: String) =
        MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `a proxy's sign-in page is a way to sign in`() = runTest {
        val inspection = assertIs<AddressInspection.Ready>(service.inspectAddress(url.toString(), null))
        assertEquals(SignInMethod.Browser, inspection.method)
    }

    @Test
    fun `the page's session is saved once it opens the live connection`() = runTest {
        val id = service.browserTarget(url)
        assertEquals(
            ConnectionFailure.SignInRequired,
            assertIs<SignInResult.Failed>(service.completeBrowserSignIn(id, url, null)).failure,
        )
        assertEquals(emptyList(), stores.repository.dashboards.first())

        browser.headers[id] = "_oauth2_proxy=ok"
        val signedIn = assertIs<SignInResult.SignedIn>(service.completeBrowserSignIn(id, url, null))
        assertEquals(id, signedIn.dashboard.id)
        assertTrue(cookies.isBrowserSession(id))
        assertNull(stores.repository.credentials(id))
        // The app's own requests and the live connection's ticket use the browser's cookies.
        assertIs<lab.dawnforge.hermes.core.gateway.TicketResult.Ticket>(service.mintGatewayTicket(id))
    }

    @Test
    fun `an expired page session asks for the page again, and keeps the browser's cookies`() = runTest {
        val id = service.browserTarget(url)
        browser.headers[id] = "_oauth2_proxy=ok"
        service.completeBrowserSignIn(id, url, null)

        browser.headers[id] = "_oauth2_proxy=expired"
        assertIs<lab.dawnforge.hermes.core.gateway.TicketResult.SignInRequired>(service.mintGatewayTicket(id))
        assertEquals(emptyList(), browser.cleared)
    }

    @Test
    fun `a page given up on before its dashboard was saved leaves nothing in the browser`() = runTest {
        val id = service.browserTarget(url)
        browser.headers[id] = "CF_Authorization=half-way"

        service.abandonBrowserSignIn(id, url)
        assertEquals(listOf(id to url.toString()), browser.discarded)
        assertNull(browser.headers[id])
        assertEquals(emptyList(), stores.repository.dashboards.first())
    }

    @Test
    fun `a page given up on for a saved dashboard keeps its session`() = runTest {
        val id = service.browserTarget(url)
        browser.headers[id] = "_oauth2_proxy=ok"
        service.completeBrowserSignIn(id, url, null)

        // Signing in again later: the page opens for the saved dashboard, and is cancelled.
        assertEquals(id, service.browserTarget(url))
        service.abandonBrowserSignIn(id, url)
        assertEquals(emptyList(), browser.discarded)
        assertEquals("_oauth2_proxy=ok", browser.headers[id])
    }

    @Test
    fun `the sweep keeps saved dashboards' browser data and forgets the rest`() = runTest {
        val saved = service.browserTarget(url)
        browser.headers[saved] = "_oauth2_proxy=ok"
        service.completeBrowserSignIn(saved, url, null)
        // A page the app never saw end: the process was killed while it showed.
        val orphan = DashboardId("never-saved")
        browser.headers[orphan] = "CF_Authorization=half-way"

        service.sweepBrowserSignIns()
        assertEquals(setOf(saved), browser.headers.keys)
    }

    @Test
    fun `signing out signs the browser out too, and a password sign-in leaves it`() = runTest {
        val id = service.browserTarget(url)
        browser.headers[id] = "_oauth2_proxy=ok"
        service.completeBrowserSignIn(id, url, null)

        service.signOut(id)
        assertEquals(listOf(id), browser.cleared)
        assertEquals(false, cookies.isBrowserSession(id))
    }
}
