package lab.dawnforge.hermes.core.data.phonedata

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.GeneralSecurityException
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.secrets.SecretCipher
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** A key ready for a pass: its id, whether it was just set up, and the host's waiting changes. */
data class Prepared(val kid: String, val fresh: Boolean, val pending: Int?)

/** This phone's key for one feature on one profile, as the host knows it. */
@Serializable
internal data class DeviceKey(
    val private: String,
    val public: String,
    val kid: String,
    val hostPublic: String,
    val generation: String? = null,
) {
    override fun toString(): String = "DeviceKey(kid=$kid)"
}

/**
 * Uploads this phone's data to the push plugin, sealed, as iOS does for health, location and the
 * phone (`/<feature>/key`, `/<feature>/ingest`, `/phone/exchange`): one X25519 key per feature and
 * profile, set up when the host doesn't know it (or its store was wiped), kept encrypted on the
 * phone. The host refusing a key it lost (409) sets up a new one and tries once more.
 */
class PhoneDataUploader(
    private val requests: DashboardRequests,
    private val secrets: DataStore<Preferences>,
    private val cipher: SecretCipher,
    /** What the host shows this phone as ("Pixel 8"). */
    private val deviceName: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val keying = Mutex()

    /** This phone's id on [id]'s host: random, kept, the same for every feature. */
    suspend fun deviceId(id: DashboardId): String {
        val key = stringPreferencesKey("phonedata.device.${id.value}")
        secrets.data.first()[key]?.let { return it }
        val made = "android-" + UUID.randomUUID().toString().replace("-", "").take(DEVICE_ID_CHARS)
        secrets.edit { prefs -> if (prefs[key] == null) prefs[key] = made }
        return secrets.data.first()[key] ?: made
    }

    /** `GET /<feature>/status`: what the host keeps, and whether it knows this phone's key. */
    suspend fun status(id: DashboardId, profile: String, feature: PhoneDataFeature): NetResult<JsonObject> {
        val device = deviceId(id)
        return requests.run(id) { url, jar, cf ->
            get(url, jar, cf, API, listOf(feature.id, "status"), mapOf("profile" to profile, "device_id" to device))
        }.objectOrMalformed()
    }

    /**
     * Seals [body] and uploads it as one batch (`/<feature>/ingest`); the host's answer, or why not.
     * [body] must carry the same `batch_id` as [batchId].
     */
    suspend fun ingest(
        id: DashboardId,
        profile: String,
        feature: PhoneDataFeature,
        batchId: String,
        body: JsonObject,
    ): NetResult<JsonObject> = ingest(id, profile, feature, batchId) { body }

    /**
     * [ingest], with the body made once the key is settled: [body] hears whether the key was set
     * up during this call (the host then has nothing from this phone, so everything goes whole).
     */
    suspend fun ingest(
        id: DashboardId,
        profile: String,
        feature: PhoneDataFeature,
        batchId: String,
        body: (newKey: Boolean) -> JsonObject,
    ): NetResult<JsonObject> = withKey(id, profile, feature) { key, device, newKey ->
        val channel = SealedChannel(feature)
        val sealed = channel.seal(
            key.purpose(channel, device, "ingest device-to-host"),
            body(newKey).toString().toByteArray(),
            channel.ingestAad(key.kid, device, batchId),
        )
        val envelope = buildJsonObject {
            put("v", 1)
            put("device_id", device)
            put("kid", key.kid)
            put("batch_id", batchId)
            put("nonce", sealed.nonce)
            put("ct", sealed.ciphertext)
        }
        requests.run(id) { url, jar, cf ->
            post(url, jar, cf, API, envelope, mapOf("profile" to profile), listOf(feature.id, "ingest"))
        }.objectOrMalformed()
    }

    /**
     * A sealed exchange (`/phone/exchange`): [body] goes sealed as the request, and the host's
     * sealed answer comes back opened.
     */
    suspend fun exchange(
        id: DashboardId,
        profile: String,
        op: String,
        callId: String,
        body: JsonObject,
    ): NetResult<JsonObject> = withKey(id, profile, PhoneDataFeature.Phone) { key, device, _ ->
        val channel = SealedChannel(PhoneDataFeature.Phone)
        val sealed = channel.seal(
            key.purpose(channel, device, "exchange device-to-host"),
            body.toString().toByteArray(),
            channel.exchangeAad("request", op, key.kid, device, callId),
        )
        val envelope = buildJsonObject {
            put("v", 1)
            put("device_id", device)
            put("kid", key.kid)
            put("call_id", callId)
            put("op", op)
            put("nonce", sealed.nonce)
            put("ct", sealed.ciphertext)
        }
        when (
            val reply = requests.run(id) { url, jar, cf ->
                post(url, jar, cf, API, envelope, mapOf("profile" to profile), listOf("phone", "exchange"))
            }.objectOrMalformed()
        ) {
            is NetResult.Failure -> reply
            is NetResult.Success -> openAnswer(channel, key, device, op, callId, reply.value)
        }
    }

    private fun openAnswer(
        channel: SealedChannel,
        key: DeviceKey,
        device: String,
        op: String,
        callId: String,
        reply: JsonObject,
    ): NetResult<JsonObject> {
        val nonce = reply.text("nonce") ?: return NetResult.Failure(MALFORMED)
        val ciphertext = reply.text("ct") ?: return NetResult.Failure(MALFORMED)
        return try {
            val opened = channel.open(
                key.purpose(channel, device, "exchange host-to-device"),
                Sealed(nonce, ciphertext),
                channel.exchangeAad("answer", op, key.kid, device, callId),
            )
            (json.parseToJsonElement(opened.decodeToString()) as? JsonObject)?.let { NetResult.Success(it) }
                ?: NetResult.Failure(MALFORMED)
        } catch (_: GeneralSecurityException) {
            NetResult.Failure(MALFORMED)
        }
    }

    /** Deletes this phone's data and key for [feature] on the host (`/<feature>/wipe`), and the key here. */
    suspend fun wipe(id: DashboardId, profile: String, feature: PhoneDataFeature): NetResult<JsonObject> {
        val device = deviceId(id)
        val result = requests.run(id) { url, jar, cf ->
            post(
                url,
                jar,
                cf,
                API,
                buildJsonObject { put("device_id", device) },
                mapOf("profile" to profile),
                listOf(feature.id, "wipe"),
            )
        }.objectOrMalformed()
        if (result is NetResult.Success) forgetKey(id, profile, feature)
        return result
    }

    /**
     * Deletes everything [feature] keeps for [profile] on the host, from every device
     * (`/<feature>/wipe` with `all`), and this phone's key.
     */
    suspend fun wipeAll(id: DashboardId, profile: String, feature: PhoneDataFeature): NetResult<JsonObject> {
        val result = requests.run(id) { url, jar, cf ->
            post(
                url,
                jar,
                cf,
                API,
                buildJsonObject {
                    put("all", true)
                },
                mapOf("profile" to profile),
                listOf(feature.id, "wipe"),
            )
        }.objectOrMalformed()
        if (result is NetResult.Success) forgetKey(id, profile, feature)
        return result
    }

    /** Forgets this phone's key for [feature] on [profile] (the host deleted or no longer has it). */
    suspend fun forget(id: DashboardId, profile: String, feature: PhoneDataFeature) = forgetKey(id, profile, feature)

    /** `GET /<feature>/<path>` for [profile]: what the host shows about it (a read log, rules). */
    suspend fun read(
        id: DashboardId,
        profile: String,
        feature: PhoneDataFeature,
        path: List<String>,
        query: Map<String, String> = emptyMap(),
    ): NetResult<JsonObject> = requests.run(id) { url, jar, cf ->
        get(url, jar, cf, API, listOf(feature.id) + path, query + ("profile" to profile))
    }.objectOrMalformed()

    /** `DELETE /<feature>/<path>` for [profile]. */
    suspend fun remove(
        id: DashboardId,
        profile: String,
        feature: PhoneDataFeature,
        path: List<String>,
    ): NetResult<JsonObject> = requests.run(id) { url, jar, cf ->
        delete(url, jar, cf, API, listOf(feature.id) + path, mapOf("profile" to profile))
    }.objectOrMalformed()

    /**
     * Checks with the host once (its status), as iOS does at the start of a pass: the saved key
     * while the host still knows it and its store wasn't wiped, else a new one. [Prepared.fresh]
     * says a new key was set up, so everything goes whole again; [Prepared.pending] is how many
     * changes the host has waiting for the phone, when it says.
     */
    suspend fun prepare(id: DashboardId, profile: String, feature: PhoneDataFeature): NetResult<Prepared> =
        keying.withLock {
            val device = deviceId(id)
            val saved = loadKey(id, profile, feature)
            val status = when (val read = status(id, profile, feature)) {
                is NetResult.Success -> read.value
                is NetResult.Failure -> return@withLock read
            }
            val hostDevice = status["device"] as? JsonObject
            val pending = (hostDevice?.get("pending") as? JsonPrimitive)?.intOrNull
            val generation = status["generation"]?.asText()
            if (saved != null && saved.kid == hostDevice?.text("kid") &&
                (saved.generation == null || saved.generation == generation)
            ) {
                return@withLock NetResult.Success(Prepared(saved.kid, fresh = false, pending = pending))
            }
            when (val made = register(id, profile, feature, device)) {
                is NetResult.Success -> NetResult.Success(Prepared(made.value.kid, fresh = true, pending = null))
                is NetResult.Failure -> made
            }
        }

    /**
     * Runs [call] with the saved key (set up first when there's none), telling it whether the key
     * was just set up. A call the host refuses with 409 (it lost the key) runs once more with a new
     * key.
     */
    private suspend fun withKey(
        id: DashboardId,
        profile: String,
        feature: PhoneDataFeature,
        call: suspend (key: DeviceKey, device: String, newKey: Boolean) -> NetResult<JsonObject>,
    ): NetResult<JsonObject> {
        val device = deviceId(id)
        var newKey = false
        val key = loadKey(id, profile, feature) ?: when (val made = prepare(id, profile, feature)) {
            is NetResult.Success -> {
                newKey = made.value.fresh
                loadKey(id, profile, feature) ?: return NetResult.Failure(MALFORMED)
            }

            is NetResult.Failure -> return made
        }
        val first = call(key, device, newKey)
        if (!(first is NetResult.Failure && first.failure.isUnknownKey())) return first
        forgetKey(id, profile, feature)
        if (prepare(id, profile, feature) is NetResult.Failure) return first
        val fresh = loadKey(id, profile, feature) ?: return first
        return call(fresh, device, true)
    }

    private suspend fun register(
        id: DashboardId,
        profile: String,
        feature: PhoneDataFeature,
        device: String,
    ): NetResult<DeviceKey> {
        val (private, public) = SealedChannel.newKeyPair()
        val body = buildJsonObject {
            put("device_id", device)
            put("device_name", deviceName)
            put("public_key", SealedChannel.b64u(public))
        }
        val reply = requests.run(id) { url, jar, cf ->
            post(url, jar, cf, API, body, mapOf("profile" to profile), listOf(feature.id, "key"))
        }.objectOrMalformed()
        val answer = when (reply) {
            is NetResult.Success -> reply.value
            is NetResult.Failure -> return reply
        }
        val kid = answer.text("kid") ?: return NetResult.Failure(MALFORMED)
        val hostPublic = answer.text("host_public_key") ?: return NetResult.Failure(MALFORMED)
        // The host's key id must be the one both sides work out, or the keys don't match.
        val expected = SealedChannel(feature).keyId(public, SealedChannel.unb64u(hostPublic))
        if (kid != expected) return NetResult.Failure(MALFORMED)
        val key = DeviceKey(
            SealedChannel.b64u(private),
            SealedChannel.b64u(public),
            kid,
            hostPublic,
            answer["generation"]?.asText(),
        )
        saveKey(id, profile, feature, key)
        return NetResult.Success(key)
    }

    private fun DeviceKey.purpose(channel: SealedChannel, device: String, purpose: String): ByteArray {
        val devicePublic = SealedChannel.unb64u(public)
        val host = SealedChannel.unb64u(hostPublic)
        val shared = channel.agree(SealedChannel.unb64u(private), host)
        return channel.key(shared, device, devicePublic, host, purpose)
    }

    private fun keyName(id: DashboardId, profile: String, feature: PhoneDataFeature) =
        stringPreferencesKey("phonedata.key.${feature.id}.${id.value}.$profile")

    private suspend fun loadKey(id: DashboardId, profile: String, feature: PhoneDataFeature): DeviceKey? {
        val sealed = secrets.data.first()[keyName(id, profile, feature)] ?: return null
        return runCatching {
            val plain = cipher.decrypt(Base64.getDecoder().decode(sealed), CONTEXT)
            json.decodeFromString(DeviceKey.serializer(), plain.decodeToString())
        }.getOrNull()
    }

    private suspend fun saveKey(id: DashboardId, profile: String, feature: PhoneDataFeature, key: DeviceKey) {
        val sealed = cipher.encrypt(json.encodeToString(DeviceKey.serializer(), key).toByteArray(), CONTEXT)
        secrets.edit { it[keyName(id, profile, feature)] = Base64.getEncoder().encodeToString(sealed) }
    }

    private suspend fun forgetKey(id: DashboardId, profile: String, feature: PhoneDataFeature) {
        secrets.edit { it.remove(keyName(id, profile, feature)) }
    }

    private fun ConnectionFailure.isUnknownKey(): Boolean =
        this is ConnectionFailure.UnexpectedResponse && status == CONFLICT && HostKeyConflict.isKeyLoss(detail)

    private fun NetResult<JsonElement>.objectOrMalformed(): NetResult<JsonObject> = when (this) {
        is NetResult.Success -> (value as? JsonObject)?.let { NetResult.Success(it) } ?: NetResult.Failure(MALFORMED)
        is NetResult.Failure -> this
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement.asText(): String? =
        (this as? JsonPrimitive)?.let { it.intOrNull?.toString() ?: it.contentOrNull }

    private companion object {
        const val API = "/api/plugins/talaria_push"
        const val CONTEXT = "hermes.phonedata.v1"
        const val DEVICE_ID_CHARS = 24
        const val CONFLICT = 409
        val MALFORMED = ConnectionFailure.UnexpectedResponse(null, "The push plugin gave an unexpected answer")
    }
}
