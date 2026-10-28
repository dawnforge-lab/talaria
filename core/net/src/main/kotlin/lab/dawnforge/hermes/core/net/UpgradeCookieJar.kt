package lab.dawnforge.hermes.core.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * The cookies a dashboard's WebSocket upgrades carry: only a browser session's, whose sign-in proxy
 * admits the upgrade with its own cookie. Read at each upgrade, so a sign-in after the socket's
 * client was made still counts. Every other session's upgrade goes with its ticket alone.
 */
class UpgradeCookieJar(private val jar: DashboardCookieJar) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        if (jar.browser != null) jar.loadForRequest(url) else emptyList()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (jar.browser != null) jar.saveFromResponse(url, cookies)
    }
}
