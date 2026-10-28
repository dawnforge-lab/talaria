package lab.dawnforge.hermes.core.net

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable

/**
 * A PKCE pair (RFC 7636, S256): the [verifier] stays in the app, the [challenge] goes to Hermes
 * with the sign-in, and only the holder of the verifier can redeem the code it returns.
 */
class Pkce private constructor(val verifier: String, val challenge: String) {
    override fun toString(): String = "Pkce(<redacted>)"

    companion object {
        fun create(random: SecureRandom = SecureRandom()): Pkce {
            val verifier = randomToken(random, VERIFIER_BYTES)
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Pkce(verifier, base64Url(digest))
        }

        /** 64 bytes make an 86-character verifier, within RFC 7636's 43 to 128. */
        private const val VERIFIER_BYTES = 64
    }
}

/** A random, URL-safe string, e.g. the `state` that ties a sign-in callback to the attempt that started it. */
fun randomToken(random: SecureRandom = SecureRandom(), bytes: Int = 32): String =
    base64Url(ByteArray(bytes).also(random::nextBytes))

private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/**
 * The session of a native sign-in (Hermes' `/auth/native/token` and `/auth/native/refresh`): a
 * bearer token, and the refresh token that renews it. Both are secrets.
 */
@Serializable
data class NativeTokens(
    val accessToken: String,
    /** Null when the identity provider gave none: the session then ends when [accessToken] does. */
    val refreshToken: String?,
    /** Epoch milliseconds. */
    val expiresAtMs: Long,
    /** The Hermes provider that signed the user in; a hint for refreshing. */
    val provider: String,
    val userId: String? = null,
) {
    override fun toString(): String =
        "NativeTokens(provider=$provider, expiresAtMs=$expiresAtMs, accessToken=<redacted>, refreshToken=<redacted>)"
}
