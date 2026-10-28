package lab.dawnforge.hermes.feature.connect.web

import java.net.URI
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.net.ConnectionUrlPolicy

/**
 * Where the sign-in page may go, and when it has signed in, as iOS's `AuthWebView` decides:
 * - before the dashboard has shown, the page may go anywhere the app's address rules allow (https,
 *   or http to a local, private or Tailscale address), so a sign-in service's redirects work;
 * - once a dashboard page has shown, only the dashboard's own origin.
 *
 * A dashboard page that isn't a sign-in page (no `login`, `signin`, `sign_in` or `sign-in` part,
 * no `oauth2/start`) is the moment to check whether the sign-in worked.
 */
object WebSignInPolicy {
    private val signInParts = setOf("login", "signin", "sign_in", "sign-in")

    fun isSignInPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.split('/').any { it in signInParts } || lower.contains("oauth2/start")
    }

    /** A page of the dashboard itself, past its sign-in. */
    fun isSignedInDashboardPage(url: String, dashboard: DashboardUrl): Boolean {
        val uri = parse(url) ?: return false
        return sameOrigin(uri, dashboard) && !isSignInPath(uri.rawPath.orEmpty())
    }

    /** Whether the page may open [url] in its main frame; [pinned] once the dashboard has shown. */
    fun allowsNavigation(url: String, dashboard: DashboardUrl, pinned: Boolean): Boolean {
        val uri = parse(url) ?: return false
        if (uri.rawUserInfo != null) return false
        val scheme = uri.scheme?.lowercase()
        val host = uri.host ?: return false
        val transport = scheme == "https" || (scheme == "http" && ConnectionUrlPolicy.allowsCleartext(host))
        return transport && (!pinned || sameOrigin(uri, dashboard))
    }

    private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()

    private fun sameOrigin(uri: URI, dashboard: DashboardUrl): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme == dashboard.scheme.lowercase() &&
            uri.host.equals(dashboard.host, ignoreCase = true) &&
            effectivePort(scheme, uri.port) == effectivePort(dashboard.scheme.lowercase(), dashboard.port ?: -1)
    }

    private fun effectivePort(scheme: String, port: Int): Int = when {
        port != -1 -> port
        scheme == "https" -> HTTPS_PORT
        else -> HTTP_PORT
    }

    private const val HTTPS_PORT = 443
    private const val HTTP_PORT = 80
}
