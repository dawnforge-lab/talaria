package lab.dawnforge.hermes.core.net

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lab.dawnforge.hermes.core.model.DashboardUrl
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class NativeSignInTest {
    private val server = MockWebServer()
    private lateinit var dashboard: DashboardUrl
    private val factory = DashboardClientFactory(DashboardClientFactory.defaultBaseClient())
    private val client = DashboardAuthClient(factory)

    @BeforeTest
    fun setUp() {
        server.start()
        dashboard = DashboardUrl("http", server.hostName, server.port, basePath = "")
    }

    @AfterTest
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `the challenge is the SHA-256 of the verifier, base64url without padding`() {
        val pkce = Pkce.create()
        assertTrue(pkce.verifier.length in 43..128)
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(pkce.verifier.toByteArray()))
        assertEquals(expected, pkce.challenge)
        assertEquals("Pkce(<redacted>)", pkce.toString())
    }

    @Test
    fun `the authorize URL carries the challenge, the loopback redirect and the state`() {
        val pkce = Pkce.create()
        val url = client.nativeAuthorizeUrl(dashboard, pkce, "http://127.0.0.1:5000/callback", "st", "basic")
            .toHttpUrl()
        assertEquals("/auth/native/authorize", url.encodedPath)
        assertEquals(pkce.challenge, url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("http://127.0.0.1:5000/callback", url.queryParameter("redirect_uri"))
        assertEquals("st", url.queryParameter("state"))
        assertEquals("basic", url.queryParameter("provider"))
        assertNull(
            client.nativeAuthorizeUrl(dashboard, pkce, "http://127.0.0.1:1/callback", "s", null)
                .toHttpUrl().queryParameter("provider"),
        )
    }

    @Test
    fun `the receiver hands over the code of its own sign-in and turns everything else away`() = runBlocking {
        LoopbackReceiver.open().use { receiver ->
            assertTrue(receiver.redirectUri.startsWith("http://127.0.0.1:"))
            assertTrue(receiver.redirectUri.endsWith("/callback"))
            val waiting = async { receiver.await("good-state", timeoutMs = 10_000) }
            val browser = OkHttpClient()
            val favicon = get(browser, receiver.redirectUri.replace("/callback", "/favicon.ico"))
            assertEquals(404, favicon)
            assertEquals(404, get(browser, "${receiver.redirectUri}?code=stale&state=other"))
            assertEquals(200, get(browser, "${receiver.redirectUri}?code=a%2Bcode&state=good-state"))
            assertEquals(LoopbackReceiver.Callback.Code("a+code"), waiting.await())
        }
    }

    @Test
    fun `a refused sign-in comes back as such, and a sign-in never finished times out`() = runBlocking {
        LoopbackReceiver.open().use { receiver ->
            val waiting = async { receiver.await("s", timeoutMs = 10_000) }
            get(OkHttpClient(), "${receiver.redirectUri}?error=access_denied&state=s")
            assertEquals(LoopbackReceiver.Callback.Refused("access_denied"), waiting.await())
        }
        LoopbackReceiver.open().use { receiver ->
            assertNull(receiver.await("s", timeoutMs = 600))
        }
    }

    private suspend fun get(client: OkHttpClient, url: String): Int = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { it.code }
    }

    @Test
    fun `a redeemed code gives bearer tokens, and a refused one asks for a new sign-in`() = runTest {
        server.enqueue(
            json(
                """{"access_token":"at-1","refresh_token":"rt-1","token_type":"Bearer","expires_at":1761000000,""" +
                    """"provider":"basic","user_id":"admin"}""",
            ),
        )
        val pkce = Pkce.create()
        val tokens = (client.redeemNativeCode(dashboard, null, "code-1", pkce) as NetResult.Success).value
        assertEquals(NativeTokens("at-1", "rt-1", 1_761_000_000_000, "basic", "admin"), tokens)
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("code-1", body["code"]!!.jsonPrimitive.content)
        assertEquals(pkce.verifier, body["code_verifier"]!!.jsonPrimitive.content)

        server.enqueue(json("""{"detail":"Invalid or expired authorization code."}""", code = 400))
        assertEquals(
            NetResult.Failure(ConnectionFailure.SignInRequired),
            client.redeemNativeCode(dashboard, null, "code-1", pkce),
        )
    }

    @Test
    fun `a refresh sends its token, and with no refresh token a sign-in is needed`() = runTest {
        server.enqueue(
            json("""{"access_token":"at-2","refresh_token":"","expires_at":1761000600,"provider":"basic"}"""),
        )
        val old = NativeTokens("at-1", "rt-1", 0, "basic")
        val renewed = (client.refreshNativeTokens(dashboard, null, old) as NetResult.Success).value
        assertEquals("at-2", renewed.accessToken)
        assertNull(renewed.refreshToken, "an empty refresh token is none")
        val request = server.takeRequest()
        assertEquals("/auth/native/refresh", request.url.encodedPath)
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("rt-1", body["refresh_token"]!!.jsonPrimitive.content)

        server.enqueue(json("""{"error":"session_expired"}""", code = 401))
        assertIs<NetResult.Failure>(client.refreshNativeTokens(dashboard, null, old))
        assertEquals(
            NetResult.Failure(ConnectionFailure.SignInRequired),
            client.refreshNativeTokens(dashboard, null, old.copy(refreshToken = null)),
        )
    }

    @Test
    fun `the bearer token goes to the dashboard's own origin only, over http too`() = runTest {
        val jar = DashboardCookieJar().apply { bearerToken = "at-1" }
        server.enqueue(json("""{"ticket":"t"}"""))
        client.mintTicket(dashboard, jar, null)
        assertEquals("Bearer at-1", server.takeRequest().headers["Authorization"])

        val interceptor = DashboardHeadersInterceptor(dashboard, cloudflareToken = { null }, bearerToken = { "at-1" })
        assertTrue(interceptor.isDashboard("http://${dashboard.host}:${dashboard.port}/api/ws".toHttpUrl()))
        assertTrue(!interceptor.isDashboard("http://evil.example.com:${dashboard.port}/".toHttpUrl()))
        assertTrue(!interceptor.isDashboard("http://${dashboard.host}:1/".toHttpUrl()))

        server.enqueue(json("""{"ticket":"t"}"""))
        client.mintTicket(dashboard, DashboardCookieJar(), null)
        assertNull(server.takeRequest().headers["Authorization"], "a cookie session sends none")
    }

    @Test
    fun `a session mark tells a renewed session from the one a request went out with`() {
        val jar = DashboardCookieJar().apply { bearerToken = "at-1" }
        val sent = jar.mark()
        assertTrue(!jar.isEmpty)
        jar.bearerToken = "at-2"
        assertTrue(sent != jar.mark())
        assertTrue("at-1" !in sent.toString())
    }
}
