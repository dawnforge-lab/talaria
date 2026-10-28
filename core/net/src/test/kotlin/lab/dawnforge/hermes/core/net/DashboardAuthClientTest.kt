package lab.dawnforge.hermes.core.net

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import lab.dawnforge.hermes.core.model.CloudflareAccessToken
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.model.SignInMethod
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl

class DashboardAuthClientTest {
    private val server = MockWebServer()
    private lateinit var dashboard: DashboardUrl
    private val client = DashboardAuthClient(DashboardClientFactory(DashboardClientFactory.defaultBaseClient()))

    @BeforeTest
    fun setUp() {
        server.start()
        dashboard = DashboardUrl("http", server.hostName, server.port, basePath = "")
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun <T> NetResult<T>.value(): T =
        (this as? NetResult.Success)?.value ?: error("Expected success, got $this")

    private fun NetResult<*>.failure(): ConnectionFailure =
        (this as? NetResult.Failure)?.failure ?: error("Expected failure, got $this")

    // Discovery

    @Test
    fun `password provider means password sign-in`() = runTest {
        server.enqueue(
            json(
                """{"providers":[{"name":"basic","display_name":"Password","supports_password":true}],"auth_flows":["cookie"]}""",
            ),
        )
        val method = client.discoverSignInMethod(dashboard, null).value()
        assertEquals(SignInMethod.Password("basic"), method)
        val request = server.takeRequest()
        assertEquals("/api/auth/providers", request.url.encodedPath)
        assertNull(request.headers["Cookie"])
        assertEquals("application/json", request.headers["Accept"])
    }

    @Test
    fun `password plus OAuth provider offers both, the browser for the OAuth one`() = runTest {
        server.enqueue(
            json(
                """{"providers":[{"name":"basic","supports_password":true},""" +
                    """{"name":"google","supports_password":false}],"auth_flows":["cookie","native_pkce"]}""",
            ),
        )
        assertEquals(
            SignInMethod.Password("basic", browser = SignInMethod.NativeOAuth("google")),
            client.discoverSignInMethod(dashboard, null).value(),
        )
    }

    @Test
    fun `password plus several OAuth providers leaves the browser's choice to Hermes`() = runTest {
        server.enqueue(
            json(
                """{"providers":[{"name":"basic","supports_password":true},""" +
                    """{"name":"self-hosted","supports_password":false},""" +
                    """{"name":"nous","supports_password":false}],"auth_flows":["cookie","native_pkce"]}""",
            ),
        )
        assertEquals(
            SignInMethod.Password("basic", browser = SignInMethod.NativeOAuth(null)),
            client.discoverSignInMethod(dashboard, null).value(),
        )
    }

    @Test
    fun `an OAuth provider without the native flow offers no browser beside the password`() = runTest {
        server.enqueue(
            json(
                """{"providers":[{"name":"basic","supports_password":true},""" +
                    """{"name":"google","supports_password":false}]}""",
            ),
        )
        server.enqueue(json("""{"version":"0.22.2","auth_required":true,"auth_flows":["cookie"]}"""))
        assertEquals(SignInMethod.Password("basic"), client.discoverSignInMethod(dashboard, null).value())
        server.takeRequest()
        assertEquals("/api/status", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `a password-only dashboard offers no browser, native flow or not`() = runTest {
        // The browser would only show Hermes' own password form, which the app has (as iOS).
        server.enqueue(
            json("""{"providers":[{"name":"basic","supports_password":true}],"auth_flows":["cookie","native_pkce"]}"""),
        )
        assertEquals(SignInMethod.Password("basic"), client.discoverSignInMethod(dashboard, null).value())
        // Nothing else is asked: the flows wouldn't be used.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `native provider with native_pkce means native OAuth`() = runTest {
        server.enqueue(
            json(
                """{"providers":[{"name":"google","supports_password":false}],"auth_flows":["cookie","native_pkce"]}""",
            ),
        )
        assertEquals(SignInMethod.NativeOAuth("google"), client.discoverSignInMethod(dashboard, null).value())
    }

    @Test
    fun `native support is read from status when providers do not say`() = runTest {
        server.enqueue(
            json("""{"providers":[{"name":"a","supports_password":false},{"name":"b","supports_password":false}]}"""),
        )
        server.enqueue(json("""{"version":"0.22.2","auth_flows":["cookie","native_pkce"]}"""))
        assertEquals(SignInMethod.NativeOAuth(null), client.discoverSignInMethod(dashboard, null).value())
        server.takeRequest()
        assertEquals("/api/status", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `OAuth provider without native flow means browser sign-in`() = runTest {
        server.enqueue(json("""{"providers":[{"name":"google","supports_password":false}]}"""))
        server.enqueue(json("""{"auth_flows":["cookie"]}"""))
        assertEquals(SignInMethod.Browser, client.discoverSignInMethod(dashboard, null).value())
    }

    @Test
    fun `no usable provider is reported`() = runTest {
        server.enqueue(json("""{"providers":[]}"""))
        assertEquals(ConnectionFailure.NoSignInMethod, client.discoverSignInMethod(dashboard, null).failure())
    }

    @Test
    fun `redirect to a sign-in page means an auth proxy`() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/oauth2/start?rd=/").build())
        assertEquals(
            ConnectionFailure.InteractiveSignInRequired,
            client.discoverSignInMethod(dashboard, null).failure(),
        )
    }

    @Test
    fun `redirect to Cloudflare Access with a token means the token was rejected`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(302)
                .addHeader("Location", "https://team.cloudflareaccess.com/cdn-cgi/access/login")
                .build(),
        )
        val token = CloudflareAccessToken("id.access", "secret")
        assertEquals(ConnectionFailure.CloudflareTokenRejected, client.discoverSignInMethod(dashboard, token).failure())
    }

    @Test
    fun `same-origin redirect is followed`() = runTest {
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "/api/auth/providers/").build())
        server.enqueue(json("""{"providers":[{"name":"basic","supports_password":true}],"auth_flows":["cookie"]}"""))
        assertEquals(SignInMethod.Password("basic"), client.discoverSignInMethod(dashboard, null).value())
    }

    @Test
    fun `something other than Hermes is reported`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("<html>router</html>").build())
        assertEquals(ConnectionFailure.NotADashboard(200), client.discoverSignInMethod(dashboard, null).failure())
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals(ConnectionFailure.NotADashboard(404), client.discoverSignInMethod(dashboard, null).failure())
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(
            ConnectionFailure.DashboardUnavailable(503),
            client.discoverSignInMethod(dashboard, null).failure(),
        )
    }

    @Test
    fun `requests keep the base path`() = runTest {
        val prefixed = dashboard.copy(basePath = "/hermes")
        server.enqueue(json("""{"providers":[{"name":"basic","supports_password":true}],"auth_flows":["cookie"]}"""))
        client.discoverSignInMethod(prefixed, null).value()
        assertEquals("/hermes/api/auth/providers", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `dashboard with sign-in off is reported as such`() = runTest {
        // What Hermes 0.22.2 answers when bound to 127.0.0.1 with no dashboard.public_url.
        server.enqueue(json("""{"error":"unauthenticated"}""", code = 401))
        server.enqueue(json("""{"version":"0.22.2","auth_required":false,"auth_providers":[],"auth_flows":[]}"""))
        assertEquals(ConnectionFailure.SignInDisabled, client.discoverSignInMethod(dashboard, null).failure())
        server.takeRequest()
        assertEquals("/api/status", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `a 401 from a dashboard that requires sign-in stays a plain error`() = runTest {
        server.enqueue(json("""{"detail":"nope"}""", code = 401))
        server.enqueue(json("""{"version":"0.22.2","auth_required":true}"""))
        assertEquals(
            ConnectionFailure.UnexpectedResponse(401, "nope"),
            client.discoverSignInMethod(dashboard, null).failure(),
        )
    }

    @Test
    fun `refused connection is classified`() = runTest {
        val closed = dashboard
        server.close()
        assertEquals(ConnectionFailure.ConnectionRefused, client.discoverSignInMethod(closed, null).failure())
    }

    // Password sign-in and ticket

    @Test
    fun `password sign-in stores the session cookie and the ticket uses it`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Set-Cookie", "hermes_session_at=abc; Path=/; HttpOnly")
                .addHeader("Content-Type", "application/json")
                .body("""{"ok":true,"next":"/"}""")
                .build(),
        )
        server.enqueue(json("""{"ticket":"t-123","ttl_seconds":30}"""))

        val jar = DashboardCookieJar()
        client.passwordLogin(dashboard, jar, null, "basic", "me", "p@ss \"word\"").value()
        assertEquals("t-123", client.mintTicket(dashboard, jar, null).value())

        val login = server.takeRequest()
        assertEquals("POST", login.method)
        assertEquals("/auth/password-login", login.url.encodedPath)
        assertEquals(
            """{"provider":"basic","username":"me","password":"p@ss \"word\""}""",
            login.body?.utf8(),
        )
        val ticket = server.takeRequest()
        assertEquals("/api/auth/ws-ticket", ticket.url.encodedPath)
        assertEquals("hermes_session_at=abc", ticket.headers["Cookie"])
    }

    @Test
    fun `wrong password and rate limit are classified`() = runTest {
        server.enqueue(json("""{"detail":"Invalid credentials"}""", code = 401))
        assertEquals(
            ConnectionFailure.AuthenticationRejected,
            client.passwordLogin(dashboard, DashboardCookieJar(), null, "basic", "me", "x").failure(),
        )
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "42").build())
        assertEquals(
            ConnectionFailure.RateLimited(42),
            client.passwordLogin(dashboard, DashboardCookieJar(), null, "basic", "me", "x").failure(),
        )
    }

    @Test
    fun `sign-in without a session cookie is a ticket failure, not a bad password`() = runTest {
        server.enqueue(json("""{"ok":true}"""))
        val failure = client.passwordLogin(dashboard, DashboardCookieJar(), null, "basic", "me", "x").failure()
        assertTrue(failure is ConnectionFailure.SessionTicketFailure)
    }

    @Test
    fun `ticket refusals are told apart from expired sessions`() = runTest {
        server.enqueue(json("""{"error":"unauthenticated"}""", code = 401))
        assertEquals(
            ConnectionFailure.SignInRequired,
            client.mintTicket(dashboard, DashboardCookieJar(), null).failure(),
        )
        server.enqueue(MockResponse.Builder().code(403).build())
        assertEquals(
            ConnectionFailure.SignInRequired,
            client.mintTicket(dashboard, DashboardCookieJar(), null).failure(),
        )
        server.enqueue(json("""{"detail":"profile_mismatch"}""", code = 403))
        assertEquals(
            ConnectionFailure.Refused("profile_mismatch"),
            client.mintTicket(dashboard, DashboardCookieJar(), null).failure(),
        )
        server.enqueue(json("""{"ticket":""}"""))
        val emptyTicket = client.mintTicket(dashboard, DashboardCookieJar(), null).failure()
        assertTrue(emptyTicket is ConnectionFailure.SessionTicketFailure)
    }

    // Headers

    @Test
    fun `Cloudflare headers go only to the dashboard over https`() = runTest {
        val token = CloudflareAccessToken(" id.access ", "secret")
        server.enqueue(json("""{"version":"1"}"""))
        client.status(dashboard, token).value()
        assertNull(server.takeRequest().headers["CF-Access-Client-Id"], "never over plain http")

        val secure = DashboardUrl("https", "hermes.example.com", null, "")
        val interceptor = DashboardHeadersInterceptor(secure, cloudflareToken = { token })
        assertTrue(interceptor.sendsSecretsTo("https://hermes.example.com/api/ws".toHttpUrl()))
        assertFalse(interceptor.sendsSecretsTo("https://hermes.example.com:8443/".toHttpUrl()))
        assertFalse(interceptor.sendsSecretsTo("https://evil.example.com/".toHttpUrl()))
        assertFalse(interceptor.sendsSecretsTo("http://hermes.example.com/".toHttpUrl()))
    }

    @Test
    fun `status reads the version`() = runTest {
        server.enqueue(json("""{"version":"0.22.2","auth_flows":["cookie"],"gateway_running":true}"""))
        assertEquals(DashboardStatus("0.22.2", listOf("cookie"), true), client.status(dashboard, null).value())
    }
}
