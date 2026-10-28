package lab.dawnforge.hermes.core.net

/**
 * A setup link, which hands the app a dashboard to sign in to:
 * `talaria://connect?url=<address>&username=<name>&password=<password>`, each value
 * percent-encoded. Only [address] is required.
 */
data class ConnectLink(val address: String, val username: String?, val password: String?) {
    override fun toString(): String =
        "ConnectLink(address=$address, username=$username, password=${password?.let { "<redacted>" }})"

    companion object {
        /** Returns the link, or null when [raw] is not a readable connect link. */
        fun parse(raw: String): ConnectLink? {
            val text = raw.trim()
            val schemeEnd = text.indexOf("://")
            if (schemeEnd <= 0 || !text.substring(0, schemeEnd).equals("talaria", ignoreCase = true)) return null

            val afterScheme = text.substring(schemeEnd + 3)
            if (afterScheme.contains('#')) return null
            val hostAndPath = afterScheme.substringBefore('?')
            val rawQuery = afterScheme.substringAfter('?', missingDelimiterValue = "")

            val host = hostAndPath.substringBefore('/')
            val path = hostAndPath.removePrefix(host)
            if (!host.equals("connect", ignoreCase = true) || (path != "" && path != "/")) return null

            val params = queryParams(rawQuery) ?: return null

            val address = params["url"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return ConnectLink(
                address = address,
                username = params["username"]?.trim()?.takeIf { it.isNotEmpty() },
                password = params["password"]?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/** What a scanned QR code or pasted text turned out to be. */
sealed interface ScannedCode {
    data class Link(val link: ConnectLink) : ScannedCode

    data class Address(val input: String) : ScannedCode

    data object Unreadable : ScannedCode

    companion object {
        fun classify(text: String): ScannedCode {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return Unreadable
            if (trimmed.startsWith("talaria:", ignoreCase = true)) {
                return ConnectLink.parse(trimmed)?.let(::Link) ?: Unreadable
            }
            return when (ConnectionUrlPolicy.check(trimmed)) {
                is AddressCheck.Valid -> Address(trimmed)
                is AddressCheck.Invalid -> Unreadable
            }
        }
    }
}
