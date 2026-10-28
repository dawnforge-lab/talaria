package lab.dawnforge.hermes.core.net

import kotlinx.serialization.Serializable
import lab.dawnforge.hermes.core.model.DashboardUrl

/** An extra HTTP header a reverse proxy in front of Hermes asks for. */
@Serializable
data class ProxyHeader(val name: String, val value: String)

/** The extra headers for an origin (`https://host[:port]`), as the user saved them; none for others. */
fun interface ProxyHeaders {
    fun forOrigin(origin: String): List<ProxyHeader>

    companion object {
        val NONE = ProxyHeaders { emptyList() }
    }
}

/** What's wrong with one header row, if anything. */
enum class ProxyHeaderProblem { EmptyName, InvalidName, Reserved, InvalidValue, Duplicate }

/**
 * iOS's rules for extra headers: a token name the app doesn't manage itself, a value without line
 * breaks, each name once, and only ever over HTTPS to the dashboard's own origin.
 */
object ProxyHeaderRules {
    private val TOKEN = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")
    private val RESERVED = setOf(
        "host", "cookie", "content-length", "content-type", "transfer-encoding", "connection", "upgrade",
        "keep-alive", "proxy-connection", "te", "trailer", "expect", "origin",
    )
    private const val TAB = '\t'
    private const val SPACE = ' '
    private const val DELETE = '\u007F'

    /** The problem with [header] in [all] (the rows being edited), or null when it can be sent. */
    fun problem(header: ProxyHeader, all: List<ProxyHeader>): ProxyHeaderProblem? {
        val name = header.name.trim()
        return when {
            name.isEmpty() -> ProxyHeaderProblem.EmptyName
            !TOKEN.matches(name) -> ProxyHeaderProblem.InvalidName
            name.lowercase() in RESERVED || name.lowercase().startsWith("sec-") -> ProxyHeaderProblem.Reserved
            header.value.any { (it < SPACE && it != TAB) || it == DELETE } -> ProxyHeaderProblem.InvalidValue
            all.count { it.name.trim().equals(name, ignoreCase = true) } > 1 -> ProxyHeaderProblem.Duplicate
            else -> null
        }
    }

    /** The rows worth keeping: blank ones are dropped, names trimmed. */
    fun tidy(rows: List<ProxyHeader>): List<ProxyHeader> =
        rows.filterNot { it.name.isBlank() && it.value.isBlank() }.map { it.copy(name = it.name.trim()) }

    /** What is sent: the valid rows only, the first of a repeated name winning. */
    fun sendable(rows: List<ProxyHeader>): List<ProxyHeader> {
        val seen = HashSet<String>()
        return rows.filter { problem(it, listOf(it)) == null && seen.add(it.name.trim().lowercase()) }
            .map { it.copy(name = it.name.trim()) }
    }

    /** The origin headers are bound to, or null when the address isn't HTTPS (never sent in the clear). */
    fun origin(url: DashboardUrl): String? {
        if (url.scheme != "https") return null
        val host = if (':' in url.host && !url.host.startsWith("[")) "[${url.host}]" else url.host
        val port = url.port?.takeIf { it != HTTPS_PORT }?.let { ":$it" }.orEmpty()
        return "https://${host.lowercase()}$port"
    }

    private const val HTTPS_PORT = 443
}
