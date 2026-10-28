package lab.dawnforge.hermes.core.data.groups

import java.util.Base64
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.gateway.GatewayClient
import lab.dawnforge.hermes.core.gateway.GatewayClosedException
import lab.dawnforge.hermes.core.gateway.RpcException
import lab.dawnforge.hermes.core.gateway.RpcTimeoutException
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** Whether this Hermes hosts group chats, and its rooms. */
sealed interface GroupsState {
    data object Loading : GroupsState

    data class Available(val capabilities: GroupCapabilities, val rooms: List<GroupRoom>) : GroupsState

    /** A Hermes without hosted rooms (no `groups.*`, or not all a room needs). */
    data object Unsupported : GroupsState

    data class Failed(val failure: ConnectionFailure) : GroupsState
}

/**
 * Hermes' hosted group chats on the live connection (`groups.*`, sent without a profile: rooms are
 * the whole gateway's), and what the push plugin keeps for them: files sent into a room
 * (`room-files`) and a room's picture (`room-images`).
 */
class GroupsRepository(private val requests: DashboardRequests, private val gateway: () -> GatewayClient?) {
    private val _state = MutableStateFlow<GroupsState>(GroupsState.Loading)
    val state: StateFlow<GroupsState> = _state.asStateFlow()

    private val _pictures = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

    /** Rooms' own pictures, by room id. */
    val pictures: StateFlow<Map<String, ByteArray>> = _pictures.asStateFlow()
    private var pictureRevisions = emptyMap<String, String>()

    val capabilities: GroupCapabilities? get() = (_state.value as? GroupsState.Available)?.capabilities

    /** What a room left behind when its screen closed: its unsent text and unsettled send. */
    data class Parked(val draft: String, val outbox: GroupRoomOutbox)

    private val parked = HashMap<String, Parked>()

    /** Keeps [roomId]'s draft and unsettled send for its next opening (in memory, as iOS keeps them). */
    fun park(roomId: String, draft: String, outbox: GroupRoomOutbox) = synchronized(parked) {
        if (draft.isEmpty() && outbox.pending == null) parked.remove(roomId) else parked[roomId] = Parked(draft, outbox)
    }

    fun unpark(roomId: String): Parked? = synchronized(parked) { parked.remove(roomId) }

    /** Asks what the gateway can do, then lists its rooms. A known list stays through a passing failure. */
    suspend fun refresh(): GroupsState {
        val next = when (val result = call("groups.capabilities", null)) {
            is NetResult.Success -> {
                val capabilities = GroupCapabilities.parse(result.value)
                when {
                    capabilities == null -> GroupsState.Failed(MALFORMED)

                    !capabilities.foundation -> GroupsState.Unsupported

                    else -> when (val rooms = list()) {
                        is NetResult.Success -> GroupsState.Available(capabilities, rooms.value)

                        is NetResult.Failure -> (_state.value as? GroupsState.Available)?.copy(
                            capabilities = capabilities,
                        )
                            ?: GroupsState.Failed(rooms.failure)
                    }
                }
            }

            is NetResult.Failure -> when {
                result.failure.isUnknownMethod() -> GroupsState.Unsupported
                _state.value is GroupsState.Available -> _state.value
                else -> GroupsState.Failed(result.failure)
            }
        }
        _state.value = next
        return next
    }

    /** Lists the rooms again, keeping what's known on a failure. */
    suspend fun refreshRooms() {
        val rooms = (list() as? NetResult.Success)?.value ?: return
        _state.update { (it as? GroupsState.Available)?.copy(rooms = rooms) ?: it }
    }

    /** The live rooms, most recently changed first. */
    private suspend fun list(): NetResult<List<GroupRoom>> =
        call("groups.list", buildJsonObject { put("include_disbanded", false) }).map { reply ->
            ((reply as? JsonObject)?.get("rooms") as? JsonArray).orEmpty()
                .mapNotNull(GroupRoom::parse)
                .filterNot(GroupRoom::isDisbanded)
        }

    /**
     * Makes a room of [members] (each `{member_id, profile, handle, display_name}`). [roomId] is the
     * client's and doubles as the retry key: the same id finds a room an unanswered try made.
     */
    suspend fun create(roomId: String, name: String, members: List<Map<String, String>>): NetResult<GroupRoom> {
        val params = buildJsonObject {
            put("room_id", roomId)
            put("name", name)
            put(
                "members",
                JsonArray(members.map { row -> JsonObject(row.mapValues { JsonPrimitive(it.value) }) }),
            )
        }
        return call("groups.create", params).parsed { GroupRoom.parse((it as? JsonObject)?.get("room")) }
    }

    /** A room and its driver. Disbanded rooms are included, so a closed room is seen as closed. */
    suspend fun state(roomId: String): NetResult<Pair<GroupRoom, GroupDriverStatus?>> = call(
        "groups.state",
        buildJsonObject {
            put("room_id", roomId)
            put("include_disbanded", true)
        },
    ).parsed { reply ->
        val row = reply as? JsonObject
        GroupRoom.parse(row?.get("room"))?.let { it to GroupDriverStatus.parse(row?.get("driver_status")) }
    }

    suspend fun log(roomId: String, sinceSeq: Int, limit: Int? = null): NetResult<GroupLogPage> = call(
        "groups.log",
        buildJsonObject {
            put("room_id", roomId)
            put("since_seq", sinceSeq)
            put("include_disbanded", true)
            limit?.let { put("limit", it) }
        },
    ).parsed(GroupLogPage::parse)

    /** Posts the user's message; the same [eventId] again is the same message, never a second one. */
    suspend fun send(roomId: String, eventId: String, text: String): NetResult<GroupSendResult> = call(
        "groups.send",
        buildJsonObject {
            put("room_id", roomId)
            put("event_id", eventId)
            put(
                "payload",
                buildJsonObject {
                    put("text", text)
                    put("thread_id", MAIN_THREAD)
                },
            )
        },
    ).parsed { reply ->
        val row = reply as? JsonObject
        GroupEvent.parse(row?.get("event"))?.let {
            GroupSendResult(it, (row?.get("driver_started") as? JsonPrimitive)?.booleanOrNull == true)
        }
    }

    /** Stops the room's queued and running work. */
    suspend fun stop(roomId: String): NetResult<Unit> =
        call("groups.stop", buildJsonObject { put("room_id", roomId) }).map { }

    /**
     * Answers a member's approval with [choice] (`once` or `deny`). False when the gateway no longer
     * held it (answered elsewhere, or it timed out).
     */
    suspend fun approve(roomId: String, approval: GroupApproval, choice: String): NetResult<Boolean> = call(
        "groups.approve",
        buildJsonObject {
            put("room_id", roomId)
            put("member_id", approval.memberId)
            put("task_id", approval.taskId)
            put("execution_generation", approval.executionGeneration)
            put("choice", choice)
            put("request_id", approval.requestId)
        },
    ).map { reply ->
        val resolved = (((reply as? JsonObject)?.get("result") as? JsonObject)?.get("resolved") as? JsonPrimitive)
        resolved?.longOrNull?.let { it > 0 } ?: true
    }

    /** Ends a room for good: its work, history and members' sessions. */
    suspend fun disband(roomId: String): NetResult<Unit> =
        call("groups.disband", buildJsonObject { put("room_id", roomId) }).map { }

    /** Stores a file for a room (the plugin's `room-files`) and answers the `@file:` line its message carries. */
    suspend fun uploadFile(id: DashboardId, name: String, bytes: ByteArray): NetResult<String> {
        val body = buildJsonObject {
            put("name", name)
            put("data_base64", Base64.getEncoder().encodeToString(bytes))
        }
        val timeout = maxOf(UPLOAD_TIMEOUT_MS, body.toString().length / BYTES_PER_MS)
        val result = requests.run(id) { url, jar, cf ->
            post(url, jar, cf, PLUGIN, body, segments = listOf("room-files"), timeoutMs = timeout)
        }
        return result.parsed { reply ->
            val row = reply as? JsonObject ?: return@parsed null
            if ((row["ok"] as? JsonPrimitive)?.booleanOrNull != true) return@parsed null
            row.text("ref_text")?.trim()?.takeIf { it.startsWith(FILE) && it.length > FILE.length }
                ?: row.text("path")?.trim()?.takeIf(String::isNotEmpty)?.let { FILE + reference(it) }
        }
    }

    /** Reads the rooms' pictures that changed since the last look. */
    suspend fun refreshPictures(id: DashboardId) {
        val listed = requests.run(id) { url, jar, cf -> get(url, jar, cf, PLUGIN, listOf("room-images")) }
        val revisions = ((listed as? NetResult.Success)?.value as? JsonObject)
            ?.takeIf { (it["ok"] as? JsonPrimitive)?.booleanOrNull == true }
            ?.let { it["revisions"] as? JsonObject }
            ?.mapNotNull { (room, rev) ->
                (rev as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let {
                    room to
                        it
                }
            }
            ?.toMap() ?: return
        val fetched = HashMap<String, Pair<ByteArray, String>>()
        for ((room, revision) in revisions) {
            if (pictureRevisions[room] == revision || !isPictureId(room)) continue
            val answer = requests.run(id) { url, jar, cf -> get(url, jar, cf, PLUGIN, listOf("room-images", room)) }
            val row = (answer as? NetResult.Success)?.value as? JsonObject ?: continue
            val data = row.text("data_base64")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            val rev = row.text("rev")
            if (data != null && data.isNotEmpty() && !rev.isNullOrEmpty()) fetched[room] = data to rev
        }
        _pictures.update { current ->
            current.filterKeys(revisions::containsKey) + fetched.mapValues { it.value.first }
        }
        pictureRevisions = pictureRevisions.filterKeys(revisions::containsKey) + fetched.mapValues { it.value.second }
    }

    /** Sets a room's picture (a square JPEG), or removes it with null. */
    suspend fun setPicture(id: DashboardId, roomId: String, jpeg: ByteArray?): NetResult<Unit> {
        if (!isPictureId(roomId)) return NetResult.Failure(MALFORMED)
        val result = requests.run(id) { url, jar, cf ->
            if (jpeg == null) {
                delete(url, jar, cf, PLUGIN, listOf("room-images", roomId))
            } else {
                val body = buildJsonObject { put("data_base64", Base64.getEncoder().encodeToString(jpeg)) }
                put(url, jar, cf, PLUGIN, listOf("room-images", roomId), body)
            }
        }
        return when (result) {
            is NetResult.Failure -> result

            is NetResult.Success -> {
                val rev = (result.value as? JsonObject)?.text("rev")
                if (jpeg != null && rev.isNullOrEmpty()) return NetResult.Failure(MALFORMED)
                _pictures.update { if (jpeg == null) it - roomId else it + (roomId to jpeg) }
                pictureRevisions = if (jpeg == null) pictureRevisions - roomId else pictureRevisions + (roomId to rev!!)
                NetResult.Success(Unit)
            }
        }
    }

    private suspend fun call(method: String, params: JsonObject?): NetResult<JsonElement> {
        val client = gateway() ?: return NetResult.Failure(NOT_CONNECTED)
        return try {
            NetResult.Success(client.call(method, params, CALL_TIMEOUT_MS))
        } catch (e: RpcException) {
            NetResult.Failure(ConnectionFailure.UnexpectedResponse(e.code, e.message))
        } catch (_: GatewayClosedException) {
            NetResult.Failure(NOT_CONNECTED)
        } catch (_: RpcTimeoutException) {
            NetResult.Failure(ConnectionFailure.TimedOut)
        }
    }

    private fun ConnectionFailure.isUnknownMethod() = this is ConnectionFailure.UnexpectedResponse &&
        (status == UNKNOWN_METHOD || detail.orEmpty().contains("unknown method"))

    private inline fun <T> NetResult<JsonElement>.map(transform: (JsonElement) -> T): NetResult<T> = when (this) {
        is NetResult.Success -> NetResult.Success(transform(value))
        is NetResult.Failure -> this
    }

    private inline fun <T> NetResult<JsonElement>.parsed(transform: (JsonElement) -> T?): NetResult<T> = when (this) {
        is NetResult.Success -> transform(value)?.let { NetResult.Success(it) } ?: NetResult.Failure(MALFORMED)
        is NetResult.Failure -> this
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    companion object {
        /** The one thread a room's composer writes to. */
        const val MAIN_THREAD = "main"

        /** The largest file `room-files` takes. */
        const val MAX_FILE_BYTES = 50L * 1024 * 1024

        val NOT_CONNECTED = ConnectionFailure.NetworkError("The live connection is not open")
        private val MALFORMED = ConnectionFailure.UnexpectedResponse(null, "Hermes gave an unexpected answer")
        private const val PLUGIN = "/api/plugins/talaria_push"
        private const val FILE = "@file:"
        private const val UNKNOWN_METHOD = -32601
        private const val ROOM_NOT_FOUND = 4112
        private const val ROOM_GONE = 4114
        private const val CALL_TIMEOUT_MS = 30_000L
        private const val UPLOAD_TIMEOUT_MS = 180_000L

        /** About 0.5 Mbit/s: a slow link can still finish a large upload. */
        private const val BYTES_PER_MS = 64L
        private val PICTURE_ID = Regex("^[A-Za-z0-9._:-]+$")

        /** The room is gone (disbanded, or never was): its screen closes. */
        fun isRoomGone(failure: ConnectionFailure): Boolean = failure is ConnectionFailure.UnexpectedResponse &&
            (
                failure.status == ROOM_NOT_FOUND || failure.status == ROOM_GONE ||
                    failure.detail.orEmpty().contains("hosted room not found", ignoreCase = true)
                )

        private fun isPictureId(roomId: String) = PICTURE_ID.matches(roomId)

        /** A path Hermes reads back whole: quoted when it holds spaces, brackets or quotes. */
        private fun reference(path: String): String {
            if (path.none { it.isWhitespace() || it in "()[]{}<>\"'`" }) return path
            val quote = listOf('`', '"', '\'').firstOrNull { it !in path } ?: return path
            return "$quote$path$quote"
        }
    }
}
