package lab.dawnforge.hermes.core.model

/** A Cloudflare Access service token, sent as `CF-Access-Client-Id` / `CF-Access-Client-Secret`. */
data class CloudflareAccessToken(val clientId: String, val clientSecret: String) {
    /** Whether both parts are present after trimming the client id. */
    val isConfigured: Boolean get() = clientId.trim().isNotEmpty() && clientSecret.isNotEmpty()

    override fun toString(): String = "CloudflareAccessToken(clientId=$clientId, clientSecret=<redacted>)"
}
