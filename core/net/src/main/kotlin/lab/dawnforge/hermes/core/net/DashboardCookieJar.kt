package lab.dawnforge.hermes.core.net

import kotlinx.serialization.Serializable
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * The session of one dashboard, held apart from every other dashboard's: its cookies, the
 * [bearerToken] of a native sign-in, or the [browser] store of a sign-in through the dashboard's
 * own page.
 *
 * Accepts only cookies scoped to the exact host that set them (never a parent domain), Secure
 * cookies only over https, and enforces the `__Host-` and `__Secure-` prefix rules. This matches
 * what the iOS app accepts from Hermes' password sign-in.
 *
 * [onChange] receives a snapshot after every change so the caller can persist it.
 */
class DashboardCookieJar(
    initial: List<StoredCookie> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val onChange: (List<StoredCookie>) -> Unit = {},
) : CookieJar {
    private data class Key(val name: String, val domain: String, val path: String)

    private val cookies = LinkedHashMap<Key, Cookie>()

    /**
     * The access token of a native sign-in (Hermes' native flow, under `/auth/native/`), sent as
     * `Authorization: Bearer` instead of cookies. Null for a cookie session.
     */
    @Volatile
    var bearerToken: String? = null

    /**
     * The browser's cookie store of a sign-in through the dashboard's own page (an authentication
     * proxy): while set, it holds the session instead of this jar. Null otherwise.
     */
    @Volatile
    var browser: BrowserCookieStore? = null

    init {
        val now = clock()
        initial.mapNotNull(StoredCookie::toCookie)
            .filter { it.expiresAt > now }
            .forEach { cookies[it.key()] = it }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        browser?.let { store ->
            // A proxy refreshes its cookie on ordinary requests; the browser keeps the new one.
            if (cookies.isNotEmpty()) store.save(url.toString(), cookies.map(Cookie::toString))
            return
        }
        val now = clock()
        var changed = false
        synchronized(this) {
            for (cookie in cookies) {
                if (!accepts(url, cookie)) continue
                val key = cookie.key()
                if (cookie.expiresAt <= now) {
                    changed = (this.cookies.remove(key) != null) || changed
                } else if (this.cookies[key] != cookie) {
                    this.cookies[key] = cookie
                    changed = true
                }
            }
        }
        if (changed) onChange(snapshot())
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        browser?.let { return it.cookiesFor(url) }
        val now = clock()
        var expired = false
        val matching = synchronized(this) {
            expired = cookies.values.removeAll { it.expiresAt <= now }
            cookies.values.filter { it.matches(url) }
        }
        if (expired) onChange(snapshot())
        // Longer paths first, as browsers send them.
        return matching.sortedByDescending { it.path.length }
    }

    /** Whether any cookie would be sent to [url]. */
    fun hasCookiesFor(url: HttpUrl): Boolean = loadForRequest(url).isNotEmpty()

    fun snapshot(): List<StoredCookie> = synchronized(this) { cookies.values.map(StoredCookie::from) }

    val isEmpty: Boolean get() = bearerToken == null && browser == null && synchronized(this) { cookies.isEmpty() }

    /** What a request is sent with, to tell later whether the session changed meanwhile. */
    fun mark(): SessionMark = SessionMark(snapshot(), bearerToken)

    /** Replaces every cookie with [replacement], e.g. the session of a fresh sign-in. */
    fun reset(replacement: List<StoredCookie>) {
        val now = clock()
        synchronized(this) {
            cookies.clear()
            replacement.mapNotNull(StoredCookie::toCookie)
                .filter { it.expiresAt > now }
                .forEach { cookies[it.key()] = it }
        }
        onChange(snapshot())
    }

    fun clear() {
        val hadAny = synchronized(this) {
            val any = cookies.isNotEmpty()
            cookies.clear()
            any
        }
        if (hadAny) onChange(emptyList())
    }

    private fun Cookie.key() = Key(name, domain, path)

    companion object {
        internal fun accepts(url: HttpUrl, cookie: Cookie): Boolean {
            if (!cookie.domain.equals(url.host, ignoreCase = true)) return false
            if (cookie.secure && !url.isHttps) return false
            if (cookie.name.startsWith("__Host-") && !(cookie.secure && cookie.path == "/" && cookie.hostOnly)) {
                return false
            }
            if (cookie.name.startsWith("__Secure-") && !cookie.secure) return false
            return true
        }
    }
}

/** The credentials a request went out with: compared, never sent. */
data class SessionMark(val cookies: List<StoredCookie>, val bearerToken: String?) {
    override fun toString(): String =
        "SessionMark(cookies=$cookies, bearer=${if (bearerToken == null) "none" else "<redacted>"})"
}

/** A cookie in a form that can be stored and restored. Its [value] is a secret. */
@Serializable
data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val persistent: Boolean,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
) {
    override fun toString(): String = "StoredCookie(name=$name, domain=$domain, path=$path, value=<redacted>)"

    fun toCookie(): Cookie? = runCatching {
        Cookie.Builder()
            .name(name)
            .value(value)
            .apply { if (hostOnly) hostOnlyDomain(domain) else domain(domain) }
            .path(path)
            .apply { if (persistent) expiresAt(expiresAt) }
            .apply { if (secure) secure() }
            .apply { if (httpOnly) httpOnly() }
            .build()
    }.getOrNull()

    companion object {
        fun from(cookie: Cookie) = StoredCookie(
            name = cookie.name,
            value = cookie.value,
            domain = cookie.domain,
            path = cookie.path,
            expiresAt = cookie.expiresAt,
            persistent = cookie.persistent,
            secure = cookie.secure,
            httpOnly = cookie.httpOnly,
            hostOnly = cookie.hostOnly,
        )
    }
}
