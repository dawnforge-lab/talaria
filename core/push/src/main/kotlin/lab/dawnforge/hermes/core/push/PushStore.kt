package lab.dawnforge.hermes.core.push

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lab.dawnforge.hermes.core.data.secrets.SecretCipher
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.push.relay.RelayInstallation

/**
 * What the phone keeps for push: its relay installation and, per Hermes profile it pairs, the
 * gateway id the relay routes by and that pairing's end-to-end secret.
 *
 * The device credential and pairing secrets are encrypted with [cipher], each bound to its own
 * slot; everything else is routing data the relay sees anyway.
 */
class PushStore(private val store: DataStore<Preferences>, private val cipher: SecretCipher) {

    /** A Hermes profile that notifies this phone through [gatewayId]. */
    data class Pairing(
        val dashboardId: DashboardId,
        val profile: String,
        val gatewayId: String,
        val gatewayName: String?,
        val kid: String,
        val preferences: NotifyPreferences = NotifyPreferences(),
    )

    /**
     * What a pairing notifies about, kept on the phone: the relay keeps one set per phone, and
     * each Hermes profile here has its own.
     */
    data class NotifyPreferences(
        /** Hermes event types that don't notify (`response.ready`, `approval.needed`, …). */
        val muted: Set<String> = emptySet(),
        /** Generic text only: no reply, title or question in the notification. */
        val hideText: Boolean = false,
    )

    data class State(
        val relayUrl: String?,
        val installationId: String?,
        val token: String?,
        val pairings: List<Pairing>,
        /** The FCM token changed and the relay has not been told yet. */
        val tokenPending: Boolean,
        /** The user answered the chat list's offer to set up notifications with "Not now". */
        val offerDismissed: Boolean,
        /**
         * Installations this phone deleted: a Hermes profile still paired to one notifies no one,
         * and only learns so on its next delivery.
         */
        val retiredInstallations: Set<String>,
    )

    val state: Flow<State> = store.data.map { it.toState() }

    suspend fun current(): State = state.first()

    suspend fun setRelayUrl(url: String) {
        store.edit { it[RELAY_URL] = url }
    }

    suspend fun installation(): RelayInstallation? {
        val prefs = store.data.first()
        val id = prefs[INSTALLATION_ID] ?: return null
        val sealed = prefs[CREDENTIAL] ?: return null
        val credential = runCatching { cipher.decrypt(Base64.getDecoder().decode(sealed), CREDENTIAL_CONTEXT) }
            .getOrNull() ?: return null
        return RelayInstallation(id, credential.decodeToString())
    }

    suspend fun saveInstallation(installation: RelayInstallation, token: String) {
        val sealed = Base64.getEncoder().encodeToString(
            cipher.encrypt(installation.credential.toByteArray(), CREDENTIAL_CONTEXT),
        )
        store.edit {
            it[INSTALLATION_ID] = installation.id
            it[CREDENTIAL] = sealed
            it[TOKEN] = token
            it.remove(TOKEN_PENDING)
        }
    }

    suspend fun saveToken(token: String) {
        store.edit {
            it[TOKEN] = token
            it.remove(TOKEN_PENDING)
        }
    }

    suspend fun dismissOffer() {
        store.edit { it[OFFER_DISMISSED] = "1" }
    }

    suspend fun markTokenPending() {
        store.edit { it[TOKEN_PENDING] = "1" }
    }

    /** Forgets the installation and every pairing: the relay deleted it, or nothing is paired. */
    suspend fun clearInstallation() {
        store.edit { prefs ->
            prefs[INSTALLATION_ID]?.let { id ->
                val retired = prefs[RETIRED]?.split('\n')?.filter(String::isNotEmpty).orEmpty()
                prefs[RETIRED] = (retired + id).distinct().takeLast(RETIRED_LIMIT).joinToString("\n")
            }
            prefs.asMap().keys.filter { it.name.startsWith(SECRET_PREFIX) }.forEach { prefs.remove(it) }
            prefs.remove(INSTALLATION_ID)
            prefs.remove(CREDENTIAL)
            prefs.remove(TOKEN)
            prefs.remove(TOKEN_PENDING)
            prefs.remove(PAIRINGS)
        }
    }

    suspend fun pairing(gatewayId: String): Pairing? = current().pairings.firstOrNull { it.gatewayId == gatewayId }

    suspend fun pairingFor(dashboardId: DashboardId, profile: String): Pairing? =
        current().pairings.firstOrNull { it.dashboardId == dashboardId && it.profile == profile }

    /** Saves [pairing] with its [secret], replacing any earlier pairing of the same profile. */
    suspend fun savePairing(pairing: Pairing, secret: ByteArray) {
        val sealed = Base64.getEncoder().encodeToString(cipher.encrypt(secret, secretContext(pairing.gatewayId)))
        store.edit { prefs ->
            val kept = prefs.pairings().filterNot {
                it.gatewayId == pairing.gatewayId ||
                    (it.dashboardId == pairing.dashboardId && it.profile == pairing.profile)
            }
            val replaced = prefs.pairings() - kept.toSet()
            // The replaced pairings' secrets go with them; a profile paired again keeps its preferences.
            replaced.forEach { prefs.remove(secretKey(it.gatewayId)) }
            val earlier = replaced.firstOrNull {
                it.dashboardId == pairing.dashboardId && it.profile == pairing.profile
            }
            val saved = earlier?.let { pairing.copy(preferences = it.preferences) } ?: pairing
            prefs[secretKey(pairing.gatewayId)] = sealed
            prefs[PAIRINGS] = json.encodeToString(STORED_LIST, (kept + saved).map(StoredPairing::of))
        }
    }

    suspend fun setPreferences(gatewayId: String, preferences: NotifyPreferences) {
        store.edit { prefs ->
            val updated = prefs.pairings().map {
                if (it.gatewayId ==
                    gatewayId
                ) {
                    it.copy(preferences = preferences)
                } else {
                    it
                }
            }
            prefs[PAIRINGS] = json.encodeToString(STORED_LIST, updated.map(StoredPairing::of))
        }
    }

    /** The pairing's secret, or null when it is gone or no longer decrypts. */
    suspend fun secret(gatewayId: String): ByteArray? {
        val sealed = store.data.first()[secretKey(gatewayId)] ?: return null
        return runCatching { cipher.decrypt(Base64.getDecoder().decode(sealed), secretContext(gatewayId)) }.getOrNull()
    }

    suspend fun removePairing(gatewayId: String) {
        store.edit { prefs ->
            prefs.remove(secretKey(gatewayId))
            prefs[PAIRINGS] =
                json.encodeToString(
                    STORED_LIST,
                    prefs.pairings().filterNot {
                        it.gatewayId == gatewayId
                    }.map(StoredPairing::of),
                )
        }
    }

    /** Removes the dashboard's pairings; returns what is left. */
    suspend fun removePairings(dashboardId: DashboardId): List<Pairing> {
        var left = emptyList<Pairing>()
        store.edit { prefs ->
            val (gone, kept) = prefs.pairings().partition { it.dashboardId == dashboardId }
            gone.forEach { prefs.remove(secretKey(it.gatewayId)) }
            prefs[PAIRINGS] = json.encodeToString(STORED_LIST, kept.map(StoredPairing::of))
            left = kept
        }
        return left
    }

    /**
     * True the first time [messageId] is seen. The ids of the last [SEEN_LIMIT] notifications are
     * kept, so a push the relay or Google delivers twice shows once.
     */
    suspend fun firstSighting(messageId: String): Boolean {
        var first = false
        store.edit { prefs ->
            val seen = prefs[SEEN]?.split('\n')?.filter(String::isNotEmpty).orEmpty()
            if (messageId in seen) return@edit
            first = true
            prefs[SEEN] = (seen + messageId).takeLast(SEEN_LIMIT).joinToString("\n")
        }
        return first
    }

    private fun Preferences.toState() = State(
        relayUrl = this[RELAY_URL],
        installationId = this[INSTALLATION_ID],
        token = this[TOKEN],
        pairings = pairings(),
        tokenPending = this[TOKEN_PENDING] != null,
        offerDismissed = this[OFFER_DISMISSED] != null,
        retiredInstallations = this[RETIRED]?.split('\n')?.filter(String::isNotEmpty).orEmpty().toSet(),
    )

    private fun Preferences.pairings(): List<Pairing> = this[PAIRINGS]
        ?.let { runCatching { json.decodeFromString(STORED_LIST, it) }.getOrNull() }
        .orEmpty()
        .map(StoredPairing::toPairing)

    @Serializable
    private data class StoredPairing(
        val dashboardId: String,
        val profile: String,
        val gatewayId: String,
        val gatewayName: String? = null,
        val kid: String,
        val muted: List<String> = emptyList(),
        val hideText: Boolean = false,
    ) {
        fun toPairing() = Pairing(
            DashboardId(dashboardId),
            profile,
            gatewayId,
            gatewayName,
            kid,
            NotifyPreferences(muted.toSet(), hideText),
        )

        companion object {
            fun of(pairing: Pairing) = StoredPairing(
                pairing.dashboardId.value,
                pairing.profile,
                pairing.gatewayId,
                pairing.gatewayName,
                pairing.kid,
                pairing.preferences.muted.sorted(),
                pairing.preferences.hideText,
            )
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val STORED_LIST = kotlinx.serialization.builtins.ListSerializer(StoredPairing.serializer())
        val RELAY_URL = stringPreferencesKey("relay_url")
        val INSTALLATION_ID = stringPreferencesKey("installation_id")
        val CREDENTIAL = stringPreferencesKey("installation_credential")
        val TOKEN = stringPreferencesKey("fcm_token")
        val TOKEN_PENDING = stringPreferencesKey("fcm_token_pending")
        val PAIRINGS = stringPreferencesKey("pairings.v1")
        val SEEN = stringPreferencesKey("seen_messages")
        val OFFER_DISMISSED = stringPreferencesKey("offer_dismissed")
        val RETIRED = stringPreferencesKey("retired_installations")
        const val SECRET_PREFIX = "pairing_secret."
        const val CREDENTIAL_CONTEXT = "hermes.push.installation_credential.v1"
        const val SEEN_LIMIT = 200
        const val RETIRED_LIMIT = 20

        fun secretKey(gatewayId: String) = stringPreferencesKey("$SECRET_PREFIX$gatewayId")

        fun secretContext(gatewayId: String) = "hermes.push.pairing.$gatewayId.secret.v1"
    }
}
