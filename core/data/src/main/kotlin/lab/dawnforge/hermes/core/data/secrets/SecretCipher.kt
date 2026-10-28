package lab.dawnforge.hermes.core.data.secrets

/**
 * Encrypts secrets at rest. [context] is bound to the ciphertext as associated data, so a value
 * copied into another slot (another dashboard, another kind of secret) fails to decrypt.
 */
interface SecretCipher {
    suspend fun encrypt(plaintext: ByteArray, context: String): ByteArray

    /** Throws if the ciphertext was tampered with, moved, or the key is gone. */
    suspend fun decrypt(ciphertext: ByteArray, context: String): ByteArray
}
