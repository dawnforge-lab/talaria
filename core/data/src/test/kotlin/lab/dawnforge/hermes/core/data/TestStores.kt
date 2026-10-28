package lab.dawnforge.hermes.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import java.io.File
import java.security.GeneralSecurityException
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.secrets.SecretCipher
import lab.dawnforge.hermes.core.data.session.SessionCookies
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

class TestStores {
    val directory: File = createTempDirectory("hermes-data-test").toFile()
    private val settingsFile = File(directory, "dashboards.preferences_pb")
    private val secretsFile = File(directory, "dashboard_secrets.preferences_pb")

    val settings = store(settingsFile)
    val secrets = store(secretsFile)
    val repository = DashboardRepository(settings, secrets, FakeCipher())
    val cookies = SessionCookies(repository, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    /** Another store in the same folder, for the settings a test needs besides the dashboards'. */
    fun store(name: String): DataStore<Preferences> = store(File(directory, "$name.preferences_pb"))

    fun secretsFileText(): String = if (secretsFile.exists()) secretsFile.readBytes().decodeToString() else ""

    // Okio storage, because DataStore's default file storage cannot replace a file on Windows
    // hosts. Devices and CI (Linux) are unaffected; the app itself keeps the default storage.
    private fun store(file: File): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { file.toOkioPath() },
    )
}
