package lab.dawnforge.hermes.core.data.phonedata

import com.google.crypto.tink.aead.internal.InsecureNonceChaCha20Poly1305
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.Random
import com.google.crypto.tink.subtle.X25519
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64

/** The plugin's phone-data features, each with its own keys and labels. */
enum class PhoneDataFeature(val id: String) {
    Health("health"),
    Location("location"),
    Phone("phone"),
}

/** A body sealed for the plugin: its nonce and ciphertext, base64url without padding. */
data class Sealed(val nonce: String, val ciphertext: String)

/**
 * The plugin's phone-data encryption, as iOS and the plugin share it (`*_store.py`): the phone's
 * X25519 key agreed with the host's, HKDF-SHA256 per purpose, and ChaCha20-Poly1305 with
 * associated data naming what the body is for. Each feature has its own salt and labels, so a body
 * sealed for one can never be opened as another.
 */
class SealedChannel(val feature: PhoneDataFeature) {
    private val salt = "talaria-${feature.id}-v1".toByteArray()
    private val aadTag = "talaria-${feature.id}/1"

    /** The key both sides name a device's key by: 32 hex characters. */
    fun keyId(devicePublic: ByteArray, hostPublic: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("talaria-${feature.id}-kid\n".toByteArray() + devicePublic + hostPublic)
        return digest.joinToString("") { "%02x".format(it) }.take(KEY_ID_CHARS)
    }

    /** The shared secret of the phone's private key and the host's public one; refuses an all-zero one. */
    fun agree(devicePrivate: ByteArray, hostPublic: ByteArray): ByteArray {
        val shared = X25519.computeSharedSecret(devicePrivate, hostPublic)
        if (shared.all { it == 0.toByte() }) throw GeneralSecurityException("That public key can't be used")
        return shared
    }

    /** The key for one [purpose] (`ingest device-to-host`, `exchange host-to-device`, …). */
    fun key(
        shared: ByteArray,
        deviceId: String,
        devicePublic: ByteArray,
        hostPublic: ByteArray,
        purpose: String,
    ): ByteArray {
        val info = "talaria-${feature.id}-v1 $purpose".toByteArray() + "\n".toByteArray() +
            deviceId.toByteArray(Charsets.US_ASCII) + "\n".toByteArray() + devicePublic + hostPublic
        return Hkdf.computeHkdf("HMACSHA256", shared, salt, info, KEY_BYTES)
    }

    fun ingestAad(keyId: String, deviceId: String, batchId: String): ByteArray =
        listOf(aadTag, "ingest", "kid=$keyId", "dev=$deviceId", "batch=$batchId").joinToString("\n").toByteArray()

    /** A sealed exchange's associated data; [direction] is `request` or `answer`. */
    fun exchangeAad(direction: String, op: String, keyId: String, deviceId: String, callId: String): ByteArray =
        listOf(aadTag, "exchange-$direction", "op=$op", "kid=$keyId", "dev=$deviceId", "call=$callId")
            .joinToString("\n").toByteArray()

    /** Seals [plaintext]; a fresh random nonce unless one is given (tests). */
    fun seal(
        key: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
        nonce: ByteArray = Random.randBytes(NONCE_BYTES),
    ): Sealed = Sealed(b64u(nonce), b64u(InsecureNonceChaCha20Poly1305(key).encrypt(nonce, plaintext, aad)))

    /** Opens a body the host sealed; throws [GeneralSecurityException] for a wrong key or a changed body. */
    fun open(key: ByteArray, sealed: Sealed, aad: ByteArray): ByteArray =
        InsecureNonceChaCha20Poly1305(key).decrypt(unb64u(sealed.nonce), unb64u(sealed.ciphertext), aad)

    companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        private const val KEY_ID_CHARS = 32

        /** A new device key pair: (private, public). */
        fun newKeyPair(): Pair<ByteArray, ByteArray> {
            val private = X25519.generatePrivateKey()
            return private to X25519.publicFromPrivate(private)
        }

        fun publicKey(private: ByteArray): ByteArray = X25519.publicFromPrivate(private)

        fun b64u(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun unb64u(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
    }
}
