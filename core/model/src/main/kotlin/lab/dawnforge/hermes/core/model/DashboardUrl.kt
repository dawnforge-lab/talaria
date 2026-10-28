package lab.dawnforge.hermes.core.model

/**
 * The normalized address of a Hermes dashboard: scheme, host, optional port and an optional base
 * path (for a dashboard served under a prefix such as `https://example.com/hermes`).
 *
 * Every endpoint is the base path followed by the endpoint path, so `/api/status` on the example
 * above is `https://example.com/hermes/api/status`. Build instances through the URL policy in
 * `core:net`, which validates them.
 */
data class DashboardUrl(
    val scheme: String,
    val host: String,
    /** The explicit port, or null for the scheme's default. */
    val port: Int?,
    /** Empty, or a path starting with "/" and never ending with one. */
    val basePath: String,
) {
    init {
        require(scheme == "http" || scheme == "https") { "Unsupported scheme $scheme" }
        require(host.isNotEmpty()) { "Missing host" }
        require(basePath.isEmpty() || (basePath.startsWith("/") && !basePath.endsWith("/"))) {
            "Bad base path $basePath"
        }
    }

    val isHttps: Boolean get() = scheme == "https"

    /** `scheme://host[:port]`, with IPv6 hosts in brackets. */
    val origin: String
        get() = buildString {
            append(scheme).append("://")
            append(if (host.contains(':')) "[$host]" else host)
            if (port != null) append(':').append(port)
        }

    /** The full URL of [path] on this dashboard; [path] must start with "/". */
    fun resolve(path: String): String {
        require(path.startsWith("/")) { "Path must start with /: $path" }
        return origin + basePath + path
    }

    override fun toString(): String = origin + basePath
}
