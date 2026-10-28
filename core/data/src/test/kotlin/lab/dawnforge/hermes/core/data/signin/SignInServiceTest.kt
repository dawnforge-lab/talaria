package lab.dawnforge.hermes.core.data.signin

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lab.dawnforge.hermes.core.data.TestStores
import lab.dawnforge.hermes.core.data.dashboard.DashboardCredentials
import lab.dawnforge.hermes.core.gateway.TicketResult
import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.model.SignInMethod
import lab.dawnforge.hermes.core.net.AddressProblem
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.DashboardAuthClient
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import lab.dawnforge.hermes.core.net.NativeTokens
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class SignInServiceTest {
    private val server = MockWebServer()
    private val stores = TestStores()
    private val service = SignInService(
        auth = DashboardAuthClient(DashboardClientFactory(DashboardClientFactory.defaultBaseClient())),
        repository = stores.repository,
        cookies = stores.cookies,
        clock = { 1000 },
        newId = { "dash-1" },
    )
    private lateinit var url: DashboardUrl
    private val password = SignInMethod.Password("basic")

    @BeforeTest
    fun setUp() {
        server.start()
        url = DashboardUrl("http", server.hostName, server.port, "")
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun json(body: String, code: Int = 200, cookie: String? = null) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }
        .body(body)
        .build()

    private fun enqueueSuccessfulSignIn(cookieValue: String = "s1") {
        server.enqueue(json("""{"ok":true}""", cookie = "hermes_session_at=$cookieValue; Path=/; HttpOnly"))
        server.enqueue(json("""{"ticket":"t","ttl_seconds":30}"""))
        server.enqueue(json("""{"version":"0.22.2"}"""))
    }

    private suspend fun signIn(savePassword: Boolean = true): SignInResult = service.signInWithPassword(
        url = url,
        method = password,
        username = " me ",
        password = "pw",
        savePassword = savePassword,
        cloudflareToken = null,
    )

    /** Signs in successfully and returns the dashboard, with the sign-in requests already taken. */
    private suspend fun signedInDashboard(savePassword: Boolean = true): DashboardId {
        enqueueSuccessfulSignIn()
        val id = assertIs<SignInResult.SignedIn>(signIn(savePassword)).dashboard.id
        repeat(3) { server.takeRequest() }
        return id
    }

    @Test
    fun `invalid address fails without a request`() = runTest {
        assertEquals(
            AddressInspection.Failed(ConnectionFailure.InvalidAddress(AddressProblem.InsecureTransport), null),
            service.inspectAddress("http://hermes.example.com", null),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `inspection reports the sign-in method`() = runTest {
        server.enqueue(json("""{"providers":[{"name":"basic","supports_password":true}],"auth_flows":["cookie"]}"""))
        assertEquals(
            AddressInspection.Ready(url, password, existing = null),
            service.inspectAddress(url.toString(), null),
        )
    }

    @Test
    fun `password sign-in saves the dashboard, session and password`() = runTest {
        enqueueSuccessfulSignIn()
        val signedIn = assertIs<SignInResult.SignedIn>(signIn())
        assertEquals("0.22.2", signedIn.hermesVersion)
        assertEquals("me", signedIn.dashboard.username)

        val repo = stores.repository
        val id = signedIn.dashboard.id
        assertEquals(id, repo.activeDashboardId.first())
        assertEquals(DashboardCredentials("me", "pw"), repo.credentials(id))
        assertEquals(listOf("hermes_session_at"), repo.cookies(id).map { it.name })
    }

    @Test
    fun `password is not kept unless asked`() = runTest {
        assertNull(stores.repository.credentials(signedInDashboard(savePassword = false)))
    }

    @Test
    fun `wrong password saves nothing`() = runTest {
        server.enqueue(json("""{"detail":"Invalid credentials"}""", code = 401))
        assertEquals(SignInResult.Failed(ConnectionFailure.AuthenticationRejected), signIn())
        assertEquals(emptyList(), stores.repository.dashboards.first())
    }

    @Test
    fun `saved session is confirmed with a ticket`() = runTest {
        val id = signedInDashboard()
        server.enqueue(json("""{"ticket":"t2"}"""))
        server.enqueue(json("""{"version":"0.22.2"}"""))
        assertIs<SessionState.Ready>(service.checkSession(id))
        assertEquals("hermes_session_at=s1", server.takeRequest().headers["Cookie"])
    }

    @Test
    fun `expired session is renewed with the saved password`() = runTest {
        val id = signedInDashboard()
        server.enqueue(json("""{"error":"session_expired"}""", code = 401))
        enqueueSuccessfulSignIn(cookieValue = "s2")
        assertIs<SessionState.Ready>(service.checkSession(id))
        assertEquals("s2", stores.repository.cookies(id).single().value)
    }

    @Test
    fun `rejected saved password asks to sign in but keeps it`() = runTest {
        val id = signedInDashboard()
        server.enqueue(json("""{"error":"session_expired"}""", code = 401))
        server.enqueue(json("""{"detail":"Invalid credentials"}""", code = 401))
        val state = assertIs<SessionState.NeedsSignIn>(service.checkSession(id))
        assertEquals(ConnectionFailure.AuthenticationRejected, state.reason)
        assertEquals(DashboardCredentials("me", "pw"), stores.repository.credentials(id))
    }

    @Test
    fun `expired session without a saved password asks to sign in`() = runTest {
        val id = signedInDashboard(savePassword = false)
        server.enqueue(json("""{"error":"session_expired"}""", code = 401))
        val state = assertIs<SessionState.NeedsSignIn>(service.checkSession(id))
        assertEquals(ConnectionFailure.SignInRequired, state.reason)
        assertEquals(emptyList(), stores.repository.cookies(id))
    }

    @Test
    fun `after signing out the check asks nothing of the network and shows no error`() = runTest {
        val id = signedInDashboard()
        service.signOut(id)
        val state = assertIs<SessionState.NeedsSignIn>(service.checkSession(id))
        assertNull(state.reason)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `saved password signs in when no session is stored`() = runTest {
        val id = signedInDashboard()
        stores.cookies.clear(id)
        enqueueSuccessfulSignIn(cookieValue = "s3")
        assertIs<SessionState.Ready>(service.checkSession(id))
        assertEquals("/auth/password-login", server.takeRequest().url.encodedPath)
        assertEquals("s3", stores.repository.cookies(id).single().value)
    }

    @Test
    fun `unreachable dashboard keeps the session`() = runTest {
        val id = signedInDashboard()
        server.enqueue(MockResponse.Builder().code(502).build())
        val state = assertIs<SessionState.Unreachable>(service.checkSession(id))
        assertEquals(ConnectionFailure.DashboardUnavailable(502), state.failure)
        assertEquals(1, stores.repository.cookies(id).size)
    }

    // ---- Browser (native) sessions -------------------------------------------------------------

    /** A dashboard signed in through the browser, as SignInService leaves it. */
    private suspend fun nativeDashboard(tokens: NativeTokens): DashboardId {
        val id = DashboardId("dash-native")
        stores.repository.saveAndActivate(Dashboard(id, url, username = null, addedAt = 1), passwordProvider = null)
        stores.cookies.useTokens(id, tokens)
        return id
    }

    @Test
    fun `a browser session refused by Hermes is renewed with its refresh token`() = runTest {
        val id = nativeDashboard(NativeTokens("at-1", "rt-1", expiresAtMs = 1_000_000, provider = "basic"))
        server.enqueue(json("""{"error":"session_expired"}""", code = 401))
        server.enqueue(json("""{"access_token":"at-2","refresh_token":"rt-2","expires_at":2000,"provider":"basic"}"""))
        server.enqueue(json("""{"version":"0.22.2"}"""))
        server.enqueue(json("""{"ticket":"t2","ttl_seconds":30}"""))

        assertEquals(TicketResult.Ticket("t2"), service.mintGatewayTicket(id))
        assertEquals("Bearer at-1", server.takeRequest().headers["Authorization"])
        val refresh = server.takeRequest()
        assertEquals("/auth/native/refresh", refresh.url.encodedPath)
        assertTrue(refresh.body!!.utf8().contains("rt-1"))
        server.takeRequest()
        assertEquals("Bearer at-2", server.takeRequest().headers["Authorization"])
        assertEquals("rt-2", stores.repository.nativeTokens(id)?.refreshToken, "the rotated refresh token is kept")
    }

    @Test
    fun `a browser session whose refresh token is refused needs a new sign-in`() = runTest {
        val id = nativeDashboard(NativeTokens("at-1", "rt-1", expiresAtMs = 1_000_000, provider = "basic"))
        server.enqueue(json("""{"error":"session_expired"}""", code = 401))
        server.enqueue(json("""{"error":"session_expired"}""", code = 401))

        assertIs<TicketResult.SignInRequired>(service.mintGatewayTicket(id))
        assertNull(stores.repository.nativeTokens(id))
        assertTrue(stores.cookies.jar(id).isEmpty)
    }

    @Test
    fun `signing out of a browser session forgets its tokens`() = runTest {
        val id = nativeDashboard(NativeTokens("at-1", "rt-1", expiresAtMs = 1_000_000, provider = "basic"))
        service.signOut(id)
        assertNull(stores.repository.nativeTokens(id))
        assertNull(stores.cookies.jar(id).bearerToken)
    }
}
