package lab.dawnforge.hermes.core.net

import okhttp3.Cookie
import okhttp3.HttpUrl

/**
 * The cookie store of a dashboard signed in through its own sign-in page in the app's browser
 * (an authentication proxy in front of Hermes): the browser keeps the session, as iOS keeps it in
 * the dashboard's WebKit store, so a proxy's cookies keep their own domain and expiry, and the
 * app's requests and the browser share every refresh.
 */
interface BrowserCookieStore {
    /** The `Cookie` header the browser would send to [url], or null when it has none. */
    fun cookieHeader(url: String): String?

    /** Stores the `Set-Cookie` [headers] a response from [url] carried. */
    fun save(url: String, headers: List<String>)
}

/** The browser's cookies for [url], as OkHttp sends them: name and value only, which is all a request carries. */
internal fun BrowserCookieStore.cookiesFor(url: HttpUrl): List<Cookie> = cookieHeader(url.toString()).orEmpty()
    .split(';')
    .mapNotNull { pair ->
        val name = pair.substringBefore('=').trim()
        val value = pair.substringAfter('=', missingDelimiterValue = "").trim()
        if (name.isEmpty()) return@mapNotNull null
        runCatching { Cookie.Builder().name(name).value(value).hostOnlyDomain(url.host).build() }.getOrNull()
    }
