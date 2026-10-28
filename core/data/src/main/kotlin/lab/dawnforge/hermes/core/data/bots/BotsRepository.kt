package lab.dawnforge.hermes.core.data.bots

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.gateway.GatewayClient
import lab.dawnforge.hermes.core.gateway.GatewayClosedException
import lab.dawnforge.hermes.core.gateway.RpcException
import lab.dawnforge.hermes.core.gateway.RpcTimeoutException
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** How the roster can be read on this host. */
sealed interface BotsState {
    data object Loading : BotsState

    data class Available(val roster: BotRoster) : BotsState

    /** A Hermes without Bot Mode (`profiles.list` unknown). */
    data object Unsupported : BotsState

    data class Failed(val failure: ConnectionFailure) : BotsState
}

/** A bot's description and personality, for its editor. */
data class BotDetails(val description: String, val soul: String)

/** What became of a bot's look or personality, written with `profiles.configure`. */
enum class BotWrite { Saved, Conflict, Failed }

/** The chat a bot's chat opens: its stored id, and its live id when it was just made. */
data class BotChat(val profile: String, val sessionId: String?, val runtimeId: String?)

/**
 * Bots on the live connection (Hermes' `profiles.*`, as Hermes Desktop and iOS use them): the
 * roster, a bot's look (`ui_meta['hermes-bots']`, compare-and-swap), picture and personality,
 * creating one, and its one chat (the session titled "Bot Chat", never made twice). Deleting a bot
 * is the dashboard's `DELETE /api/profiles/{name}`. `profiles.*` calls carry no profile: they name
 * the bot themselves.
 */
class BotsRepository(private val requests: DashboardRequests, private val gateway: () -> GatewayClient?) {
    private val _state = MutableStateFlow<BotsState>(BotsState.Loading)

    /** The roster as last read. */
    val state: StateFlow<BotsState> = _state.asStateFlow()

    /** The bots last read, for @mentions; empty when there are none (or no Bot Mode). */
    val roster: List<BotProfile> get() = (_state.value as? BotsState.Available)?.roster?.bots.orEmpty()

    suspend fun refresh(): BotsState {
        val next = when (val result = call("profiles.list", null, LIST_TIMEOUT_MS)) {
            is NetResult.Success -> BotRoster.parse(result.value)?.let(BotsState::Available)
                ?: BotsState.Failed(MALFORMED)

            is NetResult.Failure -> if (result.failure.isUnknownMethod()) {
                BotsState.Unsupported
            } else {
                // A roster already read stays; the failure shows beside it.
                (_state.value as? BotsState.Available)?.takeIf { result.failure == NOT_CONNECTED }
                    ?: BotsState.Failed(result.failure)
            }
        }
        _state.value = next
        return next
    }

    suspend fun describe(name: String): NetResult<BotDetails> =
        call("profiles.describe", buildJsonObject { put("name", name) }).map { reply ->
            val obj = reply as? JsonObject
            BotDetails(obj.text("description").orEmpty(), obj.text("soul").orEmpty())
        }

    /** Makes a bot: its profile (with SOUL.md), then its picture and look. */
    suspend fun create(name: String, description: String, soul: String): NetResult<Unit> = call(
        "profiles.create",
        buildJsonObject {
            put("name", name)
            put("description", description)
            put("soul", soul)
        },
        CREATE_TIMEOUT_MS,
    ).map { }

    /**
     * Writes a bot's look (merged into what the roster has, guarded by its revision), and its
     * description and personality when given. A look another client changed meanwhile comes back
     * [BotWrite.Conflict]: refresh, merge again, and retry once.
     */
    suspend fun configure(
        bot: BotProfile,
        patch: Map<String, JsonElement?>?,
        description: String? = null,
        soul: String? = null,
    ): NetResult<Map<String, BotWrite>> {
        val params = buildJsonObject {
            put("name", bot.name)
            if (patch != null) {
                put("ui_meta", buildJsonObject { put(BotProfile.META_KEY, BotMeta.merged(bot.meta, patch)) })
                bot.metaRevision?.let { revision ->
                    put("ui_meta_expected_revisions", buildJsonObject { put(BotProfile.META_KEY, revision) })
                }
            }
            description?.let { put("description", it) }
            soul?.let { put("soul", it) }
        }
        return call("profiles.configure", params).map { reply ->
            val applied = (reply as? JsonObject)?.get("applied") as? JsonObject
            buildMap {
                if (patch != null) {
                    put(
                        "ui_meta",
                        when {
                            // A host without the `applied` contract took it.
                            applied == null -> BotWrite.Saved

                            applied.bool("ui_meta") == true -> BotWrite.Saved

                            (applied["ui_meta_conflicts"] as? JsonObject)?.isNotEmpty() == true -> BotWrite.Conflict

                            else -> BotWrite.Failed
                        },
                    )
                }
                if (description != null) put("description", section(applied, "description"))
                if (soul != null) put("soul", section(applied, "soul"))
            }
        }
    }

    private fun section(applied: JsonObject?, key: String) =
        if (applied?.bool(key) == false) BotWrite.Failed else BotWrite.Saved

    /** Writes a bot's look, retrying once on a conflict with the roster read again. */
    suspend fun saveLook(bot: BotProfile, patch: Map<String, JsonElement?>): BotWrite {
        val first = (configure(bot, patch) as? NetResult.Success)?.value?.get("ui_meta") ?: return BotWrite.Failed
        if (first != BotWrite.Conflict) return first
        val fresh = (refresh() as? BotsState.Available)?.roster?.find(bot.name) ?: return BotWrite.Failed
        return (configure(fresh, patch) as? NetResult.Success)?.value?.get("ui_meta") ?: BotWrite.Failed
    }

    private val _avatars = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

    /** The bots' pictures read so far, by profile name. */
    val avatars: StateFlow<Map<String, ByteArray>> = _avatars.asStateFlow()

    /** Reads [bot]'s picture once, when it has one. */
    suspend fun loadAvatar(bot: BotProfile, again: Boolean = false) {
        if (!bot.hasAvatar) {
            _avatars.update { it - bot.name }
            return
        }
        if (!again && bot.name in _avatars.value) return
        avatar(bot.name)?.let { bytes -> _avatars.update { it + (bot.name to bytes) } }
    }

    /** A bot's picture as a data URL's bytes; null when it has none. */
    suspend fun avatar(name: String): ByteArray? {
        val reply = (
            call(
                "profiles.get_asset",
                buildJsonObject {
                    put("name", name)
                    put("asset", AVATAR)
                },
            ) as? NetResult.Success
            )?.value as? JsonObject ?: return null
        if (reply.bool("found") != true) return null
        val data = reply.text("data") ?: return null
        val base64 = data.substringAfter("base64,", data)
        return runCatching { java.util.Base64.getDecoder().decode(base64) }.getOrNull()
    }

    /** Sets a bot's picture ([png] at most 2 MB), or clears it. */
    suspend fun setAvatar(name: String, png: ByteArray?): NetResult<Unit> = setAsset(name, png).also { result ->
        if (result is NetResult.Success) _avatars.update { if (png == null) it - name else it + (name to png) }
    }

    private suspend fun setAsset(name: String, png: ByteArray?): NetResult<Unit> = call(
        "profiles.set_asset",
        buildJsonObject {
            put("name", name)
            put("asset", AVATAR)
            if (png == null) {
                put("clear", true)
            } else {
                put("data", "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png))
            }
        },
    ).map { }

    /**
     * The bot's one chat: found by its title each time, made only when it's certainly absent, and
     * never made twice (a title taken by another client meanwhile is looked up again).
     */
    suspend fun openChat(bot: BotProfile): BotChatOutcome {
        when (val found = lookup(bot)) {
            is BotChatOutcome.Open -> return found
            BotChatOutcome.Missing -> Unit
            else -> return found
        }
        val client = gateway() ?: return BotChatOutcome.Failed(NOT_CONNECTED)
        return try {
            val created = client.call(
                "session.create",
                buildJsonObject {
                    put("cols", COLS)
                    put("source", "desktop")
                    put("profile", bot.name)
                    put("title", BotProfile.CHAT_TITLE)
                    put("hidden", true)
                    put("follow_profile_config", true)
                },
                CREATE_TIMEOUT_MS,
            ) as? JsonObject
            val runtime = created.text("session_id") ?: return BotChatOutcome.Failed(MALFORMED)
            val stored = created.text("stored_session_id")
            client.call(
                "session.title",
                buildJsonObject {
                    put("session_id", runtime)
                    put("title", BotProfile.CHAT_TITLE)
                    put("profile", bot.name)
                },
            )
            client.trackSession(runtime, stored)
            BotChatOutcome.Open(BotChat(bot.name, stored, runtime))
        } catch (e: RpcException) {
            // Another client made it first: open theirs.
            if (e.code == TITLE_TAKEN ||
                "already in use" in e.message
            ) {
                lookup(bot)
            } else {
                BotChatOutcome.Failed(e.toFailure())
            }
        } catch (_: GatewayClosedException) {
            BotChatOutcome.Failed(NOT_CONNECTED)
        } catch (_: RpcTimeoutException) {
            BotChatOutcome.Failed(ConnectionFailure.TimedOut)
        }
    }

    private suspend fun lookup(bot: BotProfile): BotChatOutcome {
        val params = buildJsonObject {
            put("profile", bot.name)
            put("title", BotProfile.CHAT_TITLE)
            put("limit", LOOKUP_LIMIT)
            put("include_hidden", true)
        }
        val reply = when (val result = call("session.list", params)) {
            is NetResult.Success -> result.value as? JsonObject
            is NetResult.Failure -> return BotChatOutcome.Unchecked
        }
        val rows = (reply?.get("sessions") as? JsonArray) ?: return BotChatOutcome.Unchecked
        val parsed = rows.map { row ->
            val obj = row as? JsonObject
            val id = obj.text("id") ?: return BotChatOutcome.Unchecked
            BotChatRow(id, obj.text("resolved_id"), obj.text("title"), obj.text("root_title"))
        }
        return when (val resolved = BotChatResolver.resolve(parsed, bot.canonicalSession?.id)) {
            is BotChatResolution.Existing -> BotChatOutcome.Open(BotChat(bot.name, resolved.resumeId, null))
            BotChatResolution.Create -> BotChatOutcome.Missing
            BotChatResolution.Unconfirmed -> BotChatOutcome.Unconfirmed
        }
    }

    /** Deletes a bot: its profile, chats, memory and skills (the dashboard's REST route). */
    suspend fun delete(id: DashboardId, name: String): NetResult<Unit> = requests.run(id) { url, jar, cf ->
        delete(url, jar, cf, "/api/profiles", listOf(name))
    }.let { result ->
        when (result) {
            is NetResult.Success -> NetResult.Success(Unit)
            is NetResult.Failure -> result
        }
    }

    private suspend fun call(
        method: String,
        params: JsonObject?,
        timeoutMs: Long = CALL_TIMEOUT_MS,
    ): NetResult<JsonElement> {
        val client = gateway() ?: return NetResult.Failure(NOT_CONNECTED)
        return try {
            NetResult.Success(client.call(method, params, timeoutMs))
        } catch (e: RpcException) {
            NetResult.Failure(e.toFailure())
        } catch (_: GatewayClosedException) {
            NetResult.Failure(NOT_CONNECTED)
        } catch (_: RpcTimeoutException) {
            NetResult.Failure(ConnectionFailure.TimedOut)
        }
    }

    private fun RpcException.toFailure() = ConnectionFailure.UnexpectedResponse(code, message)

    private fun ConnectionFailure.isUnknownMethod() = this is ConnectionFailure.UnexpectedResponse &&
        (status == UNKNOWN_METHOD || detail.orEmpty().contains("unknown method"))

    private inline fun <T> NetResult<JsonElement>.map(transform: (JsonElement) -> T): NetResult<T> = when (this) {
        is NetResult.Success -> NetResult.Success(transform(value))
        is NetResult.Failure -> this
    }

    private fun JsonObject?.text(key: String) =
        (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull

    companion object {
        val NOT_CONNECTED = ConnectionFailure.NetworkError("The live connection is not open")
        private val MALFORMED = ConnectionFailure.UnexpectedResponse(null, "Hermes gave an unexpected answer")
        private const val AVATAR = "avatar"
        private const val COLS = 96
        private const val LOOKUP_LIMIT = 200
        private const val TITLE_TAKEN = 4022
        private const val UNKNOWN_METHOD = -32601
        private const val CALL_TIMEOUT_MS = 30_000L
        private const val LIST_TIMEOUT_MS = 30_000L
        private const val CREATE_TIMEOUT_MS = 60_000L
    }
}

/** Where opening a bot's chat got to. */
sealed interface BotChatOutcome {
    data class Open(val chat: BotChat) : BotChatOutcome

    /** It has none yet (only inside the repository: it's then made). */
    data object Missing : BotChatOutcome

    /** The lookup failed: not making a chat that may exist. */
    data object Unchecked : BotChatOutcome

    /** The roster says it has one, but the lookup found none: not making a second. */
    data object Unconfirmed : BotChatOutcome

    data class Failed(val failure: ConnectionFailure) : BotChatOutcome
}
