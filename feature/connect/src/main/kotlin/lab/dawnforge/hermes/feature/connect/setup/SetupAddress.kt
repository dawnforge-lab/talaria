package lab.dawnforge.hermes.feature.connect.setup

import lab.dawnforge.hermes.core.net.AddressCheck
import lab.dawnforge.hermes.core.net.AddressProblem
import lab.dawnforge.hermes.core.net.ConnectionUrlPolicy

/** How the phone reaches Hermes, as the setup wizard asks it. */
enum class AccessMethod { Lan, Tailscale, ReverseProxy }

enum class SetupScheme(val value: String) { Http("http"), Https("https") }

/** What the wizard has been told so far. In memory only: it can hold the password. */
data class SetupDraft(
    /** The address the wizard was opened with, if any, offered as "Use or edit current dashboard address". */
    val existingAddress: String = "",
    val useExisting: Boolean = false,
    val method: AccessMethod? = null,
    val lanHost: String = "",
    val lanPort: String = "",
    val tailscaleHost: String = "",
    val tailscalePort: String = "",
    val tailscaleScheme: SetupScheme? = null,
    val proxyAddress: String = "",
    val username: String = "",
    val password: String = "",
) {
    val hasCredentials: Boolean get() = username.isNotBlank() && password.isNotEmpty()

    override fun toString(): String = "SetupDraft(method=$method, useExisting=$useExisting, password=<redacted>)"
}

/** Why the typed details don't make an address yet. */
sealed interface SetupAddressProblem {
    data object MethodRequired : SetupAddressProblem

    data object InvalidHost : SetupAddressProblem

    data object InvalidPort : SetupAddressProblem

    data object HttpsRequired : SetupAddressProblem

    data object SchemeRequired : SetupAddressProblem

    data class Policy(val problem: AddressProblem) : SetupAddressProblem
}

sealed interface SetupAddress {
    data class Built(val address: String) : SetupAddress

    data class Problem(val problem: SetupAddressProblem) : SetupAddress
}

/**
 * Builds the dashboard address from the wizard's answers, as iOS's setup does: a LAN address is
 * `http://ip:port`; a Tailscale `*.ts.net` name is https, any other Tailscale host needs its
 * scheme chosen; a reverse proxy is a full https address. No network is touched; the app's address
 * rules ([ConnectionUrlPolicy]) have the last word.
 */
object SetupAddressBuilder {
    fun build(draft: SetupDraft): SetupAddress = try {
        SetupAddress.Built(address(draft))
    } catch (e: Invalid) {
        SetupAddress.Problem(e.problem)
    }

    fun isServeHostname(host: String): Boolean = host.trim().lowercase().endsWith(".ts.net")

    private class Invalid(val problem: SetupAddressProblem) : Exception()

    private fun address(draft: SetupDraft): String {
        if (draft.useExisting) return fullAddress(draft.existingAddress, requireHttps = false)
        return when (draft.method) {
            AccessMethod.Lan -> endpoint(draft.lanHost, draft.lanPort, SetupScheme.Http, portRequired = true)

            AccessMethod.Tailscale -> {
                val host = draft.tailscaleHost.trim()
                val scheme = if (isServeHostname(host)) {
                    SetupScheme.Https
                } else {
                    // The host is checked before asking for a scheme; an IP never quietly becomes https.
                    validateHost(host)
                    draft.tailscaleScheme ?: throw Invalid(SetupAddressProblem.SchemeRequired)
                }
                endpoint(host, draft.tailscalePort, scheme, portRequired = false)
            }

            AccessMethod.ReverseProxy -> fullAddress(draft.proxyAddress, requireHttps = true)

            null -> throw Invalid(SetupAddressProblem.MethodRequired)
        }
    }

    private fun endpoint(rawHost: String, rawPort: String, scheme: SetupScheme, portRequired: Boolean): String {
        val host = rawHost.trim()
        validateHost(host)
        val port = port(rawPort, portRequired)
        return permitted("${scheme.value}://$host" + (port?.let { ":$it" } ?: ""))
    }

    private fun fullAddress(raw: String, requireHttps: Boolean): String {
        val address = raw.trim()
        val scheme = address.substringBefore("://", missingDelimiterValue = "")
        if (scheme.isEmpty() || address.substringAfter("://").substringBefore('/').isEmpty()) {
            throw Invalid(
                if (requireHttps) {
                    SetupAddressProblem.HttpsRequired
                } else {
                    SetupAddressProblem.Policy(
                        AddressProblem.Malformed,
                    )
                },
            )
        }
        if (requireHttps && !scheme.equals("https", ignoreCase = true)) throw Invalid(SetupAddressProblem.HttpsRequired)
        return permitted(address)
    }

    private fun permitted(address: String): String = when (val check = ConnectionUrlPolicy.check(address)) {
        is AddressCheck.Valid -> address
        is AddressCheck.Invalid -> throw Invalid(SetupAddressProblem.Policy(check.problem))
    }

    private fun port(raw: String, required: Boolean): Int? {
        val value = raw.trim()
        if (value.isEmpty() && !required) return null
        val port = value.takeIf { it.isNotEmpty() && it.all { c -> c.isPlainDigit() } }?.toIntOrNull()
        if (port == null || port !in 1..MAX_PORT) throw Invalid(SetupAddressProblem.InvalidPort)
        return port
    }

    /** Syntax only: letters, digits and hyphens in dot-separated labels, or a dotted IPv4 address. */
    private fun validateHost(host: String) {
        val labels = host.split('.')
        val valid = host.isNotEmpty() && host.length <= MAX_HOST && labels.all { label ->
            label.isNotEmpty() && label.length <= MAX_LABEL && label.first() != '-' && label.last() != '-' &&
                label.all { it.isHostChar() || it == '-' }
        }
        if (!valid) throw Invalid(SetupAddressProblem.InvalidHost)
        if (host.all { it.isPlainDigit() || it == '.' }) {
            if (labels.size != IPV4_PARTS || labels.any { (it.toIntOrNull() ?: -1) !in 0..MAX_OCTET }) {
                throw Invalid(SetupAddressProblem.InvalidHost)
            }
        }
    }

    private fun Char.isPlainDigit() = this in '0'..'9'

    private fun Char.isHostChar() = isPlainDigit() || this in 'a'..'z' || this in 'A'..'Z'

    private const val MAX_PORT = 65535
    private const val MAX_HOST = 253
    private const val MAX_LABEL = 63
    private const val IPV4_PARTS = 4
    private const val MAX_OCTET = 255
}
