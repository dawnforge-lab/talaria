package lab.dawnforge.hermes.core.data.dashboard

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.GeneralSecurityException
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import lab.dawnforge.hermes.core.data.secrets.SecretCipher
import lab.dawnforge.hermes.core.net.ProxyHeader
import lab.dawnforge.hermes.core.net.ProxyHeaderRules
import lab.dawnforge.hermes.core.net.ProxyHeaders

/**
 * The extra headers a reverse proxy in front of Hermes asks for, kept encrypted by server origin
 * (`https://host[:port]`), as iOS keeps them in the Keychain. Signing out keeps them; removing the
 * last dashboard on an origin forgets them. Every request to that origin over HTTPS carries the
 * valid ones.
 */
class ProxyHeadersStore(
    private val secrets: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProxyHeaders {
    private val lock = Mutex()

    @Volatile
    private var cache: Map<String, List<ProxyHeader>>? = null

    /** What requests to [origin] carry. Read once from storage, then from memory. */
    override fun forOrigin(origin: String): List<ProxyHeader> {
        val all = cache ?: runBlocking { load() }
        return ProxyHeaderRules.sendable(all[origin].orEmpty())
    }

    /** The rows saved for [origin], invalid ones included, for editing. */
    suspend fun rows(origin: String): List<ProxyHeader> = load()[origin].orEmpty()

    suspend fun save(origin: String, rows: List<ProxyHeader>) = lock.withLock {
        val kept = ProxyHeaderRules.tidy(rows)
        val next = (read() - origin) + if (kept.isEmpty()) emptyMap() else mapOf(origin to kept)
        write(next)
    }

    suspend fun forget(origin: String) = lock.withLock { write(read() - origin) }

    private suspend fun load(): Map<String, List<ProxyHeader>> = cache ?: lock.withLock { read() }

    private suspend fun read(): Map<String, List<ProxyHeader>> {
        cache?.let { return it }
        val encoded = secrets.data.first()[KEY]
        val decoded = encoded?.let {
            try {
                json.decodeFromString(
                    SERIALIZER,
                    cipher.decrypt(Base64.getDecoder().decode(it), CONTEXT).decodeToString(),
                )
            } catch (_: GeneralSecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }.orEmpty()
        cache = decoded
        return decoded
    }

    private suspend fun write(all: Map<String, List<ProxyHeader>>) {
        val sealed = Base64.getEncoder().encodeToString(
            cipher.encrypt(json.encodeToString(SERIALIZER, all).encodeToByteArray(), CONTEXT),
        )
        secrets.edit { it[KEY] = sealed }
        cache = all
    }

    private companion object {
        val KEY = stringPreferencesKey("proxy_headers.v1")
        const val CONTEXT = "hermes.proxy-headers.v1"
        val SERIALIZER = MapSerializer(String.serializer(), ListSerializer(ProxyHeader.serializer()))
    }
}
