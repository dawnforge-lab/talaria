package lab.dawnforge.hermes.feature.bots.groups

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lab.dawnforge.hermes.core.data.bots.MentionAutocomplete
import lab.dawnforge.hermes.core.data.bots.MentionCandidate
import lab.dawnforge.hermes.core.data.chat.AttachmentReader
import lab.dawnforge.hermes.core.data.chat.PickFailure
import lab.dawnforge.hermes.core.data.chat.PickedFile
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.groups.GroupApproval
import lab.dawnforge.hermes.core.data.groups.GroupCapabilities
import lab.dawnforge.hermes.core.data.groups.GroupDriverStatus
import lab.dawnforge.hermes.core.data.groups.GroupEvent
import lab.dawnforge.hermes.core.data.groups.GroupLogPage
import lab.dawnforge.hermes.core.data.groups.GroupMember
import lab.dawnforge.hermes.core.data.groups.GroupMentions
import lab.dawnforge.hermes.core.data.groups.GroupMessageParts
import lab.dawnforge.hermes.core.data.groups.GroupRoom
import lab.dawnforge.hermes.core.data.groups.GroupRoomOutbox
import lab.dawnforge.hermes.core.data.groups.GroupRoomReplay
import lab.dawnforge.hermes.core.data.groups.GroupRoomTurns
import lab.dawnforge.hermes.core.data.groups.GroupsRepository
import lab.dawnforge.hermes.core.data.groups.GroupsState
import lab.dawnforge.hermes.core.data.plugin.PluginState
import lab.dawnforge.hermes.core.data.plugin.PushPluginRepository
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.feature.bots.navigation.GroupRoomRoute

/** Something to tell about the room. */
sealed interface RoomMessage {
    data class LoadFailed(val failure: ConnectionFailure) : RoomMessage

    data class SendFailed(val failure: ConnectionFailure) : RoomMessage

    /** Other text was sent while a message is still unsettled. */
    data object PendingFirst : RoomMessage

    data class AttachFailed(val failure: PickFailure) : RoomMessage

    data class UploadFailed(val failure: ConnectionFailure) : RoomMessage

    data class StopFailed(val failure: ConnectionFailure) : RoomMessage

    data class DisbandFailed(val failure: ConnectionFailure) : RoomMessage

    data class PictureFailed(val failure: ConnectionFailure?) : RoomMessage
}

/** Where the user's answer to one of the room's approvals stands. */
sealed interface ApprovalAnswer {
    data object Sending : ApprovalAnswer

    data object Sent : ApprovalAnswer

    /** Null: the gateway no longer held it. */
    data class Failed(val failure: ConnectionFailure?) : ApprovalAnswer
}

/** A file picked for the next message. */
data class RoomAttachment(val id: Long, val name: String)

data class GroupRoomUiState(
    val name: String,
    val room: GroupRoom? = null,
    val replay: GroupRoomReplay,
    val driver: GroupDriverStatus? = null,
    val capabilities: GroupCapabilities? = null,
    val loading: Boolean = true,
    val draft: String = "",
    val mentions: List<MentionCandidate> = emptyList(),
    val attachments: List<RoomAttachment> = emptyList(),
    val attaching: Boolean = false,
    val uploading: Boolean = false,
    /** The message whose delivery isn't settled yet: it keeps its retry key. */
    val pending: GroupRoomOutbox.Pending? = null,
    val sending: Boolean = false,
    val answers: Map<String, ApprovalAnswer> = emptyMap(),
    val canAttach: Boolean = false,
    val canSetPicture: Boolean = false,
    val message: RoomMessage? = null,
    /** The room is gone (disbanded here or elsewhere): the screen closes. */
    val gone: Boolean = false,
) {
    val members: List<GroupMember> get() = room?.members.orEmpty()

    /** Log events that earn a row. */
    val visibleEvents get() = replay.events.filter(GroupRoomTurns::isVisible)

    /** The member whose turn runs: none while the driver says it's idle or blocked. */
    val responder: GroupMember?
        get() {
            val status = driver
            if (status != null && (!status.working || status.blocked)) return null
            return GroupRoomTurns.pendingResponder(replay.events, members)
        }

    /** Approvals still waiting on the user (an answer sent hides its card). */
    val approvals: List<GroupApproval>
        get() = if (capabilities?.supports("groups.approve") != true) {
            emptyList()
        } else {
            driver?.approvals.orEmpty().filter { answers[it.id] != ApprovalAnswer.Sent }
        }

    val canStop: Boolean
        get() = capabilities?.supports("groups.stop") == true && driver?.let { it.running || it.working } == true

    val canSend: Boolean
        get() = room != null && !sending && !uploading && !attaching &&
            (draft.isNotBlank() || attachments.isNotEmpty())
}

/**
 * One hosted group chat. A room isn't a chat: the gateway's driver runs every member's turn, and
 * the screen polls the room's log every 3 seconds while it's on screen. A message keeps its retry
 * key until the gateway files it, so a retry is never a second message.
 */
@HiltViewModel
class GroupRoomViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val groups: GroupsRepository,
    private val dashboards: DashboardRepository,
    private val plugin: PushPluginRepository,
    private val attachmentReader: AttachmentReader,
    private val settings: AppSettings,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val route = savedState.toRoute<GroupRoomRoute>()
    private val roomId = route.roomId
    private val _state = MutableStateFlow(GroupRoomUiState(name = route.name, replay = GroupRoomReplay(roomId)))
    val state: StateFlow<GroupRoomUiState> = _state.asStateFlow()
    val pictures: StateFlow<Map<String, ByteArray>> = groups.pictures

    private var outbox = GroupRoomOutbox()
    private val picked = HashMap<Long, PickedFile>()
    private var nextId = 0L
    private var dashboard: DashboardId? = null

    /** One read of the room at a time: a poll never lands on top of a resync. */
    private val reading = Mutex()

    init {
        groups.unpark(roomId)?.let { parked ->
            outbox = parked.outbox
            _state.update { it.copy(draft = parked.draft, pending = outbox.pending) }
        }
        viewModelScope.launch { open() }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            dashboard = id
            val reported = (plugin.state(id, dashboards.activeProfile(id).first()) as? NetResult.Success)?.value
            val status = (reported as? PluginState.Reported)?.status
            _state.update {
                it.copy(
                    canAttach = status?.reports("room-files") == true,
                    canSetPicture = status?.reports("room-images") == true,
                )
            }
        }
    }

    /** Reads the room and the last of its log. */
    private suspend fun open() = reading.withLock {
        if (groups.capabilities == null) groups.refresh()
        val capabilities = groups.capabilities
        _state.update { it.copy(capabilities = capabilities) }
        if (capabilities == null) {
            _state.update { it.copy(loading = false, message = RoomMessage.LoadFailed(GroupsRepository.NOT_CONNECTED)) }
            return@withLock
        }
        readTail(capabilities)
    }

    /** The room's state and a bounded tail of its log (iOS keeps 200), replacing what's held. */
    private suspend fun readTail(capabilities: GroupCapabilities) {
        when (val state = groups.state(roomId)) {
            is NetResult.Failure -> return failedRead(state.failure)

            is NetResult.Success -> {
                val (room, driver) = state.value
                if (room.isDisbanded) return close()
                _state.update { it.copy(room = room, name = room.name, driver = driver) }
                val window = minOf(TAIL, capabilities.maxLogLimit)
                when (val page = groups.log(roomId, maxOf(0, (room.latestSeq ?: 0) - window), window)) {
                    is NetResult.Failure -> failedRead(page.failure)

                    is NetResult.Success -> {
                        val replay = GroupRoomReplay(roomId).initial(page.value)
                        _state.update { it.copy(replay = replay, loading = false) }
                        settle(replay.events)
                    }
                }
            }
        }
    }

    private fun failedRead(failure: ConnectionFailure) {
        if (GroupsRepository.isRoomGone(failure)) return close()
        _state.update { it.copy(loading = false, message = RoomMessage.LoadFailed(failure)) }
    }

    /** Follows the room while the screen shows it: a delta of its log, and its driver, every 3 s. */
    suspend fun follow() {
        while (true) {
            delay(POLL_MS)
            if (_state.value.gone) return
            poll()
        }
    }

    private suspend fun poll() = reading.withLock {
        val capabilities = _state.value.capabilities ?: groups.capabilities ?: return@withLock
        val replay = _state.value.replay
        val page = when (val result = groups.log(roomId, replay.sinceSeq)) {
            is NetResult.Success -> result.value
            is NetResult.Failure -> return@withLock if (GroupsRepository.isRoomGone(result.failure)) close() else Unit
        }
        if (page.events.firstOrNull()?.let { it.roomId != roomId } == true) return@withLock
        if (replay.hasGap || replay.skipsAhead(page)) return@withLock readTail(capabilities)
        val fresh = page.events.filter { it.roomId == roomId && it.seq > replay.cursor }
        if (fresh.isNotEmpty()) {
            _state.update { it.copy(replay = it.replay.adopt(page)) }
            settle(fresh)
        }
        // The driver is read even with no new events, or a settled room would look busy until the next one.
        if (_state.value.replay.cursor >= page.latestSeq || fresh.isEmpty()) {
            val status = (groups.state(roomId) as? NetResult.Success)?.value ?: return@withLock
            if (status.first.isDisbanded) return@withLock close()
            _state.update { state ->
                val listed = status.second?.approvals.orEmpty().map { it.id }.toSet()
                state.copy(
                    room = status.first,
                    driver = status.second,
                    // A failed answer whose approval is gone has nothing left to retry.
                    answers = state.answers.filter { (id, answer) -> answer !is ApprovalAnswer.Failed || id in listed },
                )
            }
        }
    }

    /** A send whose answer was lost settles when its message shows up in the log. */
    private fun settle(events: List<GroupEvent>) {
        if (!outbox.settled(events)) return
        outbox = GroupRoomOutbox()
        _state.update { it.copy(pending = null, message = it.message.takeUnless { m -> m is RoomMessage.SendFailed }) }
    }

    private fun close() {
        groups.park(roomId, "", GroupRoomOutbox())
        outbox = GroupRoomOutbox()
        _state.update { it.copy(gone = true, pending = null) }
        viewModelScope.launch { groups.refreshRooms() }
    }

    // ---- Composer ------------------------------------------------------------------------------

    fun updateDraft(text: String) = _state.update { it.copy(draft = text, mentions = mentionsFor(text, it.members)) }

    private fun mentionsFor(text: String, members: List<GroupMember>): List<MentionCandidate> {
        val query = MentionAutocomplete.query(text) ?: return emptyList()
        return MentionAutocomplete.filter(GroupMentions.candidates(members, EVERYONE), query)
    }

    fun pickMention(candidate: MentionCandidate) =
        updateDraft(MentionAutocomplete.complete(_state.value.draft, candidate))

    fun attach(uri: Uri) {
        _state.update { it.copy(attaching = true) }
        viewModelScope.launch {
            val limit = minOf(settings.preferences.first().attachmentLimitBytes, GroupsRepository.MAX_FILE_BYTES)
            when (val result = attachmentReader.read(uri, limit)) {
                is AttachmentReader.Result.Read -> {
                    val id = nextId++
                    picked[id] = result.file
                    _state.update {
                        it.copy(attaching = false, attachments = it.attachments + RoomAttachment(id, result.file.name))
                    }
                }

                is AttachmentReader.Result.Failed -> _state.update {
                    it.copy(attaching = false, message = RoomMessage.AttachFailed(result.failure))
                }
            }
        }
    }

    fun removeAttachment(id: Long) {
        picked.remove(id)
        _state.update { state -> state.copy(attachments = state.attachments.filter { it.id != id }) }
    }

    /** Sends the composer's text and files. Files go up first; the text then names them. */
    fun send() {
        val current = _state.value
        if (!current.canSend) return
        val text = current.draft.trim()
        val files = current.attachments.mapNotNull { attachment -> picked[attachment.id]?.let { attachment.id to it } }
        if (!outbox.accepts(text) && files.isEmpty()) {
            _state.update { it.copy(message = RoomMessage.PendingFirst) }
            return
        }
        if (files.isEmpty()) {
            _state.update { it.copy(draft = "", mentions = emptyList()) }
            viewModelScope.launch { deliver(text) }
            return
        }
        val id = dashboard ?: return
        _state.update { it.copy(uploading = true) }
        viewModelScope.launch {
            val references = mutableListOf<String>()
            for ((_, file) in files) {
                when (val uploaded = groups.uploadFile(id, file.name, file.bytes)) {
                    is NetResult.Success -> references += uploaded.value

                    is NetResult.Failure -> {
                        _state.update {
                            it.copy(uploading = false, message = RoomMessage.UploadFailed(uploaded.failure))
                        }
                        return@launch
                    }
                }
            }
            val message = GroupMessageParts.compose(text, references)
            if (!outbox.accepts(message)) {
                _state.update { it.copy(uploading = false, message = RoomMessage.PendingFirst) }
                return@launch
            }
            files.forEach { (key, _) -> picked.remove(key) }
            val sent = files.map { it.first }.toSet()
            _state.update { state ->
                // Text typed while the files went up stays in the composer.
                val rest = if (text.isNotEmpty() && state.draft.trim().startsWith(text)) {
                    state.draft.trim().removePrefix(text).trim()
                } else {
                    state.draft
                }
                state.copy(uploading = false, draft = rest, attachments = state.attachments.filter { it.id !in sent })
            }
            deliver(message)
        }
    }

    /** Retries the unsettled message with its own retry key. */
    fun retry() {
        val pending = outbox.pending ?: return
        if (_state.value.sending) return
        viewModelScope.launch { deliver(pending.text) }
    }

    private suspend fun deliver(text: String) {
        if (!outbox.accepts(text)) {
            _state.update { it.copy(message = RoomMessage.PendingFirst) }
            return
        }
        outbox = outbox.begin(text)
        val pending = outbox.pending ?: return
        _state.update { it.copy(pending = pending, sending = true) }
        val result = groups.send(roomId, pending.eventId, pending.text)
        _state.update { it.copy(sending = false) }
        when (result) {
            is NetResult.Failure -> {
                if (GroupsRepository.isRoomGone(result.failure)) return close()
                // A poll may already have seen it land; otherwise the row offers a retry with the same key.
                if (outbox.pending?.eventId == pending.eventId) {
                    _state.update { it.copy(message = RoomMessage.SendFailed(result.failure)) }
                }
            }

            is NetResult.Success -> {
                val event = result.value.event
                if (event.roomId != roomId) {
                    _state.update { it.copy(message = RoomMessage.SendFailed(MALFORMED)) }
                    return
                }
                outbox = outbox.accept(pending.eventId)
                val before = reading.withLock {
                    val cursor = _state.value.replay.cursor
                    _state.update { state ->
                        val page = GroupLogPage(listOf(event), maxOf(event.seq, state.replay.cursor), hasMore = false)
                        state.copy(
                            replay = state.replay.adopt(page),
                            pending = outbox.pending,
                            message = state.message.takeUnless { it is RoomMessage.SendFailed },
                            // The driver took it on: show the room working before the next poll says so.
                            driver = if (result.value.driverStarted) {
                                (state.driver ?: GroupDriverStatus(false, false, false, emptyList()))
                                    .copy(running = true, working = true)
                            } else {
                                state.driver
                            },
                        )
                    }
                    cursor
                }
                // It jumped past events not seen yet: read the room again rather than show a hole.
                if (event.seq > before + 1) {
                    _state.value.capabilities?.let { capabilities -> reading.withLock { readTail(capabilities) } }
                }
                groups.refreshRooms()
            }
        }
    }

    // ---- Room actions --------------------------------------------------------------------------

    fun answer(approval: GroupApproval, choice: String) {
        val current = _state.value.answers[approval.id]
        if (!approval.allows(choice) || current == ApprovalAnswer.Sending || current == ApprovalAnswer.Sent) return
        _state.update { it.copy(answers = it.answers + (approval.id to ApprovalAnswer.Sending)) }
        viewModelScope.launch {
            val answer = when (val result = groups.approve(roomId, approval, choice)) {
                is NetResult.Success -> if (result.value) ApprovalAnswer.Sent else ApprovalAnswer.Failed(null)
                is NetResult.Failure -> ApprovalAnswer.Failed(result.failure)
            }
            _state.update { it.copy(answers = it.answers + (approval.id to answer)) }
            poll()
        }
    }

    fun stop() {
        viewModelScope.launch {
            when (val result = groups.stop(roomId)) {
                is NetResult.Success -> poll()
                is NetResult.Failure -> _state.update { it.copy(message = RoomMessage.StopFailed(result.failure)) }
            }
        }
    }

    fun disband() {
        viewModelScope.launch {
            when (val result = groups.disband(roomId)) {
                is NetResult.Success -> close()
                is NetResult.Failure -> _state.update { it.copy(message = RoomMessage.DisbandFailed(result.failure)) }
            }
        }
    }

    /** A picture from the phone, cropped square to 256 px, as iOS sends it. */
    fun setPicture(uri: Uri) {
        val id = dashboard ?: return
        viewModelScope.launch {
            val jpeg = withContext(Dispatchers.IO) { runCatching { square(uri) }.getOrNull() }
            if (jpeg == null) {
                _state.update { it.copy(message = RoomMessage.PictureFailed(null)) }
                return@launch
            }
            val result = groups.setPicture(id, roomId, jpeg)
            if (result is NetResult.Failure) {
                _state.update {
                    it.copy(message = RoomMessage.PictureFailed(result.failure))
                }
            }
        }
    }

    fun removePicture() {
        val id = dashboard ?: return
        viewModelScope.launch {
            val result = groups.setPicture(id, roomId, null)
            if (result is NetResult.Failure) {
                _state.update {
                    it.copy(message = RoomMessage.PictureFailed(result.failure))
                }
            }
        }
    }

    private fun square(uri: Uri): ByteArray? {
        val source = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) ?: return null
        val side = minOf(source.width, source.height)
        val cropped = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, PICTURE_PX, PICTURE_PX, true)
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        // Its unsent text and unsettled send wait for the room's next opening.
        if (!_state.value.gone) groups.park(roomId, _state.value.draft, outbox)
    }

    companion object {
        /** The `@all` row's title is the screen's own; this stands in for it. */
        const val EVERYONE = "\u0000everyone"
        private const val POLL_MS = 3_000L
        private const val TAIL = 200
        private const val PICTURE_PX = 256
        private const val JPEG_QUALITY = 82
        private val MALFORMED = ConnectionFailure.UnexpectedResponse(null, "Hermes gave an unexpected answer")
    }
}
