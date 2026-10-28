package lab.dawnforge.hermes.core.push

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import java.io.File
import java.security.GeneralSecurityException
import kotlin.io.path.createTempDirectory
import lab.dawnforge.hermes.core.data.secrets.SecretCipher
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** A stand-in cipher that, like AEAD, refuses to decrypt under the wrong context. Not secure. */
class FakeCipher : SecretCipher {
    override suspend fun encrypt(plaintext: ByteArray, context: String): ByteArray =
        (context + "|").encodeToByteArray() + plaintext.map { (it.toInt() xor 0x5A).toByte() }

    override suspend fun decrypt(ciphertext: ByteArray, context: String): ByteArray {
        val prefix = (context + "|").encodeToByteArray()
        if (ciphertext.size < prefix.size || !ciphertext.copyOfRange(0, prefix.size).contentEquals(prefix)) {
            throw GeneralSecurityException("wrong context")
        }
        return ciphertext.copyOfRange(prefix.size, ciphertext.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}

/** A [PushStore] on a temporary file. Okio storage, because DataStore's default can't replace a file on Windows. */
fun testPushStore(): PushStore {
    val file = File(createTempDirectory("hermes-push-test").toFile(), "push.preferences_pb")
    val store = PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
            file.toOkioPath()
        },
    )
    return PushStore(store, FakeCipher())
}

class FakeTokens(var current: String = "fcmToken_1:APA91b" + "a".repeat(140)) : PushTokens {
    override var available = true
    var deleted = 0

    /** The thread each call ran on: Firebase refuses to work on Android's main thread. */
    val threads = mutableListOf<String>()

    override suspend fun token(): String {
        threads += Thread.currentThread().name
        return current
    }

    override suspend fun delete() {
        threads += Thread.currentThread().name
        deleted += 1
    }
}
