package lab.dawnforge.hermes.core.net

import lab.dawnforge.hermes.core.model.DashboardUrl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Why an address can't be used as a dashboard address. */
enum class AddressProblem {
    Empty,
    UnsupportedScheme,
    Malformed,
    HasCredentials,
    HasQueryOrFragment,

    /** Plain http to a host that is not loopback, a private network or a Tailscale address. */
    InsecureTransport,
}

sealed interface AddressCheck {
    /** Addresses to try in order; more than one only when the user typed no scheme. */
    data class Valid(val candidates: List<DashboardUrl>) : AddressCheck

    data class Invalid(val problem: AddressProblem) : AddressCheck
}

/**
 * Which dashboard addresses the app accepts. Matches the iOS app's `ConnectionURLPolicy`:
 * https anywhere; plain http only on loopback, private (RFC 1918) networks and Tailscale
 * (`*.ts.net` and `100.64.0.0/10`), whose traffic WireGuard already encrypts.
 */
object ConnectionUrlPolicy {
    private val schemePrefix = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /** Validates [input] as typed by the user, expanding a scheme-less address into candidates. */
    fun check(input: String): AddressCheck {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return AddressCheck.Invalid(AddressProblem.Empty)
        if (schemePrefix.containsMatchIn(trimmed)) {
            return when (val result = normalize(trimmed)) {
                is Normalized.Ok -> AddressCheck.Valid(listOf(result.url))
                is Normalized.Bad -> AddressCheck.Invalid(result.problem)
            }
        }

        val https = normalize("https://$trimmed")
        if (https is Normalized.Bad) return AddressCheck.Invalid(https.problem)
        val httpsUrl = (https as Normalized.Ok).url
        val httpUrl = (normalize("http://$trimmed") as? Normalized.Ok)?.url

        // A bare IP or localhost is almost always a plain-http dashboard, so try that first.
        val candidates = when {
            httpUrl == null -> listOf(httpsUrl)
            isIpLiteral(httpsUrl.host) || httpsUrl.host == "localhost" -> listOf(httpUrl, httpsUrl)
            else -> listOf(httpsUrl, httpUrl)
        }
        return AddressCheck.Valid(candidates)
    }

    /** Whether plain http is acceptable for [host]. */
    fun allowsCleartext(host: String): Boolean {
        val lower = host.lowercase()
        if (lower == "localhost" || lower == "127.0.0.1" || lower == "::1") return true
        if (lower.endsWith(".ts.net")) return true
        val octets = strictIpv4(lower) ?: return false
        val (a, b) = octets
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127)
    }

    /** Hosts that only work on the machine running Hermes, never from a phone. */
    fun isLoopbackOnly(host: String): Boolean {
        val lower = host.lowercase()
        return lower == "localhost" ||
            lower.endsWith(".localhost") ||
            lower == "::1" ||
            lower == "0.0.0.0" ||
            strictIpv4(lower)?.first() == 127
    }

    private sealed interface Normalized {
        data class Ok(val url: DashboardUrl) : Normalized

        data class Bad(val problem: AddressProblem) : Normalized
    }

    private fun normalize(input: String): Normalized {
        val scheme = input.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return Normalized.Bad(AddressProblem.UnsupportedScheme)
        val url: HttpUrl = input.toHttpUrlOrNull() ?: return Normalized.Bad(AddressProblem.Malformed)
        if (url.host.isEmpty()) return Normalized.Bad(AddressProblem.Malformed)
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return Normalized.Bad(AddressProblem.HasCredentials)
        }
        if (url.query != null || url.fragment != null) return Normalized.Bad(AddressProblem.HasQueryOrFragment)
        if (url.scheme == "http" && !allowsCleartext(url.host)) {
            return Normalized.Bad(AddressProblem.InsecureTransport)
        }
        val port = url.port.takeUnless { it == HttpUrl.defaultPort(url.scheme) }
        val basePath = url.encodedPath.trimEnd('/')
        return Normalized.Ok(DashboardUrl(url.scheme, url.host, port, basePath))
    }

    private fun isIpLiteral(host: String): Boolean = host.contains(':') || strictIpv4(host) != null

    /** A dotted quad with no leading zeros (so "010" is never read as octal), or null. */
    private fun strictIpv4(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        return parts.map { part ->
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            if (part.length > 1 && part.startsWith('0')) return null
            part.toInt().takeIf { it in 0..255 } ?: return null
        }
    }
}
