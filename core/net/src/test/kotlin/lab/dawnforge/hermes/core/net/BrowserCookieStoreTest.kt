package lab.dawnforge.hermes.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

class BrowserCookieStoreTest {
    private class FakeStore(var header: String? = null) : BrowserCookieStore {
        val saved = mutableListOf<String>()

        override fun cookieHeader(url: String): String? = header

        override fun save(url: String, headers: List<String>) {
            saved += headers
        }
    }

    private val url = "https://hermes.example.com/api/status".toHttpUrl()

    @Test
    fun `a browser session's jar sends and keeps the browser's cookies`() {
        val store = FakeStore("_oauth2_proxy=abc; theme=dark")
        val jar = DashboardCookieJar().apply { browser = store }

        assertEquals(
            listOf("_oauth2_proxy" to "abc", "theme" to "dark"),
            jar.loadForRequest(url).map {
                it.name to
                    it.value
            },
        )
        assertFalse(jar.isEmpty)

        val refreshed = Cookie.parse(url, "_oauth2_proxy=new; Domain=example.com; Path=/; Secure; HttpOnly")!!
        jar.saveFromResponse(url, listOf(refreshed))
        assertEquals(1, store.saved.size)
        assertTrue(store.saved.single().startsWith("_oauth2_proxy=new"))
        // The jar's own store stays empty: the browser holds the session.
        assertEquals(emptyList(), jar.snapshot())
    }

    @Test
    fun `an upgrade carries a browser session's cookies only`() {
        val plain = DashboardCookieJar(listOf(cookie()))
        assertEquals(emptyList(), UpgradeCookieJar(plain).loadForRequest(url))

        val browser = DashboardCookieJar().apply { this.browser = FakeStore("CF_Authorization=jwt") }
        assertEquals(listOf("CF_Authorization"), UpgradeCookieJar(browser).loadForRequest(url).map { it.name })
    }

    private fun cookie() =
        StoredCookie("hermes_session_at", "s", "hermes.example.com", "/", 4_102_444_800_000, true, true, true, true)
}
