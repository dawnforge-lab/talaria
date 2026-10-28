package lab.dawnforge.hermes.core.push

import com.google.firebase.FirebaseOptions
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.core.push.e2e.NotificationE2E
import lab.dawnforge.hermes.core.push.relay.RelayClient
import lab.dawnforge.hermes.core.push.relay.RelayException
import lab.dawnforge.hermes.core.push.relay.RelayInstallation
import okhttp3.HttpUrl

/**
 * What the build knows about push: its app id, the relay it uses unless the user picks another,
 * and the Firebase options read from `google-services.json` at build time (null without one).
 */
data class PushConfig(val packageName: String, val defaultRelayUrl: String?, val firebase: FirebaseOptions?)

/** A Hermes profile's notifications as seen from this phone. */
data class ProfilePush(
    /** This phone holds a pairing (and its key) for the profile. */
    val pairedHere: Boolean,
    /** The profile notifies another device; pairing here takes its notifications over. */
    val pairedElsewhere: Boolean,
)

sealed interface PushProblem {
    /** This build has no Firebase project, so it can't get a push token. */
    data object NoFirebase : PushProblem

    /** The `talaria_push` plugin is not installed or not enabled on this Hermes. */
    data object PluginMissing : PushProblem

    /** No relay is set, or the one set is not an `https://` address. */
    data object NoRelay : PushProblem

    /** Google Play services could not hand out a token (missing, outdated, or offline). */
    data class NoToken(val detail: String?) : PushProblem

    /** The relay's nonce never arrived, so the phone could not prove its token. */
    data object ChallengeNotDelivered : PushProblem

    data class Relay(val error: RelayException) : PushProblem

    data class Dashboard(val failure: ConnectionFailure) : PushProblem
}

sealed interface PushResult<out T> {
    data class Done<out T>(val value: T) : PushResult<T>

    data class Failed(val problem: PushProblem) : PushResult<Nothing>
}

/**
 * Turns notifications on for a Hermes profile, as the iOS app does:
 * 1. register this phone's FCM token at the relay, proven with the nonce the relay pushes to it;
 * 2. create a one-time pairing code at the relay;
 * 3. hand the code to the profile's `talaria_push` plugin over the signed-in dashboard, which
 *    claims it at the relay;
 * 4. make a fresh end-to-end secret and give it to the plugin the same way, so the relay only ever
 *    sees sealed content.
 */
class PushSetup(
    private val store: PushStore,
    private val relay: RelayClient,
    private val tokens: PushTokens,
    private val challenges: TokenChallengeInbox,
    private val pluginApi: PushPluginApi,
    private val config: PushConfig,
    /** Only `https://` relays; a test may allow its local server. */
    private val parseRelayUrl: (String) -> HttpUrl? = RelayClient::relayUrl,
    private val random: SecureRandom = SecureRandom(),
) {
    /** One registration or pairing at a time: two would each register an installation. */
    private val mutex = Mutex()

    val available: Boolean get() = tokens.available

    /** The relay in use: the user's choice, else the build's default. */
    suspend fun relayUrl(): String? = store.current().relayUrl ?: config.defaultRelayUrl

    suspend fun setRelayUrl(url: String) = store.setRelayUrl(url.trim())

    /**
     * Whether to offer notifications for this profile: the build can receive them, this phone
     * holds no pairing for it, and the user hasn't said "Not now".
     */
    fun offer(dashboardId: DashboardId, profile: String): Flow<Boolean> = store.state.map { state ->
        tokens.available && !state.offerDismissed &&
            state.pairings.none { it.dashboardId == dashboardId && it.profile == profile }
    }.distinctUntilChanged()

    suspend fun dismissOffer() = store.dismissOffer()

    /** Every Hermes profile, on any dashboard, that notifies this phone. */
    val pairings: Flow<List<PushStore.Pairing>> = store.state.map { it.pairings }.distinctUntilChanged()

    /** What [profile] on [dashboardId] notifies about; nothing changes on Hermes or the relay. */
    suspend fun setPreferences(dashboardId: DashboardId, profile: String, preferences: PushStore.NotifyPreferences) {
        val pairing = store.pairingFor(dashboardId, profile) ?: return
        store.setPreferences(pairing.gatewayId, preferences)
    }

    /** Whether the plugin is there, and who the profile notifies. */
    suspend fun check(dashboardId: DashboardId, profile: String): PushResult<ProfilePush> {
        if (!tokens.available) return PushResult.Failed(PushProblem.NoFirebase)
        val status = when (val reply = plugin(dashboardId, profile, "GET", "e2e")) {
            is PushResult.Failed -> return reply
            is PushResult.Done -> reply.value as? JsonObject ?: JsonObject(emptyMap())
        }
        val ours = store.pairingFor(dashboardId, profile)
        val state = store.current()
        val pairedTo = status.text("installation_id")
        // A pairing the relay already turned away, or one to an installation this phone deleted,
        // notifies no one.
        val paired = status.bool("paired") && !status.bool("relay_rejected") && pairedTo !in state.retiredInstallations
        val here =
            ours != null && paired && pairedTo == state.installationId && status.text("gateway_id") == ours.gatewayId
        return PushResult.Done(ProfilePush(pairedHere = here, pairedElsewhere = paired && !here))
    }

    suspend fun enable(dashboardId: DashboardId, profile: String): PushResult<PushStore.Pairing> = mutex.withLock {
        if (!tokens.available) return@withLock PushResult.Failed(PushProblem.NoFirebase)
        val relayUrl = relayUrl()?.let(parseRelayUrl) ?: return@withLock PushResult.Failed(PushProblem.NoRelay)
        val capabilities = when (val reply = plugin(dashboardId, profile, "GET", "capabilities")) {
            is PushResult.Failed -> return@withLock reply
            is PushResult.Done -> (reply.value as? JsonObject)?.get("capabilities") as? JsonArray
        }
        val supported = capabilities?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        if ("pairing" !in supported || "e2e-notifications" !in supported) {
            return@withLock PushResult.Failed(PushProblem.PluginMissing)
        }
        try {
            pair(relayUrl, dashboardId, profile, retryRegistration = true)
        } catch (e: RelayException) {
            PushResult.Failed(PushProblem.Relay(e))
        } catch (_: TimeoutCancellationException) {
            PushResult.Failed(PushProblem.ChallengeNotDelivered)
        }
    }

    private suspend fun pair(
        relayUrl: HttpUrl,
        dashboardId: DashboardId,
        profile: String,
        retryRegistration: Boolean,
    ): PushResult<PushStore.Pairing> {
        val installation = when (val registered = ensureInstallation(relayUrl)) {
            is PushResult.Failed -> return registered
            is PushResult.Done -> registered.value
        }
        val code = try {
            relay.createPairing(relayUrl, installation, dashboardId.value)
        } catch (e: RelayException.Unauthorized) {
            // The relay forgot this phone (its data was reset, or it deactivated a refused token).
            store.clearInstallation()
            if (!retryRegistration) throw e
            return pair(relayUrl, dashboardId, profile, retryRegistration = false)
        }
        val pairBody = buildJsonObject {
            put("pairing_code", code)
            put("relay_url", relayUrl.toString().trimEnd('/'))
        }
        val claimed = when (val reply = plugin(dashboardId, profile, "POST", "pair", pairBody)) {
            is PushResult.Failed -> return reply
            is PushResult.Done -> reply.value as? JsonObject ?: JsonObject(emptyMap())
        }
        val gatewayId = claimed.text("gateway_id")
        if (claimed.text("installation_id") != installation.id || gatewayId == null) {
            return PushResult.Failed(
                PushProblem.Dashboard(ConnectionFailure.UnexpectedResponse(null, "The plugin paired something else")),
            )
        }

        val kid = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val secret = ByteArray(NotificationE2E.SECRET_BYTES).also(random::nextBytes)
        val keyBody = buildJsonObject {
            put("installation_id", installation.id)
            put("gateway_id", gatewayId)
            put("kid", kid)
            put("secret", NotificationE2E.b64u(secret))
        }
        val pairing = PushStore.Pairing(dashboardId, profile, gatewayId, claimed.text("gateway_name"), kid)
        // Saved before the plugin has it, so a push sealed with it the moment the plugin stores it opens.
        store.savePairing(pairing, secret)
        when (val reply = plugin(dashboardId, profile, "POST", "e2e", keyBody)) {
            is PushResult.Failed -> {
                // Without the key the plugin would send plaintext; this phone shows nothing it can't verify.
                store.removePairing(gatewayId)
                return reply
            }

            is PushResult.Done -> Unit
        }
        return PushResult.Done(pairing)
    }

    /** Turns the profile's notifications off on this phone. */
    suspend fun disable(dashboardId: DashboardId, profile: String) = mutex.withLock {
        val pairing = store.pairingFor(dashboardId, profile) ?: return@withLock
        store.removePairing(pairing.gatewayId)
        retireIfUnused()
    }

    /** Forgets every pairing of a dashboard being signed out of or removed. */
    suspend fun forget(dashboardId: DashboardId) = mutex.withLock {
        store.removePairings(dashboardId)
        retireIfUnused()
    }

    /** Firebase rotated the token: the relay must be told, with proof, or pushes stop. */
    suspend fun onNewToken(token: String) {
        store.markTokenPending()
        syncToken(token)
    }

    /** Sends a token change the relay has not heard of yet; safe to call at every start. */
    suspend fun syncToken(known: String? = null) = mutex.withLock {
        val state = store.current()
        if (!tokens.available || state.installationId == null) return@withLock
        val installation = store.installation() ?: return@withLock
        val relayUrl = relayUrl()?.let(parseRelayUrl) ?: return@withLock
        val token = known ?: runCatching { pushId() }.getOrNull() ?: return@withLock
        if (token == state.token && !state.tokenPending) return@withLock
        runCatching {
            val proof = challenges.await { relay.requestChallenge(relayUrl, token, config.packageName) }
            relay.updateToken(relayUrl, installation, token, proof)
            store.saveToken(token)
        }.onFailure { if (it is RelayException.Unauthorized) store.clearInstallation() }
    }

    private suspend fun ensureInstallation(relayUrl: HttpUrl): PushResult<RelayInstallation> {
        val token = try {
            pushId()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Play services reports a missing, outdated or offline service as one of several exceptions;
            // its type and words are kept, since they say which.
            return PushResult.Failed(PushProblem.NoToken(e.toString()))
        }
        val existing = store.installation()
        if (existing != null) {
            if (store.current().token != token) {
                val proof = challenges.await { relay.requestChallenge(relayUrl, token, config.packageName) }
                relay.updateToken(relayUrl, existing, token, proof)
                store.saveToken(token)
            }
            return PushResult.Done(existing)
        }
        val proof = challenges.await { relay.requestChallenge(relayUrl, token, config.packageName) }
        val registered = relay.register(relayUrl, token, config.packageName, proof)
        val installation = RelayInstallation(registered.installationId, registered.credential)
        store.saveInstallation(installation, token)
        return PushResult.Done(installation)
    }

    /** With nothing paired, the installation goes: every host still paired to it is turned away. */
    private suspend fun retireIfUnused() {
        if (store.current().pairings.isNotEmpty()) return
        val installation = store.installation()
        val relayUrl = relayUrl()?.let(parseRelayUrl)
        if (installation != null && relayUrl != null) runCatching { relay.delete(relayUrl, installation) }
        store.clearInstallation()
        runCatching { withContext(Dispatchers.IO) { tokens.delete() } }
    }

    /**
     * The phone's push id, asked for off the caller's thread: from a screen that is Android's main
     * thread, and Firebase refuses it there ("Must not be called on the main application thread").
     */
    private suspend fun pushId(): String = withContext(Dispatchers.IO) { tokens.token() }

    /** One of the plugin's dashboard routes for [profile]; a missing plugin answers 404. */
    private suspend fun plugin(
        dashboardId: DashboardId,
        profile: String,
        method: String,
        route: String,
        body: JsonObject? = null,
    ): PushResult<JsonElement> {
        val reply = if (method == "POST") {
            pluginApi.post(dashboardId, profile, route, body ?: JsonObject(emptyMap()))
        } else {
            pluginApi.get(dashboardId, profile, route)
        }
        return when (reply) {
            is NetResult.Success -> PushResult.Done(reply.value)

            is NetResult.Failure -> when (reply.failure) {
                is ConnectionFailure.NotFound, is ConnectionFailure.NotADashboard -> PushResult.Failed(
                    PushProblem.PluginMissing,
                )

                else -> PushResult.Failed(PushProblem.Dashboard(reply.failure))
            }
        }
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull == "true"
}
