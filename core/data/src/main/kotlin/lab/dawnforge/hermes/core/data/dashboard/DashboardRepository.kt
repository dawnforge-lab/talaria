package lab.dawnforge.hermes.core.data.dashboard

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.security.GeneralSecurityException
import kotlin.io.encoding.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import lab.dawnforge.hermes.core.data.secrets.SecretCipher
import lab.dawnforge.hermes.core.model.CloudflareAccessToken
import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.net.NativeTokens
import lab.dawnforge.hermes.core.net.StoredCookie

/** A saved username and password, kept only when the user chose "Save password". */
data class DashboardCredentials(val username: String, val password: String) {
    override fun toString(): String = "DashboardCredentials(username=$username, password=<redacted>)"
}

/**
 * The dashboards the user has added and their secrets.
 *
 * Plain settings (addresses, usernames, which dashboard is active) live in [settings]. Secrets
 * (password, session cookies, Cloudflare secret) live in [secrets], each encrypted by [cipher]
 * with its dashboard and kind bound in, so nothing readable is ever written to disk.
 */
class DashboardRepository(
    private val settings: DataStore<Preferences>,
    private val secrets: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    val dashboards: Flow<List<Dashboard>> = settings.data
        .map { prefs -> storedDashboards(prefs).map(StoredDashboard::toDashboard) }
        .distinctUntilChanged()

    val activeDashboardId: Flow<DashboardId?> = settings.data
        .map { prefs -> prefs[ACTIVE_ID]?.let(::DashboardId) }
        .distinctUntilChanged()

    suspend fun dashboard(id: DashboardId): Dashboard? = stored(id)?.toDashboard()

    suspend fun dashboardFor(url: DashboardUrl): Dashboard? =
        storedDashboards(settings.data.first()).firstOrNull { it.url() == url }?.toDashboard()

    /** The password provider to use when signing in again silently. */
    suspend fun passwordProvider(id: DashboardId): String? = stored(id)?.passwordProvider

    /** Adds or replaces [dashboard] and makes it the active one. */
    suspend fun saveAndActivate(dashboard: Dashboard, passwordProvider: String?) {
        settings.edit { prefs ->
            val stored = storedDashboards(prefs)
            // A dashboard new to this phone gets the chat list's "Finish setting up" card.
            if (stored.none { it.id == dashboard.id.value }) {
                prefs[FINISH_SETUP] = prefs[FINISH_SETUP].orEmpty() + dashboard.id.value
            }
            val others = stored.filterNot { it.id == dashboard.id.value }
            prefs[DASHBOARDS] =
                json.encodeToString(listSerializer, others + StoredDashboard.from(dashboard, passwordProvider))
            prefs[ACTIVE_ID] = dashboard.id.value
        }
    }

    /** The dashboards added on this phone whose setup isn't finished or put off yet. */
    val finishingSetup: Flow<Set<DashboardId>> = settings.data
        .map { prefs -> prefs[FINISH_SETUP].orEmpty().mapTo(LinkedHashSet(), ::DashboardId) }
        .distinctUntilChanged()

    /**
     * The dashboards whose saved sign-in is opened with the phone's fingerprint, face or screen
     * lock, as iOS's Face ID unlock: once each time the app starts.
     */
    val unlockRequired: Flow<Set<DashboardId>> = settings.data
        .map { prefs -> prefs[UNLOCK].orEmpty().mapTo(LinkedHashSet(), ::DashboardId) }
        .distinctUntilChanged()

    suspend fun setUnlockRequired(id: DashboardId, required: Boolean) {
        settings.edit { prefs ->
            prefs[UNLOCK] = if (required) prefs[UNLOCK].orEmpty() + id.value else prefs[UNLOCK].orEmpty() - id.value
        }
    }

    /** Whether [id]'s session is a sign-in through its own page, kept by the app's browser. */
    suspend fun isBrowserSession(id: DashboardId): Boolean = id.value in settings.data.first()[BROWSER].orEmpty()

    suspend fun setBrowserSession(id: DashboardId, browser: Boolean) {
        settings.edit { prefs ->
            prefs[BROWSER] = if (browser) prefs[BROWSER].orEmpty() + id.value else prefs[BROWSER].orEmpty() - id.value
        }
    }

    /** Setup is done for [id], or put off ("Not now"). */
    suspend fun endFinishSetup(id: DashboardId) {
        settings.edit { prefs -> prefs[FINISH_SETUP] = prefs[FINISH_SETUP].orEmpty() - id.value }
    }

    suspend fun setActive(id: DashboardId) {
        settings.edit { prefs ->
            if (storedDashboards(prefs).any { it.id == id.value }) prefs[ACTIVE_ID] = id.value
        }
    }

    /** The Hermes profile shown for [id]; "default" until the user picks another. */
    fun activeProfile(id: DashboardId): Flow<String> = settings.data
        .map { prefs -> prefs[profileKey(id)] ?: Profile.DEFAULT }
        .distinctUntilChanged()

    suspend fun setActiveProfile(id: DashboardId, profile: String) {
        settings.edit { prefs -> prefs[profileKey(id)] = profile }
    }

    private fun profileKey(id: DashboardId) = stringPreferencesKey("active_profile.${id.value}")

    /** The profile order the user set for [id], kept on this phone as on iOS; empty until they do. */
    fun profileOrder(id: DashboardId): Flow<List<String>> = settings.data
        .map { prefs -> prefs[orderKey(id)]?.split('\n')?.filter(String::isNotEmpty).orEmpty() }
        .distinctUntilChanged()

    suspend fun setProfileOrder(id: DashboardId, names: List<String>) {
        settings.edit { prefs -> prefs[orderKey(id)] = names.joinToString("\n") }
    }

    /** The name this phone shows for [id]'s default profile, as iOS keeps it; null for the usual one. */
    fun defaultProfileName(id: DashboardId): Flow<String?> = settings.data
        .map { prefs -> prefs[defaultNameKey(id)] }
        .distinctUntilChanged()

    suspend fun setDefaultProfileName(id: DashboardId, name: String?) {
        settings.edit { prefs ->
            val trimmed = name?.trim()?.takeIf(String::isNotEmpty)
            if (trimmed == null) prefs.remove(defaultNameKey(id)) else prefs[defaultNameKey(id)] = trimmed
        }
    }

    /** The glow colour picked for each of [id]'s profiles on this phone (`0xRRGGBB`), as iOS keeps them. */
    fun profileGlows(id: DashboardId): Flow<Map<String, Int>> = settings.data
        .map { prefs ->
            prefs[glowKey(id)].orEmpty().split('\n').mapNotNull { line ->
                val name = line.substringBeforeLast('=', "")
                val rgb = line.substringAfterLast('=', "").removePrefix("#").toIntOrNull(16)
                if (name.isEmpty() || rgb == null) null else name to rgb
            }.toMap()
        }
        .distinctUntilChanged()

    /** Picks [profile]'s glow, or with null goes back to its photo's ("Automatic"). */
    suspend fun setProfileGlow(id: DashboardId, profile: String, rgb: Int?) {
        settings.edit { prefs ->
            val picks = prefs[glowKey(id)].orEmpty().split('\n')
                .filter { it.isNotEmpty() && it.substringBeforeLast('=', "") != profile }
            val next = if (rgb == null) picks else picks + "$profile=#%06x".format(rgb and 0xFFFFFF)
            if (next.isEmpty()) prefs.remove(glowKey(id)) else prefs[glowKey(id)] = next.joinToString("\n")
        }
    }

    private fun glowKey(id: DashboardId) = stringPreferencesKey("profile_glow.${id.value}")

    private fun orderKey(id: DashboardId) = stringPreferencesKey("profile_order.${id.value}")

    private fun defaultNameKey(id: DashboardId) = stringPreferencesKey("default_profile_name.${id.value}")

    /** Removes the dashboard and all its secrets. Another dashboard becomes active, if any. */
    suspend fun remove(id: DashboardId) {
        secrets.edit { prefs -> SecretKind.entries.forEach { prefs.remove(secretKey(id, it)) } }
        settings.edit { prefs ->
            val remaining = storedDashboards(prefs).filterNot { it.id == id.value }
            prefs[DASHBOARDS] = json.encodeToString(listSerializer, remaining)
            prefs.remove(profileKey(id))
            prefs.remove(orderKey(id))
            prefs.remove(glowKey(id))
            prefs.remove(defaultNameKey(id))
            prefs[FINISH_SETUP] = prefs[FINISH_SETUP].orEmpty() - id.value
            prefs[UNLOCK] = prefs[UNLOCK].orEmpty() - id.value
            prefs[BROWSER] = prefs[BROWSER].orEmpty() - id.value
            if (prefs[ACTIVE_ID] == id.value) {
                val next = remaining.maxByOrNull { it.addedAt }
                if (next != null) prefs[ACTIVE_ID] = next.id else prefs.remove(ACTIVE_ID)
            }
        }
    }

    /** Forgets the session and saved password but keeps the dashboard and its Cloudflare token. */
    suspend fun signOut(id: DashboardId) {
        secrets.edit { prefs ->
            prefs.remove(secretKey(id, SecretKind.Credentials))
            prefs.remove(secretKey(id, SecretKind.Cookies))
            prefs.remove(secretKey(id, SecretKind.NativeTokens))
        }
    }

    suspend fun credentials(id: DashboardId): DashboardCredentials? =
        readSecret(id, SecretKind.Credentials, StoredCredentials.serializer())
            ?.let { DashboardCredentials(it.username, it.password) }

    suspend fun saveCredentials(id: DashboardId, credentials: DashboardCredentials?) = writeSecret(
        id,
        SecretKind.Credentials,
        StoredCredentials.serializer(),
        credentials?.let { StoredCredentials(it.username, it.password) },
    )

    suspend fun cookies(id: DashboardId): List<StoredCookie> =
        readSecret(id, SecretKind.Cookies, cookieListSerializer).orEmpty()

    suspend fun saveCookies(id: DashboardId, cookies: List<StoredCookie>) =
        writeSecret(id, SecretKind.Cookies, cookieListSerializer, cookies.takeIf { it.isNotEmpty() })

    /** The session of a native (browser) sign-in, if the dashboard was signed in that way. */
    suspend fun nativeTokens(id: DashboardId): NativeTokens? =
        readSecret(id, SecretKind.NativeTokens, NativeTokens.serializer())

    suspend fun saveNativeTokens(id: DashboardId, tokens: NativeTokens?) =
        writeSecret(id, SecretKind.NativeTokens, NativeTokens.serializer(), tokens)

    suspend fun cloudflareToken(id: DashboardId): CloudflareAccessToken? =
        readSecret(id, SecretKind.Cloudflare, StoredCloudflareToken.serializer())
            ?.let { CloudflareAccessToken(it.clientId, it.clientSecret) }

    suspend fun saveCloudflareToken(id: DashboardId, token: CloudflareAccessToken?) = writeSecret(
        id,
        SecretKind.Cloudflare,
        StoredCloudflareToken.serializer(),
        token?.takeIf { it.isConfigured }?.let { StoredCloudflareToken(it.clientId.trim(), it.clientSecret) },
    )

    private suspend fun stored(id: DashboardId): StoredDashboard? =
        storedDashboards(settings.data.first()).firstOrNull { it.id == id.value }

    private fun storedDashboards(prefs: Preferences): List<StoredDashboard> =
        prefs[DASHBOARDS]?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()

    private suspend fun <T> readSecret(id: DashboardId, kind: SecretKind, serializer: KSerializer<T>): T? {
        val encoded = secrets.data.first()[secretKey(id, kind)] ?: return null
        return try {
            val plaintext = cipher.decrypt(Base64.decode(encoded), secretContext(id, kind))
            json.decodeFromString(serializer, plaintext.decodeToString())
        } catch (_: GeneralSecurityException) {
            // The key is gone or the value was moved: treat it as never saved.
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private suspend fun <T : Any> writeSecret(
        id: DashboardId,
        kind: SecretKind,
        serializer: KSerializer<T>,
        value: T?,
    ) {
        val key = secretKey(id, kind)
        if (value == null) {
            secrets.edit { it.remove(key) }
            return
        }
        val ciphertext = cipher.encrypt(
            json.encodeToString(serializer, value).encodeToByteArray(),
            secretContext(id, kind),
        )
        secrets.edit { it[key] = Base64.encode(ciphertext) }
    }

    private enum class SecretKind(val key: String) {
        Credentials("credentials"),
        Cookies("cookies"),
        Cloudflare("cloudflare"),
        NativeTokens("native_tokens"),
    }

    private fun secretKey(id: DashboardId, kind: SecretKind) = stringPreferencesKey("${id.value}.${kind.key}")

    private fun secretContext(id: DashboardId, kind: SecretKind) = "hermes.dashboard.${id.value}.${kind.key}.v1"

    private companion object {
        val DASHBOARDS = stringPreferencesKey("dashboards.v1")
        val ACTIVE_ID = stringPreferencesKey("active_dashboard_id")
        val FINISH_SETUP = stringSetPreferencesKey("dashboards.finish_setup")
        val UNLOCK = stringSetPreferencesKey("dashboards.unlock_required")
        val BROWSER = stringSetPreferencesKey("dashboards.browser_session")
        val listSerializer = ListSerializer(StoredDashboard.serializer())
        val cookieListSerializer = ListSerializer(StoredCookie.serializer())
    }
}

@Serializable
private data class StoredDashboard(
    val id: String,
    val scheme: String,
    val host: String,
    val port: Int? = null,
    val basePath: String = "",
    val username: String? = null,
    val passwordProvider: String? = null,
    val addedAt: Long,
) {
    fun url() = DashboardUrl(scheme, host, port, basePath)

    fun toDashboard() = Dashboard(DashboardId(id), url(), username, addedAt)

    companion object {
        fun from(dashboard: Dashboard, passwordProvider: String?) = StoredDashboard(
            id = dashboard.id.value,
            scheme = dashboard.url.scheme,
            host = dashboard.url.host,
            port = dashboard.url.port,
            basePath = dashboard.url.basePath,
            username = dashboard.username,
            passwordProvider = passwordProvider,
            addedAt = dashboard.addedAt,
        )
    }
}

@Serializable
private data class StoredCredentials(val username: String, val password: String)

@Serializable
private data class StoredCloudflareToken(val clientId: String, val clientSecret: String)
