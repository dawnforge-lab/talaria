package lab.dawnforge.hermes.core.push.e2e

import com.google.crypto.tink.aead.internal.InsecureNonceChaCha20Poly1305
import com.google.crypto.tink.subtle.ChaCha20Poly1305
import com.google.crypto.tink.subtle.Hkdf
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * End-to-end encryption of Talaria notifications, as the `talaria_push` plugin's `e2e.py` does it.
 *
 * The phone makes a 32-byte secret per pairing and hands it to the Hermes profile over the
 * signed-in dashboard, so the relay and Google never see it. Both sides derive their keys from it
 * with HKDF-SHA256. The associated data binds each envelope to its pairing and to the fields the
 * relay can see, so the relay can neither read a push nor move, retype or re-thread one.
 */
object NotificationE2E {
    const val SECRET_BYTES = 32

    private val SALT = "talaria-e2e-v1".toByteArray()
    private val INFO_PUSH = "talaria-e2e-v1 push gateway-to-device".toByteArray()
    private val INFO_ANSWER = "talaria-e2e-v1 answer device-to-gateway".toByteArray()
    private val INFO_META = "talaria-e2e-v1 metadata".toByteArray()
    private const val AAD_TAG = "talaria-e2e/1"
    private const val NONCE_BYTES = 12
    private const val ANSWER_PREFIX = "e2e1."

    /** Bounded: a valid envelope never inflates past this. */
    private const val MAX_INFLATED_BYTES = 64 * 1024
    private val KID = Regex("^[0-9a-f]{32}$")
    private val BASE64URL = Regex("^[A-Za-z0-9_-]*$")
    private val json = Json { ignoreUnknownKeys = true }

    class Keys internal constructor(val kid: String, val push: ByteArray, val answer: ByteArray, val meta: ByteArray)

    /** The visible half of a sealed push, as the relay forwards it in `talaria_e2e`. */
    @Serializable
    data class Envelope(
        val v: Int,
        val kid: String,
        val msg: String,
        val iat: Long,
        val tok: String,
        val z: Int,
        val req: String = "",
        @SerialName("n") val nonce: String,
        @SerialName("ct") val ciphertext: String,
    )

    fun validKid(kid: String): Boolean = KID.matches(kid)

    fun deriveKeys(kid: String, secret: ByteArray): Keys {
        if (!validKid(kid)) throw E2EException("malformed key id")
        if (secret.size != SECRET_BYTES) throw E2EException("the pairing secret must be 32 bytes")

        fun derive(info: ByteArray) = Hkdf.computeHkdf("HMACSHA256", secret, SALT, info, 32)
        return Keys(kid, derive(INFO_PUSH), derive(INFO_ANSWER), derive(INFO_META))
    }

    fun parseEnvelope(raw: String): Envelope = try {
        json.decodeFromString(Envelope.serializer(), raw)
    } catch (e: IllegalArgumentException) {
        throw E2EException("the envelope is malformed", e)
    }

    /** The push's content (`title`, `body`, `session_id`, …); throws [E2EException] unless it verifies. */
    fun open(envelope: Envelope, type: String, keys: Keys, installationId: String, gatewayId: String): JsonObject {
        if (envelope.v != 1) throw E2EException("unknown envelope version ${envelope.v}")
        if (envelope.kid != keys.kid) throw E2EException("the push was sealed with another key")
        if (envelope.z != 0 && envelope.z != 1) throw E2EException("unknown compression flag")
        val nonce = unb64u(envelope.nonce)
        if (nonce.size != NONCE_BYTES) throw E2EException("the nonce is malformed")
        val aad = pushAad(
            kid = keys.kid,
            installationId = installationId,
            gatewayId = gatewayId,
            msg = envelope.msg,
            type = type,
            iat = envelope.iat,
            tok = envelope.tok,
            z = envelope.z,
            requestId = envelope.req,
        )
        val packed = try {
            // Tink's ChaCha20-Poly1305 takes the nonce in front of the ciphertext.
            ChaCha20Poly1305(keys.push).decrypt(nonce + unb64u(envelope.ciphertext), aad)
        } catch (e: GeneralSecurityException) {
            throw E2EException("the envelope did not verify", e)
        }
        return unpack(packed, envelope.z)
    }

    /**
     * A clarify answer sealed for the profile's plugin, as the relay carries it:
     * `e2e1.<kid>.<nonce>.<ciphertext>`. [questionId] is the question's qid in a batch, empty for a
     * single question; the plugin refuses an answer sealed for another request or question.
     */
    fun sealAnswer(
        answer: String,
        keys: Keys,
        installationId: String,
        gatewayId: String,
        requestId: String,
        questionId: String = "",
        nonce: ByteArray = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes),
    ): String {
        require(nonce.size == NONCE_BYTES) { "the nonce must be 12 bytes" }
        val aad = listOf(
            AAD_TAG,
            "answer",
            "kid=${keys.kid}",
            "inst=$installationId",
            "gw=$gatewayId",
            "req=$requestId",
            "qid=$questionId",
        ).joinToString("\n").toByteArray()
        // The nonce goes in the answer beside the ciphertext, as the plugin expects, not in front of it.
        val sealed = InsecureNonceChaCha20Poly1305(keys.answer).encrypt(nonce, answer.toByteArray(), aad)
        return "$ANSWER_PREFIX${keys.kid}.${b64u(nonce)}.${b64u(sealed)}"
    }

    /** The plugin's keyed thread token for [seed] (a session id): what `tok` holds. */
    fun threadToken(keys: Keys, seed: String): String =
        hmac(keys.meta, "thread\n".toByteArray() + seed.toByteArray()).toHex().take(16)

    internal fun pushAad(
        kid: String,
        installationId: String,
        gatewayId: String,
        msg: String,
        type: String,
        iat: Long,
        tok: String,
        z: Int,
        requestId: String,
    ): ByteArray = listOf(
        AAD_TAG, "push", "kid=$kid", "inst=$installationId", "gw=$gatewayId",
        "msg=$msg", "type=$type", "iat=$iat", "tok=$tok", "z=$z", "req=$requestId",
    ).joinToString("\n").toByteArray()

    fun b64u(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    fun unb64u(value: String): ByteArray {
        if (!BASE64URL.matches(value) || value.length % 4 == 1) throw E2EException("malformed base64url")
        return Base64.getUrlDecoder().decode(value)
    }

    private fun unpack(data: ByteArray, z: Int): JsonObject {
        val raw = if (z == 1) inflate(data) else data
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw E2EException("inner payload is not UTF-8", e)
        }
        return try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            throw E2EException("inner payload is not JSON", e)
        } ?: throw E2EException("inner payload is not an object")
    }

    /** Raw deflate (no zlib header), as the plugin writes it. */
    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw E2EException("inner payload is truncated")
                }
                out.write(buffer, 0, count)
                if (out.size() > MAX_INFLATED_BYTES) throw E2EException("inner payload too large")
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw E2EException("inner payload is not valid deflate", e)
        } finally {
            inflater.end()
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

class E2EException(message: String, cause: Throwable? = null) : Exception(message, cause)
