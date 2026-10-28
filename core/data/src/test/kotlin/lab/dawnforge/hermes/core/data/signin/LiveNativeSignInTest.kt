package lab.dawnforge.hermes.core.data.signin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.LiveHermes
import lab.dawnforge.hermes.core.data.TestStores
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.session.SessionPage
import lab.dawnforge.hermes.core.data.session.SessionRepository
import lab.dawnforge.hermes.core.gateway.TicketResult
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.net.AddressCheck
import lab.dawnforge.hermes.core.net.ConnectionUrlPolicy
import lab.dawnforge.hermes.core.net.DashboardApi
import lab.dawnforge.hermes.core.net.DashboardAuthClient
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import lab.dawnforge.hermes.core.net.NetResult
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue

/**
 * Hermes' native sign-in (PKCE with a loopback redirect) against a real dashboard, through the
 * app's SignInService. Skipped unless `-Phermes.live.file` is set. The browser's part is played
 * here: Hermes' password provider shows its own form, which answers with the loopback redirect.
 */
class LiveNativeSignInTest {
    @Test
    fun `signs in through the native flow, and renews the session when its token is refused`(): Unit = runBlocking {
        val config = LiveHermes.config
        assumeTrue("set -Phermes.live.file to run against a real Hermes", config != null)
        val url = (ConnectionUrlPolicy.check(config!!.getProperty("url")) as AddressCheck.Valid).candidates.first()
        val stores = TestStores()
        val factory = DashboardClientFactory(DashboardClientFactory.defaultBaseClient())
        val auth = DashboardAuthClient(factory)
        val signIn = SignInService(auth, stores.repository, stores.cookies)

        val browser = OkHttpClient.Builder()
            .cookieJar(BrowserCookies())
            .followRedirects(false)
            .build()
        val result = signIn.signInWithBrowser(url, providerName = "basic", cloudflareToken = null) { authorize ->
            playBrowser(browser, authorize, url.resolve("/auth/password-login"), config)
        }
        val signedIn = assertIs<SignInResult.SignedIn>(result, "signed in: $result")
        val id = signedIn.dashboard.id
        val tokens = assertNotNull(stores.repository.nativeTokens(id))
        println("live: signed in natively as ${tokens.userId} via ${tokens.provider}; expires ${tokens.expiresAtMs}")
        assertEquals(null, stores.repository.credentials(id), "no password is kept for a browser sign-in")

        val requests = DashboardRequests(DashboardApi(factory), stores.repository, stores.cookies, signIn)
        val sessions = SessionRepository(requests) { null }
        assertIs<NetResult.Success<SessionPage>>(
            sessions.page(id, Profile.DEFAULT, false, 0, 5),
            "REST works with the bearer",
        )
        assertIs<TicketResult.Ticket>(signIn.mintGatewayTicket(id), "and so does the chat socket's ticket")

        // A token Hermes no longer takes: the next request is refused, renewed and retried.
        stores.cookies.jar(id).bearerToken = "no-longer-valid"
        assertIs<NetResult.Success<SessionPage>>(sessions.page(id, Profile.DEFAULT, false, 0, 5), "renewed and retried")
        val renewed = assertNotNull(stores.repository.nativeTokens(id))
        // Hermes' password provider signs stateless tokens from the user and the expiry, so a renewal
        // within the same second can give the same token back; what matters is the refused one is gone.
        assertNotEquals("no-longer-valid", stores.cookies.jar(id).bearerToken, "the refused token was replaced")
        assertEquals(renewed.accessToken, stores.cookies.jar(id).bearerToken)
        println("live: a refused token was renewed with the refresh token")

        // A refresh token Hermes refuses too: back to signing in.
        stores.repository.saveNativeTokens(id, renewed.copy(refreshToken = "no-longer-valid"))
        stores.cookies.jar(id).bearerToken = "no-longer-valid"
        assertIs<NetResult.Failure>(sessions.page(id, Profile.DEFAULT, false, 0, 5))
        assertIs<TicketResult.SignInRequired>(signIn.mintGatewayTicket(id))
        assertEquals(null, stores.repository.nativeTokens(id), "the dead session is dropped")
        println("live: a refused refresh token asks for a new sign-in")
    }

    /**
     * What the browser does: open the authorize URL (Hermes answers with its sign-in form for a
     * password provider), send the form, and follow the loopback redirect it answers with.
     */
    private suspend fun playBrowser(
        browser: OkHttpClient,
        authorize: String,
        loginUrl: String,
        config: java.util.Properties,
    ) = withContext(Dispatchers.IO) {
        browser.newCall(Request.Builder().url(authorize).build()).execute().use { reply ->
            assertEquals(302, reply.code, "authorize redirects to the sign-in form")
            assertTrue(reply.header("Location").orEmpty().endsWith("/login"), reply.header("Location"))
        }
        val form = buildJsonObject {
            put("provider", "basic")
            put("username", config.getProperty("username"))
            put("password", config.getProperty("password"))
        }.toString().toRequestBody("application/json".toMediaType())
        val next = browser.newCall(Request.Builder().url(loginUrl).post(form).build()).execute().use { reply ->
            assertEquals(200, reply.code)
            Json.parseToJsonElement(reply.body.string()).jsonObject["next"]!!.jsonPrimitive.content
        }
        assertTrue(next.startsWith("http://127.0.0.1:"), "the form answers with the loopback redirect")
        browser.newCall(Request.Builder().url(next).build()).execute().use { assertEquals(200, it.code) }
    }

    /** A browser's cookies, enough for one sign-in: Hermes' PKCE cookie between the form and its answer. */
    private class BrowserCookies : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(this) {
                cookies.forEach { cookie -> this.cookies.removeAll { it.name == cookie.name } }
                this.cookies += cookies
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(this) {
            cookies.filter { it.matches(url) }
        }
    }
}
