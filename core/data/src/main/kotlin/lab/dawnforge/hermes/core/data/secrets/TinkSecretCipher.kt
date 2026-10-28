package lab.dawnforge.hermes.core.data.secrets

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * AES-256-GCM through Tink. The keyset lives in the app's private shared preferences, wrapped by
 * a master key in the Android Keystore. Tink tests the Keystore on first use and falls back to an
 * unwrapped keyset on devices where it is broken, rather than losing data.
 *
 * App data is excluded from backup and device transfer, so the keyset never outlives the
 * Keystore key that wraps it.
 */
class TinkSecretCipher(private val context: Context, private val ioDispatcher: CoroutineDispatcher) : SecretCipher {
    private val mutex = Mutex()

    @Volatile
    private var aead: Aead? = null

    override suspend fun encrypt(plaintext: ByteArray, context: String): ByteArray =
        withContext(ioDispatcher) { aead().encrypt(plaintext, context.toByteArray()) }

    override suspend fun decrypt(ciphertext: ByteArray, context: String): ByteArray =
        withContext(ioDispatcher) { aead().decrypt(ciphertext, context.toByteArray()) }

    /** Builds the primitive once; reading the keyset touches disk and the Keystore. */
    private suspend fun aead(): Aead = aead ?: mutex.withLock {
        aead ?: withContext(ioDispatcher) {
            AeadConfig.register()
            AndroidKeysetManager.Builder()
                .withSharedPref(context.applicationContext, KEYSET_NAME, KEYSET_PREFS)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }.also { aead = it }
    }

    private companion object {
        const val KEYSET_NAME = "secrets_keyset"
        const val KEYSET_PREFS = "hermes_secrets_keyset"
        const val MASTER_KEY_URI = "android-keystore://hermes_secrets_master_key"
    }
}
