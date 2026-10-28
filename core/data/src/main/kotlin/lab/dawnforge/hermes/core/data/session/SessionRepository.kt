package lab.dawnforge.hermes.core.data.session

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.gateway.GatewayClient
import lab.dawnforge.hermes.core.gateway.GatewayClosedException
import lab.dawnforge.hermes.core.gateway.RpcException
import lab.dawnforge.hermes.core.gateway.RpcTimeoutException
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.LiveStatus
import lab.dawnforge.hermes.core.model.NewChat
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.model.SessionSearchHit
import lab.dawnforge.hermes.core.model.SessionSummary
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** One page of a session list, as returned (pinned rows may repeat across pages). */
data class SessionPage(val rows: List<SessionSummary>, val total: Int?)

/** The profiles of a dashboard, and which one the app is showing. */
class ProfileRepository(private val requests: DashboardRequests, private val repository: DashboardRepository) {
    /** [id]'s profiles, in the order the user set and with the default profile's name, as iOS shows them. */
    suspend fun profiles(id: DashboardId): NetResult<List<Profile>> {
        val listed = requests.run(id) { url, jar, cloudflare ->
            get(url, jar, cloudflare, "/api/profiles").map { it.profiles() }
        }
        return when (listed) {
            is NetResult.Success -> NetResult.Success(
                arrange(listed.value, repository.profileOrder(id).first(), repository.defaultProfileName(id).first()),
            )

            is NetResult.Failure -> listed
        }
    }

    /** Changes whenever [id]'s profile order or default profile name does. */
    fun appearance(id: DashboardId): Flow<Pair<List<String>, String?>> =
        repository.profileOrder(id).combine(repository.defaultProfileName(id)) { order, name -> order to name }

    suspend fun setOrder(id: DashboardId, names: List<String>) = repository.setProfileOrder(id, names)

    suspend fun setDefaultName(id: DashboardId, name: String?) = repository.setDefaultProfileName(id, name)

    fun activeProfile(id: DashboardId): Flow<String> = repository.activeProfile(id)

    suspend fun setActiveProfile(id: DashboardId, profile: String) = repository.setActiveProfile(id, profile)

    companion object {
        /** What the default profile is called until the user names it, as on iOS. */
        const val DEFAULT_NAME = "Hermes"

        /**
         * The saved [order] first (names Hermes no longer has drop out), then the rest with the
         * default profile first and the others by name. The default profile shows [defaultName].
         */
        fun arrange(profiles: List<Profile>, order: List<String>, defaultName: String?): List<Profile> {
            val rank = order.withIndex().associate { (index, name) -> name to index }
            return profiles
                .map { if (it.name == Profile.DEFAULT) it.copy(displayName = defaultName ?: DEFAULT_NAME) else it }
                .sortedWith(
                    compareBy<Profile> { rank[it.name] ?: Int.MAX_VALUE }
                        .thenBy { it.name != Profile.DEFAULT }
                        .thenBy { it.label.lowercase() },
                )
        }
    }
}

/**
 * A profile's sessions on a dashboard: the list (Hermes' `GET /api/profiles/sessions`), search,
 * and the per-session changes the dashboard's sidebar offers. Every call names its profile
 * explicitly; Hermes would otherwise act on its own default store (and a delete would quietly do
 * nothing).
 */
class SessionRepository(
    private val requests: DashboardRequests,
    /** The active dashboard's live connection, if any. */
    private val gateway: () -> GatewayClient?,
) {
    /**
     * A page of [profile]'s sessions, most recent first, like the dashboard sidebar: chats with at
     * least one message, scheduled-job runs left out. [archived] lists the archive instead, and
     * [scheduled] only the runs of scheduled jobs.
     */
    suspend fun page(
        id: DashboardId,
        profile: String,
        archived: Boolean,
        offset: Int,
        limit: Int,
        scheduled: Boolean = false,
    ): NetResult<SessionPage> = requests.run(id) { url, jar, cloudflare ->
        get(
            url,
            jar,
            cloudflare,
            "/api/profiles/sessions",
            query = mapOf(
                "profile" to profile,
                "order" to "recent",
                "min_messages" to "1",
                "archived" to if (archived) "only" else "exclude",
                if (scheduled) "source" to "cron" else "exclude_sources" to "cron",
                "limit" to limit.toString(),
                "offset" to offset.toString(),
            ),
        ).map { body ->
            SessionPage(
                rows = body.sessionRows(),
                total = ((body as? JsonObject)?.get("total") as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            )
        }
    }

    suspend fun search(
        id: DashboardId,
        profile: String,
        query: String,
        limit: Int = SEARCH_LIMIT,
    ): NetResult<List<SessionSearchHit>> = requests.run(id) { url, jar, cloudflare ->
        get(
            url,
            jar,
            cloudflare,
            "/api/sessions/search",
            query = mapOf(
                "q" to query,
                "limit" to limit.toString(),
                "profile" to profile,
                "exclude_sources" to "cron",
            ),
        ).map { it.searchHits() }
    }

    /** Renames; an empty title clears it. Hermes allows 100 characters and unique titles per profile. */
    suspend fun rename(id: DashboardId, profile: String, sessionId: String, title: String): NetResult<String?> =
        patch(id, profile, sessionId) { put("title", title.trim()) }.map { body ->
            (body as? JsonObject)?.string("title")
        }

    suspend fun setPinned(id: DashboardId, profile: String, sessionId: String, pinned: Boolean): NetResult<Unit> =
        patch(id, profile, sessionId) { put("pinned", pinned) }.map { }

    /**
     * Moves the chat's read mark. Hermes keeps one watermark per chat: it is unread while it has
     * activity newer than the mark, so a client marks it read when it shows the chat.
     */
    suspend fun setUnread(id: DashboardId, profile: String, sessionId: String, unread: Boolean): NetResult<Unit> =
        patch(id, profile, sessionId) { put("unread", unread) }.map { }

    /** Archiving also unpins: Hermes keeps showing pinned sessions in the main list even when archived. */
    suspend fun setArchived(id: DashboardId, profile: String, sessionId: String, archived: Boolean): NetResult<Unit> =
        patch(id, profile, sessionId) {
            put("archived", archived)
            if (archived) put("pinned", false)
        }.map { }

    /**
     * Whether Hermes has the chat: a link to one that's gone must not open (opening an unknown id
     * would make Hermes store an empty chat under it).
     */
    suspend fun exists(id: DashboardId, profile: String, sessionId: String): NetResult<Boolean> = when (
        val result = requests.run(id) { url, jar, cloudflare ->
            get(url, jar, cloudflare, "/api/sessions", listOf(sessionId), mapOf("profile" to profile))
        }
    ) {
        is NetResult.Success -> NetResult.Success(true)

        is NetResult.Failure -> if (result.failure is ConnectionFailure.NotFound) {
            NetResult.Success(
                false,
            )
        } else {
            result
        }
    }

    /**
     * Deletes the session and its whole compressed history, for good. Hermes answers "ok" even
     * when it skipped a session whose reply is still running, so callers should re-list to confirm.
     */
    suspend fun delete(id: DashboardId, profile: String, sessionId: String): NetResult<Unit> =
        requests.run(id) { url, jar, cloudflare ->
            delete(url, jar, cloudflare, "/api/sessions", listOf(sessionId), mapOf("profile" to profile))
        }.map { }

    /**
     * Starts a chat on the live connection, in [cwd] when given (a project's folder). Hermes stores
     * it only when the first message is sent, so until then it is not in the session list and
     * cannot be renamed or deleted.
     */
    suspend fun startChat(profile: String, cwd: String? = null): NetResult<NewChat> {
        val client = gateway() ?: return NetResult.Failure(NOT_CONNECTED)
        return try {
            val result = client.call(
                "session.create",
                buildJsonObject {
                    put("profile", profile)
                    // A folder chosen here wins over a named profile's own.
                    cwd?.takeIf(String::isNotBlank)?.let {
                        put("cwd", it)
                        put("cwd_explicit", true)
                    }
                    // Lets Hermes recognise a retried create instead of making a second chat.
                    put("idempotency_key", UUID.randomUUID().toString())
                },
                timeoutMs = CREATE_TIMEOUT_MS,
            ) as? JsonObject
            val runtimeId = result?.string("session_id")
                ?: return NetResult.Failure(ConnectionFailure.UnexpectedResponse(null, "No session id in the reply"))
            val storedId = result.string("stored_session_id")
            client.trackSession(runtimeId, storedId)
            NetResult.Success(NewChat(runtimeId, storedId, profile))
        } catch (e: RpcException) {
            NetResult.Failure(ConnectionFailure.UnexpectedResponse(e.code, e.message))
        } catch (_: GatewayClosedException) {
            NetResult.Failure(NOT_CONNECTED)
        } catch (_: RpcTimeoutException) {
            NetResult.Failure(ConnectionFailure.TimedOut)
        }
    }

    /**
     * The live chats' states, by every id each is known under (runtime and stored). Null when this
     * Hermes has no `session.active_list`. Idle and starting chats are left out.
     */
    suspend fun liveStatuses(): NetResult<Map<String, LiveStatus>?> {
        val client = gateway() ?: return NetResult.Failure(NOT_CONNECTED)
        return try {
            val rows = (client.call("session.active_list", JsonObject(emptyMap())) as? JsonObject)
                ?.get("sessions") as? JsonArray
            val statuses = mutableMapOf<String, LiveStatus>()
            for (row in rows.orEmpty()) {
                val item = row as? JsonObject ?: continue
                val status = when (item.string("status")) {
                    "waiting" -> LiveStatus.NeedsInput
                    "working" -> LiveStatus.Working
                    else -> continue
                }
                for (key in listOfNotNull(item.string("id"), item.string("session_key"))) {
                    // A chat waiting on the user outranks one working, when two rows share an id.
                    if (statuses[key] != LiveStatus.NeedsInput) statuses[key] = status
                }
            }
            NetResult.Success(statuses)
        } catch (e: RpcException) {
            if (e.code == UNKNOWN_METHOD) {
                NetResult.Success(null)
            } else {
                NetResult.Failure(ConnectionFailure.UnexpectedResponse(e.code, e.message))
            }
        } catch (_: GatewayClosedException) {
            NetResult.Failure(NOT_CONNECTED)
        } catch (_: RpcTimeoutException) {
            NetResult.Failure(ConnectionFailure.TimedOut)
        }
    }

    private suspend fun patch(
        id: DashboardId,
        profile: String,
        sessionId: String,
        fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) = requests.run(id) { url, jar, cloudflare ->
        patch(
            url,
            jar,
            cloudflare,
            "/api/sessions",
            listOf(sessionId),
            buildJsonObject {
                fields()
                put("profile", profile)
            },
        )
    }

    private companion object {
        const val SEARCH_LIMIT = 30
        const val CREATE_TIMEOUT_MS = 60_000L
        const val UNKNOWN_METHOD = -32601
        val NOT_CONNECTED = ConnectionFailure.NetworkError("The live connection is not open")
    }
}

internal inline fun <T, R> NetResult<T>.map(transform: (T) -> R): NetResult<R> = when (this) {
    is NetResult.Success -> NetResult.Success(transform(value))
    is NetResult.Failure -> this
}
