package lab.dawnforge.hermes.core.data.plugin

import java.io.IOException
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.settings.ConnectorsRepository
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The host's push plugin (`talaria_push`, `/api/plugins/talaria_push/…`) beyond push itself: what it
 * serves, taking chats over from Hermes Desktop, what Desktop has on screen, and which senders'
 * replies notify.
 */
class PushPluginRepository(
    private val requests: DashboardRequests,
    private val connectors: ConnectorsRepository,
    /** For the plugin's published version, outside the dashboard. */
    private val web: OkHttpClient,
) {
    /**
     * What the plugin reports. Without a capabilities route Hermes' plugin list says whether it is
     * missing, too old, or installed but not served.
     */
    suspend fun state(id: DashboardId, profile: String): NetResult<PluginState> {
        val result = requests.run(id) { url, jar, cf -> get(url, jar, cf, API, listOf("capabilities")) }
        return when (result) {
            is NetResult.Success -> parsed(PushPluginJson.status(result.value)?.let { PluginState.Reported(it) })

            is NetResult.Failure -> if (result.failure.isMissingRoute()) {
                NetResult.Success(PluginState.Absent(hosted(id, profile)))
            } else {
                result
            }
        }
    }

    private suspend fun hosted(id: DashboardId, profile: String): HostedPlugin {
        val plugins = (connectors.plugins(id, profile) as? NetResult.Success)?.value ?: return HostedPlugin.Unknown
        val plugin = plugins.firstOrNull { it.name == NAME || it.name == REPOSITORY_FOLDER }
        return when {
            plugin == null -> HostedPlugin.Missing
            PluginVersions.older(plugin.version, CAPABILITIES_SINCE) -> HostedPlugin.Old
            else -> HostedPlugin.NotServed
        }
    }

    /**
     * Takes a chat Hermes Desktop or a terminal holds, by every id it is known by (at most four):
     * Hermes drops their claim unless they are replying.
     */
    suspend fun takeover(id: DashboardId, profile: String, sessionIds: List<String>): NetResult<TakeoverStatus> {
        val ids = sessionIds.filter(String::isNotBlank).distinct().take(MAX_TAKEOVER_IDS)
        val result = requests.run(id) { url, jar, cf ->
            post(
                url,
                jar,
                cf,
                API,
                buildJsonObject { put("session_ids", JsonArray(ids.map(::JsonPrimitive))) },
                mapOf("profile" to profile),
                listOf("sessions", "takeover"),
            )
        }
        return when (result) {
            is NetResult.Failure -> result
            is NetResult.Success -> parsed(PushPluginJson.takeover(result.value))
        }
    }

    /** Which chats Hermes Desktop or the web dashboard had on screen since [since] (host seconds). */
    suspend fun desktopViews(id: DashboardId, profile: String, since: Double?): NetResult<DesktopViews> {
        val result = requests.run(id) { url, jar, cf ->
            val query = mapOf("profile" to profile, "since" to since?.let { BigDecimal(it).toPlainString() })
            get(url, jar, cf, API, listOf("sessions", "desktop-views"), query)
        }
        return when (result) {
            is NetResult.Failure -> result
            is NetResult.Success -> parsed(PushPluginJson.desktopViews(result.value))
        }
    }

    suspend fun replySources(id: DashboardId, profile: String): NetResult<ReplySources> =
        requests.run(id) { url, jar, cf ->
            get(url, jar, cf, API, SOURCES_ROUTE, mapOf("profile" to profile))
        }.sources()

    /** Whether replies to messages from [source] notify; answers all of them as the host now has them. */
    suspend fun setReplySource(
        id: DashboardId,
        profile: String,
        source: ReplySource,
        notify: Boolean,
    ): NetResult<ReplySources> = requests.run(id) { url, jar, cf ->
        val body = buildJsonObject {
            put("source", source.key)
            put("notify", notify)
        }
        put(url, jar, cf, API, SOURCES_ROUTE, body, mapOf("profile" to profile))
    }.sources()

    /**
     * Reports the user's answers to confirmations and fetches those still waiting
     * (`present/exchange`). Needs the profile paired with the plugin: 409 otherwise, and 404 from a
     * plugin older than confirmations.
     */
    suspend fun exchange(id: DashboardId, profile: String, exchange: PresentExchange): NetResult<List<ConfirmPrompt>> =
        when (val reply = exchangeAll(id, profile, exchange)) {
            is NetResult.Failure -> reply
            is NetResult.Success -> NetResult.Success(reply.value.prompts)
        }

    /** The whole exchange: the confirmations still waiting, and cards Hermes put on a voice conversation's screen. */
    suspend fun exchangeAll(id: DashboardId, profile: String, exchange: PresentExchange): NetResult<PresentReply> {
        val result = requests.run(id) { url, jar, cf ->
            post(url, jar, cf, API, PresentJson.body(exchange), mapOf("profile" to profile), PRESENT_ROUTE)
        }
        return when (result) {
            is NetResult.Failure -> result
            is NetResult.Success -> parsed(PresentJson.reply(result.value))
        }
    }

    /**
     * The jobs whose runs don't notify (`cron/notifications`, plugin 0.33+); every other job's do.
     * A 404 means a plugin without it.
     */
    suspend fun mutedJobs(id: DashboardId, profile: String): NetResult<Set<String>> = requests.run(id) { url, jar, cf ->
        get(url, jar, cf, API, CRON_NOTIFY_ROUTE, mapOf("profile" to profile))
    }.muted()

    /** Whether [jobId]'s runs notify; answers the muted jobs as the host now has them. */
    suspend fun setJobNotifies(
        id: DashboardId,
        profile: String,
        jobId: String,
        notify: Boolean,
    ): NetResult<Set<String>> = requests.run(id) { url, jar, cf ->
        val body = buildJsonObject {
            put("job_id", jobId)
            put("notify", notify)
        }
        put(url, jar, cf, API, CRON_NOTIFY_ROUTE, body, mapOf("profile" to profile))
    }.muted()

    private fun NetResult<JsonElement>.muted(): NetResult<Set<String>> = when (this) {
        is NetResult.Failure -> this
        is NetResult.Success -> parsed(PushPluginJson.muted(value))
    }

    /**
     * Shares this phone's settings and the user's mode with Hermes, and reports what became of its
     * requests (`PUT /talaria-settings`, plugin 0.19+); answers the changes still waiting.
     */
    suspend fun syncSettings(id: DashboardId, profile: String, body: JsonObject): NetResult<List<SettingRequest>> =
        when (
            val result = requests.run(id) { url, jar, cf ->
                put(url, jar, cf, API, listOf("talaria-settings"), body, mapOf("profile" to profile))
            }
        ) {
            is NetResult.Failure -> result
            is NetResult.Success -> parsed(SettingsJson.pending(result.value))
        }

    private fun NetResult<JsonElement>.sources(): NetResult<ReplySources> = when (this) {
        is NetResult.Failure -> this
        is NetResult.Success -> parsed(PushPluginJson.replySources(value))
    }

    /**
     * Restarts the dashboard so it runs the plugin on disk; answers the boot id it had. Hermes
     * refuses while chats are replying (a 409 saying how many) and where it can't restart itself
     * (501, e.g. on Windows).
     */
    suspend fun restartDashboard(id: DashboardId): NetResult<String?> = requests.run(id) { url, jar, cf ->
        post(url, jar, cf, API, JsonObject(emptyMap()), segments = listOf("self", "restart"))
    }.let { result ->
        when (result) {
            is NetResult.Failure -> result

            is NetResult.Success -> {
                val boot = (result.value as? JsonObject)?.get("boot_id") as? JsonPrimitive
                NetResult.Success(boot?.content)
            }
        }
    }

    /**
     * Installs the plugin from its repository, turned on ([force] replaces a copy Hermes didn't
     * install from git). Hermes needs a gateway restart to load it.
     */
    suspend fun install(id: DashboardId, force: Boolean = false): NetResult<Unit> = requests.run(id) { url, jar, cf ->
        val body = buildJsonObject {
            put("identifier", REPOSITORY)
            put("enable", true)
            if (force) put("force", true)
        }
        post(url, jar, cf, "/api/dashboard/agent-plugins", body, segments = listOf("install"))
    }.let { result ->
        when (result) {
            is NetResult.Failure -> result

            is NetResult.Success -> {
                val answer = result.value as? JsonObject
                val ok = (answer?.get("ok") as? JsonPrimitive)?.content
                if (ok == "false") {
                    val error = (answer["error"] as? JsonPrimitive)?.content
                    NetResult.Failure(ConnectionFailure.UnexpectedResponse(null, error ?: "Hermes couldn't install it"))
                } else {
                    NetResult.Success(Unit)
                }
            }
        }
    }

    /** The newest version of the plugin published on GitHub; null when it can't be read. */
    suspend fun latestVersion(): String? = withContext(Dispatchers.IO) {
        try {
            web.newCall(Request.Builder().url(LATEST_URL).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body.string().lineSequence()
                    .firstOrNull { it.trimStart().startsWith("version:") }
                    ?.substringAfter("version:")?.trim()?.trim('"', '\'')
                    ?.takeIf(String::isNotEmpty)
            }
        } catch (_: IOException) {
            null
        }
    }

    /** A plugin without the route answers 404, or 405 when a route of another method has its path. */
    private fun ConnectionFailure.isMissingRoute(): Boolean = when (this) {
        is ConnectionFailure.NotFound -> true
        is ConnectionFailure.UnexpectedResponse -> status == METHOD_NOT_ALLOWED
        else -> false
    }

    private fun <T> parsed(value: T?): NetResult<T> = if (value !=
        null
    ) {
        NetResult.Success(value)
    } else {
        NetResult.Failure(MALFORMED)
    }

    companion object {
        const val NAME = "talaria_push"
        private const val REPOSITORY_FOLDER = "talaria-plugin"
        private const val API = "/api/plugins/talaria_push"
        private val SOURCES_ROUTE = listOf("notifications", "reply-sources")
        private val PRESENT_ROUTE = listOf("present", "exchange")
        private val CRON_NOTIFY_ROUTE = listOf("cron", "notifications")
        private const val MAX_TAKEOVER_IDS = 4
        private const val METHOD_NOT_ALLOWED = 405
        private const val CAPABILITIES_SINCE = "0.4.0"

        /** Where the plugin is published; Hermes installs it from there too. */
        const val REPOSITORY = "dawnforge-lab/talaria-plugin"
        private const val LATEST_URL = "https://raw.githubusercontent.com/$REPOSITORY/main/plugin.yaml"

        /** The oldest version the app offers to update to, as iOS. */
        const val RECOMMENDED = "1.0.0"

        private val MALFORMED = ConnectionFailure.UnexpectedResponse(null, "The push plugin gave an unexpected answer")
    }
}

/** Dotted version numbers, as the plugin's are compared: a `-suffix` is ignored, an unreadable one is older. */
object PluginVersions {
    fun older(version: String?, than: String): Boolean = compare(version, than) < 0

    fun compare(a: String?, b: String?): Int {
        val left = parts(a) ?: return if (parts(b) == null) 0 else -1
        val right = parts(b) ?: return 1
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    fun max(a: String?, b: String?): String? = if (compare(a, b) >= 0) a ?: b else b

    private fun parts(version: String?): List<Int>? =
        version?.trim()?.removePrefix("v")?.substringBefore('-')?.split('.')?.map { it.toIntOrNull() ?: return null }
}
