package lab.dawnforge.hermes.core.net

import lab.dawnforge.hermes.core.model.CloudflareAccessToken
import lab.dawnforge.hermes.core.model.DashboardUrl
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds the headers every request to a dashboard carries: `Accept: application/json` unless set,
 * the Cloudflare Access service token, and the bearer token of a native sign-in. Both go only to
 * the dashboard's own origin, so a redirect can never leak them; the Cloudflare token only over
 * https (and wss, which OkHttp sends as https). The bearer token goes wherever the session
 * cookies would, so over http too for an address the connection policy allows it for.
 */
class DashboardHeadersInterceptor(
    private val dashboard: DashboardUrl,
    private val cloudflareToken: () -> CloudflareAccessToken?,
    private val bearerToken: () -> String? = { null },
    private val proxyHeaders: ProxyHeaders = ProxyHeaders.NONE,
) : Interceptor {
    private val origin = ProxyHeaderRules.origin(dashboard)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()
        if (request.header("Accept") == null) builder.header("Accept", "application/json")
        val token = cloudflareToken()
        if (token != null && token.isConfigured && sendsSecretsTo(request.url)) {
            builder.header("CF-Access-Client-Id", token.clientId.trim())
            builder.header("CF-Access-Client-Secret", token.clientSecret)
        }
        val bearer = bearerToken()
        if (bearer != null && request.header("Authorization") == null && isDashboard(request.url)) {
            builder.header("Authorization", "Bearer $bearer")
        }
        // A reverse proxy's extra headers, over HTTPS to the dashboard only, never over one the app set.
        val built = builder.build()
        if (origin == null || !sendsSecretsTo(request.url)) return chain.proceed(built)
        val extra = built.newBuilder()
        for (header in proxyHeaders.forOrigin(origin)) {
            if (built.header(header.name) == null) extra.header(header.name, header.value)
        }
        return chain.proceed(extra.build())
    }

    internal fun sendsSecretsTo(url: HttpUrl): Boolean = url.isHttps && isDashboard(url)

    internal fun isDashboard(url: HttpUrl): Boolean = url.scheme.httpScheme() == dashboard.scheme &&
        url.host.equals(dashboard.host, ignoreCase = true) &&
        url.port == (dashboard.port ?: HttpUrl.defaultPort(dashboard.scheme))

    /** OkHttp reports a WebSocket's ws/wss URL as http/https already; this only guards the comparison. */
    private fun String.httpScheme() = when (this) {
        "ws" -> "http"
        "wss" -> "https"
        else -> this
    }
}
