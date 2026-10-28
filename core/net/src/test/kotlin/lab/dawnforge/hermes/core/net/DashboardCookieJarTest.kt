package lab.dawnforge.hermes.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

class DashboardCookieJarTest {
    // Cookie.parse() computes Max-Age expiry from the real clock, so the test clock starts there.
    private var now = System.currentTimeMillis()
    private val changes = mutableListOf<List<StoredCookie>>()
    private val jar = DashboardCookieJar(clock = { now }, onChange = { changes += it })

    private fun cookie(url: HttpUrl, header: String): Cookie = Cookie.parse(url, header) ?: error("Bad cookie $header")

    private val https = "https://hermes.example.com/api/status".toHttpUrl()
    private val http = "http://192.168.1.5:9119/api/status".toHttpUrl()

    @Test
    fun `host-only session cookie is stored and sent back`() {
        jar.saveFromResponse(https, listOf(cookie(https, "hermes_session_at=abc; Path=/; HttpOnly; Secure")))
        assertEquals(listOf("hermes_session_at"), jar.loadForRequest(https).map { it.name })
        assertEquals(1, changes.size)
    }

    @Test
    fun `cookie for a parent domain is refused`() {
        val sub = "https://hermes.example.com/".toHttpUrl()
        jar.saveFromResponse(sub, listOf(cookie(sub, "a=1; Domain=example.com; Path=/")))
        assertTrue(jar.loadForRequest(sub).isEmpty())
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `cookie with Domain equal to the host is accepted`() {
        jar.saveFromResponse(https, listOf(cookie(https, "a=1; Domain=hermes.example.com; Path=/")))
        assertEquals(1, jar.loadForRequest(https).size)
    }

    @Test
    fun `secure cookie over http is refused`() {
        jar.saveFromResponse(http, listOf(cookie(http, "a=1; Secure; Path=/")))
        assertTrue(jar.loadForRequest(http).isEmpty())
    }

    @Test
    fun `cookie prefixes are enforced`() {
        jar.saveFromResponse(
            https,
            listOf(
                cookie(https, "__Host-ok=1; Secure; Path=/"),
                cookie(https, "__Host-nopath=1; Secure; Path=/api"),
                cookie(https, "__Host-domain=1; Secure; Path=/; Domain=hermes.example.com"),
                cookie(https, "__Secure-ok=1; Secure; Path=/"),
                cookie(https, "__Secure-insecure=1; Path=/"),
            ),
        )
        assertEquals(setOf("__Host-ok", "__Secure-ok"), jar.loadForRequest(https).map { it.name }.toSet())
    }

    @Test
    fun `expired cookies are dropped and an expiring Set-Cookie deletes`() {
        jar.saveFromResponse(https, listOf(cookie(https, "a=1; Path=/; Max-Age=60")))
        assertEquals(1, jar.loadForRequest(https).size)
        now += 61_000
        assertTrue(jar.loadForRequest(https).isEmpty())

        jar.saveFromResponse(https, listOf(cookie(https, "b=1; Path=/")))
        jar.saveFromResponse(https, listOf(cookie(https, "b=; Path=/; Max-Age=0")))
        assertTrue(jar.loadForRequest(https).isEmpty())
    }

    @Test
    fun `snapshot restores into a new jar`() {
        jar.saveFromResponse(https, listOf(cookie(https, "hermes_session_at=abc; Path=/; HttpOnly; Secure")))
        val restored = DashboardCookieJar(initial = jar.snapshot(), clock = { now })
        val sent = restored.loadForRequest(https).single()
        assertEquals("abc", sent.value)
        assertTrue(sent.httpOnly && sent.secure && sent.hostOnly)
    }

    @Test
    fun `cookie values are never printed`() {
        jar.saveFromResponse(https, listOf(cookie(https, "a=topsecret; Path=/")))
        assertTrue(jar.snapshot().none { it.toString().contains("topsecret") })
    }
}
