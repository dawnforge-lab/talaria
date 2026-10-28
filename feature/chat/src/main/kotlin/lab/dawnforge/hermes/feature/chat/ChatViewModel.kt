package lab.dawnforge.hermes.feature.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import lab.dawnforge.hermes.core.chat.AgentStatus
import lab.dawnforge.hermes.core.chat.ApprovalChoice
import lab.dawnforge.hermes.core.chat.ChatModel
import lab.dawnforge.hermes.core.chat.ChatRequest
import lab.dawnforge.hermes.core.chat.ChatSettings
import lab.dawnforge.hermes.core.chat.ChatState
import lab.dawnforge.hermes.core.chat.ContextBreakdown
import lab.dawnforge.hermes.core.chat.ContextUsage
import lab.dawnforge.hermes.core.chat.DelegateAgent
import lab.dawnforge.hermes.core.chat.HapticEffect
import lab.dawnforge.hermes.core.chat.HapticSignal
import lab.dawnforge.hermes.core.chat.ModelChoices
import lab.dawnforge.hermes.core.chat.NewChatRecovery
import lab.dawnforge.hermes.core.chat.ResponseHaptics
import lab.dawnforge.hermes.core.chat.SlashCommand
import lab.dawnforge.hermes.core.chat.ToolCalls
import lab.dawnforge.hermes.core.chat.UnclearDelivery
import lab.dawnforge.hermes.core.chat.branchCount
import lab.dawnforge.hermes.core.chat.normalizeHistory
import lab.dawnforge.hermes.core.chat.picking
import lab.dawnforge.hermes.core.chat.replyEnvelope
import lab.dawnforge.hermes.core.data.bots.BotMentions
import lab.dawnforge.hermes.core.data.bots.BotsRepository
import lab.dawnforge.hermes.core.data.bots.BotsState
import lab.dawnforge.hermes.core.data.bots.MentionAutocomplete
import lab.dawnforge.hermes.core.data.bots.MentionCandidate
import lab.dawnforge.hermes.core.data.cache.ChatCache
import lab.dawnforge.hermes.core.data.cache.NewChatRef
import lab.dawnforge.hermes.core.data.cache.Waiting
import lab.dawnforge.hermes.core.data.chat.AttachmentReader
import lab.dawnforge.hermes.core.data.chat.BusyMode
import lab.dawnforge.hermes.core.data.chat.ChatMemory
import lab.dawnforge.hermes.core.data.chat.ChatRepository
import lab.dawnforge.hermes.core.data.chat.Correction
import lab.dawnforge.hermes.core.data.chat.HistoryPage
import lab.dawnforge.hermes.core.data.chat.MediaFile
import lab.dawnforge.hermes.core.data.chat.MediaRepository
import lab.dawnforge.hermes.core.data.chat.ModelSwitch
import lab.dawnforge.hermes.core.data.chat.OpenedChat
import lab.dawnforge.hermes.core.data.chat.PickFailure
import lab.dawnforge.hermes.core.data.chat.PickedFile
import lab.dawnforge.hermes.core.data.chat.SendStatus
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.gateway.GatewayManager
import lab.dawnforge.hermes.core.data.gateway.WorkOnItsWay
import lab.dawnforge.hermes.core.data.live.LiveTurnStarter
import lab.dawnforge.hermes.core.data.phonedata.location.LocationSharing
import lab.dawnforge.hermes.core.data.plugin.AnswerVia
import lab.dawnforge.hermes.core.data.plugin.AnsweredCalls
import lab.dawnforge.hermes.core.data.plugin.ConfirmOutcome
import lab.dawnforge.hermes.core.data.plugin.HermesChanges
import lab.dawnforge.hermes.core.data.plugin.MapsDirections
import lab.dawnforge.hermes.core.data.plugin.PresentedConfirms
import lab.dawnforge.hermes.core.data.plugin.PushPluginJson
import lab.dawnforge.hermes.core.data.plugin.PushPluginRepository
import lab.dawnforge.hermes.core.data.plugin.ScreenCard
import lab.dawnforge.hermes.core.data.plugin.TakeoverStatus
import lab.dawnforge.hermes.core.data.profiles.ProfileGlows
import lab.dawnforge.hermes.core.data.session.Project
import lab.dawnforge.hermes.core.data.session.ProjectsRepository
import lab.dawnforge.hermes.core.data.session.SessionRepository
import lab.dawnforge.hermes.core.data.settings.AppPreferences
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.data.settings.ModelVisibility
import lab.dawnforge.hermes.core.data.share.SharedDrafts
import lab.dawnforge.hermes.core.gateway.GatewayEvent
import lab.dawnforge.hermes.core.gateway.GatewayNotice
import lab.dawnforge.hermes.core.gateway.GatewayState
import lab.dawnforge.hermes.core.gateway.ServerRequest
import lab.dawnforge.hermes.core.model.ChatAttachment
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.Delivery
import lab.dawnforge.hermes.core.model.NoteKind
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.core.voice.Dictation
import lab.dawnforge.hermes.core.voice.DictationProblem
import lab.dawnforge.hermes.core.voice.DictationUpdate
import lab.dawnforge.hermes.core.voice.ReadAloud
import lab.dawnforge.hermes.core.voice.ReadAloudState
import lab.dawnforge.hermes.core.voice.SpokenAnswer
import lab.dawnforge.hermes.core.voice.VoiceConversation
import lab.dawnforge.hermes.core.voice.VoiceConversations
import lab.dawnforge.hermes.core.voice.VoiceHost
import lab.dawnforge.hermes.core.voice.VoicePhase
import lab.dawnforge.hermes.core.voice.VoiceProblem
import lab.dawnforge.hermes.core.voice.VoiceState
import lab.dawnforge.hermes.feature.chat.navigation.ChatRoute

data class ChatUiState(
    /** The phone's own settings: text size, Return to send, haptics. */
    val prefs: AppPreferences = AppPreferences(),
    val title: String? = null,
    val items: List<ChatItem> = emptyList(),
    /** The agent is working on a turn. */
    val running: Boolean = false,
    /** Hermes' passing status line while it works. */
    val status: String? = null,
    val context: ContextUsage? = null,
    /** The newest history is loading and nothing is shown yet. */
    val loading: Boolean = true,
    val loadingOlder: Boolean = false,
    val hasOlder: Boolean = false,
    /** Why the history could not be loaded at all. */
    val error: ConnectionFailure? = null,
    /** Why the older history could not be loaded; shown at the top, with a retry. */
    val olderError: ConnectionFailure? = null,
    val live: GatewayState = GatewayState.Stopped,
    /** The profile's glow behind the top bar (`0xRRGGBB`); null for the app's accent. */
    val glow: Int? = null,
    /** Set while the connection waits for the user to repair it: the state it failed in. */
    val liveHeld: GatewayState? = null,
    /** What is typed in the composer. */
    val draft: String = "",
    /** Files picked for the next message. */
    val attachments: List<ComposerAttachment> = emptyList(),
    /** A picked file is being read. */
    val attaching: Boolean = false,
    /** What Hermes is waiting on the user for in this chat: approvals, questions, passwords. */
    val requests: List<ChatRequest> = emptyList(),
    val stopping: Boolean = false,
    val message: ChatMessage? = null,
    /** Dictation into the composer, while it runs. */
    val dictation: DictationPhase? = null,
    /** Counts messages sent from here: each one brings the transcript back to its newest end. */
    val sent: Int = 0,
    /** The transcript shown is the copy saved on the phone: the dashboard did not answer. */
    val savedCopy: Boolean = false,
    /** The model the next turn uses. */
    val model: ChatModel? = null,
    /** The model and reasoning picker, while it is open. */
    val picker: ModelPicker? = null,
    /** Slash commands that complete what is typed. */
    val suggestions: List<SlashCommand> = emptyList(),
    /** Bots the `@` being typed could mean. */
    val mentions: List<MentionCandidate> = emptyList(),
    /** The slash command the app is running (`compress`, `branch`…), shown while it works. */
    val workingCommand: String? = null,
    /** The sub-agents this chat's turns delegated to. */
    val agents: List<DelegateAgent> = emptyList(),
    /** The chat's fast mode and approvals. */
    val settings: ChatSettings? = null,
    /** The chat's working folder on the host; null until Hermes names one. */
    val cwd: String? = null,
    /** The chat's stored id; null for a new chat until its first message is stored. */
    val storedId: String? = null,
    /** The projects the chat can move to, while choosing one; empty while they load. */
    val moveTargets: List<Project>? = null,
    /** A message refused because another Hermes app has the chat, and taking it over. */
    val takeover: Takeover? = null,
    val agentsSheet: AgentsSheet? = null,
    val contextSheet: ContextSheet? = null,
    /** The reply the next message answers, quoted when it is sent. */
    val replyTo: String? = null,
    /** The row the chat was last read at, to scroll back to once it is shown; null opens at the newest. */
    val placeToRestore: String? = null,
    /** A branch is being made. */
    val branching: Boolean = false,
) {
    val canSend: Boolean get() = draft.isNotBlank() || attachments.isNotEmpty()
}

/** A file picked for the next message, as the composer shows it. */
data class ComposerAttachment(val id: Long, val name: String, val isImage: Boolean, val sizeBytes: Int)

/** Switching the chat's model or reasoning. */
data class ModelPicker(
    val choices: ModelChoices? = null,
    val failure: ConnectionFailure? = null,
    /** A costly model Hermes asks to confirm, with its message. */
    val confirm: Confirm? = null,
    val switching: Boolean = false,
    /** What the picker leaves out, kept on this phone per dashboard. */
    val visibility: ModelVisibility = ModelVisibility(),
    /** Choosing what the picker shows, instead of a model. */
    val editing: Boolean = false,
) {
    data class Confirm(val provider: String, val model: String, val message: String?)
}

/** The sub-agents sheet, while it is open. */
data class AgentsSheet(
    /** The agent whose live output is shown. */
    val following: String? = null,
    /** That output's end, once read. */
    val output: String? = null,
    /** The agent a steering note is being written for. */
    val steering: DelegateAgent? = null,
    val failure: ConnectionFailure? = null,
)

/** How the chat's context window is used, while the sheet is open. */
data class ContextSheet(val breakdown: ContextBreakdown? = null, val failure: ConnectionFailure? = null)

/** Something the screen does once, outside the chat. */
sealed interface ChatEvent {
    /** Opens another chat: a new one, or a branch of this one. */
    data class OpenChat(val route: ChatRoute) : ChatEvent
}

/** Dictation's stage, for the composer's microphone. */
enum class DictationPhase { Starting, Listening, Transcribing }

/** A one-off note for the snackbar. */
sealed interface ChatMessage {
    data class AttachFailed(val failure: PickFailure) : ChatMessage

    /** The chat now uses [model]; [nextTurn] when a turn was running, so it starts with the next one. */
    data class ModelSwitched(val model: String, val nextTurn: Boolean) : ChatMessage

    data class ModelFailed(val failure: ConnectionFailure) : ChatMessage

    data class StopFailed(val failure: ConnectionFailure) : ChatMessage

    /** The live connection dropped before an answer could go; Hermes asks again on reconnect. */
    data object AnswerNotSent : ChatMessage

    /** A message waited too long for the connection and went back into the composer. */
    data object WaitingReturned : ChatMessage

    /** Hermes had let this new chat go before a message of it arrived: it started again. */
    data object StartedAgain : ChatMessage

    /** A command needs the live connection, which is not open. */
    data object NotConnected : ChatMessage

    data class DictationFailed(val problem: DictationProblem) : ChatMessage

    /** `/bg` or `/btw` started; the answer comes as a note. */
    data class SideAgentStarted(val background: Boolean) : ChatMessage

    data object Compressed : ChatMessage

    /** `/new` in a bot's chat: it compacts instead, as the chat never resets. */
    data object BotChatNeverResets : ChatMessage

    /** Another app's share is in the composer, to review and send. */
    data object SharedReady : ChatMessage

    data object NothingToCompress : ChatMessage

    /** Compression goes on on the host; the transcript changes when it is done. */
    data object CompressPending : ChatMessage

    data class CommandFailed(val command: String, val failure: ConnectionFailure) : ChatMessage

    data class SettingFailed(val failure: ConnectionFailure) : ChatMessage

    /** Approvals are off for the whole profile, so this chat can't ask for them again. */
    data object YoloLocked : ChatMessage

    /** A steering note went to a sub-agent ([queued]), or it no longer took one. */
    data class AgentSteered(val queued: Boolean) : ChatMessage

    data class AgentFailed(val failure: ConnectionFailure) : ChatMessage

    /** The reaction wasn't saved; it was taken back. */
    data class ReactFailed(val failure: ConnectionFailure) : ChatMessage

    data class BranchFailed(val failure: ConnectionFailure?) : ChatMessage

    data object Copied : ChatMessage

    /** The chat now works in [project]'s folder. */
    data class Moved(val project: String) : ChatMessage

    data class MoveFailed(val failure: ConnectionFailure) : ChatMessage
}

/**
 * One chat: its stored history, paged from the newest end, and the turn streaming on the live
 * connection on top of it. When a turn ends the newest history is loaded again, so what the screen
 * shows always settles to what Hermes stored.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class ChatViewModel internal constructor(
    savedState: SavedStateHandle,
    private val dashboards: DashboardRepository,
    private val chats: ChatRepository,
    private val sessions: SessionRepository,
    private val gateway: GatewayManager,
    private val attachmentReader: AttachmentReader,
    private val cache: ChatCache,
    private val memory: ChatMemory,
    private val media: MediaRepository,
    private val appSettings: AppSettings,
    private val projects: ProjectsRepository,
    private val plugin: PushPluginRepository,
    private val confirms: PresentedConfirms,
    private val dictation: Dictation,
    private val readAloud: ReadAloud,
    private val voices: VoiceConversations,
    private val answeredCalls: AnsweredCalls,
    private val bots: BotsRepository,
    private val hermesChanges: HermesChanges,
    private val sharedDrafts: SharedDrafts,
    private val liveTurns: LiveTurnStarter,
    private val work: CoroutineDispatcher,
    private val clock: () -> Long,
    /** The profiles' glow colours; none in tests. */
    private val glows: ProfileGlows? = null,
    /** Location sharing, which takes a fix with a message or a voice conversation; none in tests. */
    private val location: LocationSharing? = null,
    /** What the app finishes out of sight when it leaves the screen; none in tests. */
    private val onItsWay: WorkOnItsWay? = null,
) : ViewModel() {
    @Inject
    constructor(
        savedState: SavedStateHandle,
        dashboards: DashboardRepository,
        chats: ChatRepository,
        sessions: SessionRepository,
        gateway: GatewayManager,
        attachmentReader: AttachmentReader,
        cache: ChatCache,
        memory: ChatMemory,
        media: MediaRepository,
        appSettings: AppSettings,
        projects: ProjectsRepository,
        plugin: PushPluginRepository,
        confirms: PresentedConfirms,
        dictation: Dictation,
        readAloud: ReadAloud,
        voices: VoiceConversations,
        answeredCalls: AnsweredCalls,
        bots: BotsRepository,
        hermesChanges: HermesChanges,
        sharedDrafts: SharedDrafts,
        liveTurns: LiveTurnStarter,
        glows: ProfileGlows,
        location: LocationSharing,
        onItsWay: WorkOnItsWay,
    ) : this(
        savedState,
        dashboards,
        chats,
        sessions,
        gateway,
        attachmentReader,
        cache,
        memory,
        media,
        appSettings,
        projects,
        plugin,
        confirms,
        dictation,
        readAloud,
        voices,
        answeredCalls,
        bots,
        hermesChanges,
        sharedDrafts,
        liveTurns,
        Dispatchers.Default,
        System::currentTimeMillis,
        glows,
        location,
        onItsWay,
    )

    private val route = savedState.toRoute<ChatRoute>()
    private val profile = route.profile

    private val _state = MutableStateFlow(ChatUiState(title = route.title, storedId = route.sessionId))

    init {
        glows?.let { colors ->
            viewModelScope.launch {
                dashboards.activeDashboardId.filterNotNull().collectLatest { id ->
                    colors.tint(id, route.profile).collect { glow -> _state.update { it.copy(glow = glow) } }
                }
            }
        }
    }
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var chat = ChatState(title = route.title)

    /** [ChatState.turnsEnded], watched to reload the stored copy of each ended turn. */
    private val turnsEnded = MutableStateFlow(0)

    /** Counts attaches: reopening a chat moves its last activity on Hermes, so it is marked read again. */
    private val attaches = MutableStateFlow(0)
    private var dashboardId: DashboardId? = null

    /**
     * Every request open on the connection, of any chat. Declared before the ids: setting either
     * shows this chat's requests again.
     */
    private var pending: Map<String, ServerRequest> = emptyMap()

    /** "Show in chat": the stored row to scroll to once loaded, and the pages read back for it. */
    private var revealing: Long? = route.revealRowId
    private var revealPages = 0

    /** The stored id: known for a listed chat, learned from Hermes for a new one. */
    private var storedId: String? = route.sessionId
        set(value) {
            // A new chat's draft and place move to its stored id, which the chat list opens it by.
            val runtime = route.runtimeId
            if (field == null && value != null && runtime != null) memory.move(runtime, value)
            field = value
            _state.update { it.copy(storedId = value) }
            showRequests()
        }

    /**
     * The live session id, once the chat is attached to the connection. A reopened chat learns it
     * only after the request it was asking is already open, so setting it shows that request.
     */
    private var runtimeId: String? = route.runtimeId
        set(value) {
            field = value
            showRequests()
        }
    private var attached = false

    /** Started on this phone (New chat), not opened from the list: Hermes may not have stored it. */
    private val startedHere = route.sessionId == null

    /** A message of this chat reached Hermes, which stores a chat with its first message. */
    private var everStored = false

    /** The message going out in a chat Hermes hasn't stored, kept on the phone until Hermes has it. */
    private var inFlightKey: String? = null

    /** The last write of the chat's kept message: each waits for the one before, so none overtakes another. */
    private var keeping: Job? = null

    /** Sends out now, Hermes not having answered them yet. */
    private var sendsOut = 0

    /** This chat's hold on [WorkOnItsWay], while a send is out or a turn runs in it. */
    private var workHold: (() -> Unit)? = null

    // After the ids it is keyed by: what was typed here, and where the chat was read.
    init {
        // A draft written from outside (Edit on a confirmation) while the chat is open.
        viewModelScope.launch {
            memory.prefilled.collect { chat ->
                if (chat != memoryKey()) return@collect
                memory.draft(chat)?.let { draft -> _state.update { it.copy(draft = draft.text, replyTo = null) } }
            }
        }
        memoryKey()?.let { key ->
            memory.draft(key)?.let { draft -> _state.update { it.copy(draft = draft.text, replyTo = draft.replyTo) } }
                // A chat just started with a message ready to send.
                ?: route.draft?.let { text -> _state.update { it.copy(draft = text) } }
            val place = memory.place(key)
            // A chat opened to show a message goes there instead.
            if (place != null && route.revealRowId == null) {
                viewModelScope.launch {
                    if (!appSettings.preferences.first().openAtNewest) _state.update { it.copy(placeToRestore = place) }
                }
            }
        }
        viewModelScope.launch { appSettings.preferences.collect { prefs -> _state.update { it.copy(prefs = prefs) } } }
    }

    /** The reply's haptics, as iOS plays them; the screen plays them while it shows, if they're on. */
    private val haptics = ResponseHaptics()
    private val _hapticEffects = MutableSharedFlow<HapticEffect>(extraBufferCapacity = HAPTIC_BUFFER)
    val hapticEffects: Flow<HapticEffect> = _hapticEffects.asSharedFlow()
    private var hapticEnd: Job? = null

    private val _events = Channel<ChatEvent>(Channel.BUFFERED)
    val events: Flow<ChatEvent> = _events.receiveAsFlow()

    private val commands = ChatCommands(
        viewModelScope,
        chats,
        sessions,
        profile,
        object : ChatCommands.Host {
            override val runtimeId: String? get() = this@ChatViewModel.runtimeId

            override fun note(kind: NoteKind, title: String?, text: String) {
                chat = chat.withNote(kind, title, text)
                publish()
            }

            override fun sendText(text: String) {
                _state.update { it.copy(draft = text, suggestions = emptyList()) }
                send()
            }

            override fun prefill(text: String) = _state.update { it.copy(draft = text) }

            override fun openModels() = this@ChatViewModel.openModels()

            override fun toggleYolo() = setYolo(chat.settings?.yolo != true)

            override fun openChat(route: ChatRoute) {
                _events.trySend(ChatEvent.OpenChat(route))
            }

            override fun reload() = loadNewest()

            override fun working(command: String?) = _state.update { it.copy(workingCommand = command) }

            override fun tell(message: ChatMessage) = _state.update { it.copy(message = message) }
        },
        botChat = route.botChat,
    )

    /** Every stored row loaded so far, oldest first, hidden ones too (paging counts them). */
    private var rows: List<JsonObject> = emptyList()
    private var newestJob: Job? = null
    private var olderJob: Job? = null

    /** The files picked for the next message, by [ComposerAttachment.id]. */
    private val picked = LinkedHashMap<Long, PickedFile>()
    private var nextId = 0L

    // What another app shared goes in the composer, after any draft, and is never sent on its own.
    init {
        route.shared?.let(sharedDrafts::take)?.let { shared ->
            val added = shared.files.map { file ->
                val id = nextId++
                picked[id] = file
                ComposerAttachment(id, file.name, file.isImage, file.bytes.size)
            }
            _state.update { state ->
                val text = listOf(
                    state.draft.trim(),
                    shared.text.trim(),
                ).filter(String::isNotEmpty).joinToString("\n\n")
                state.copy(draft = text, attachments = state.attachments + added, message = ChatMessage.SharedReady)
            }
        }
    }

    /** Messages typed here and not yet with Hermes, by their row key. */
    private val outgoing = HashMap<String, Outgoing>()
    private var busyMode = BusyMode.Interrupt

    private var resyncJob: Job? = null

    /** The message waiting for the connection, if one is ([wait]). */
    private var waitingKey: String? = null
    private var waitingSince = 0L
    private var waitLimitJob: Job? = null

    /**
     * A message on its way: what was typed, and what of it has been uploaded already. [knownUsers]
     * are the stored user messages there were when it was sent, to tell its stored copy apart.
     */
    private class Outgoing(
        val text: String,
        val files: List<PickedFile>,
        val knownUsers: Set<String>,
        /** Said in a voice conversation: Hermes answers to be heard, and it steers a running turn. */
        val spoken: Boolean = false,
    ) {
        /** The `@file:` reference of each file uploaded, by its index in [files]. */
        val references = HashMap<Int, String>()
        val imagesSent = HashSet<Int>()

        /** It went out, but the reply was lost with the connection: Hermes may or may not have it. */
        var unclear = false

        /** The text its stored copy reads as. */
        val sentText: String get() = text.ifEmpty { if (files.any { it.isImage }) IMAGE_ONLY_PROMPT else "" }

        fun forgetUploads() {
            references.clear()
            imagesSent.clear()
        }
    }

    init {
        viewModelScope.launch {
            dashboardId = dashboards.activeDashboardId.first()
            showSaved()
            restoreWaiting()
            if (storedId != null) loadNewest() else _state.update { it.copy(loading = false) }
            // A call answered opens its chat anew, which talks; a copy of it further back doesn't.
            answerCallIfWaiting()
            if (route.talk) openVoice()
            launch { followConnection() }
            launch { gateway.events.collect(::onEvent) }
            launch { gateway.notices.collect(::onNotice) }
            launch { gateway.pendingRequests.collect(::onRequests) }
        }
        viewModelScope.launch {
            // Each ended turn asks for the stored copy; a burst of end events asks once.
            turnsEnded.drop(1).debounce(RELOAD_DEBOUNCE_MS).collect { loadNewest() }
        }
    }

    override fun onCleared() {
        workHold?.invoke()
        workHold = null
        // A voice conversation talks in this chat: it ends with it.
        conversation?.end()
        runtimeId?.let(chats::leave)
    }

    // ---- The live session ----------------------------------------------------------------------

    private suspend fun followConnection() {
        var wasConnected = false
        gateway.status.collect { status ->
            _state.update { it.copy(live = status.state, liveHeld = status.held) }
            val connected = status.state is GatewayState.Connected
            if (connected && !attached) {
                attach()
            } else if (connected && !wasConnected) {
                // Back after a drop: the client replayed what it could; Hermes' snapshot and the
                // stored copy cover the rest.
                resync()
            }
            wasConnected = connected
        }
    }

    /** Attaches the chat to the live connection, and picks up a turn already running in it. */
    private suspend fun attach() {
        val opened = open() ?: return
        attached = true
        attaches.update { it + 1 }
        (chats.busyMode() as? NetResult.Success)?.let { busyMode = it.value }
        catchUp(opened)
        sendWaiting()
    }

    /** Hermes' snapshot of the chat; attaching it if it was not (`session.resume` reuses a live session). */
    private suspend fun open(): OpenedChat? {
        val stored = storedId
        val runtime = runtimeId
        var opened = when {
            stored != null -> chats.open(profile, stored)
            runtime != null -> chats.attach(runtime, null)
            else -> return null
        }
        // Reopened from the list's unsent chats: Hermes may have stored it by the id it gave it.
        val reserved = route.reservedId
        if (stored == null && reserved != null && opened.isNotFound()) opened = chats.open(profile, reserved)
        var startedAgain = false
        val failure = (opened as? NetResult.Failure)?.failure
        if (failure != null && NewChatRecovery.startsAgain(startedHere, hasStoredMessages(), failure)) {
            opened = chats.startAgain(profile, chat.cwd ?: route.newChatCwd)
            startedAgain = opened is NetResult.Success
        }
        return (opened as? NetResult.Success)?.value?.also {
            runtimeId = it.runtimeId
            if (storedId == null || startedAgain) storedId = it.storedId
            if (startedAgain) _state.update { state -> state.copy(message = ChatMessage.StartedAgain) }
        }
    }

    private fun NetResult<*>.isNotFound() = (this as? NetResult.Failure)?.failure is ConnectionFailure.NotFound

    /** Whether Hermes has stored a message of this chat, so has stored the chat. */
    private fun hasStoredMessages(): Boolean = everStored || chat.history.any { it is ChatItem.User }

    /**
     * Brings the chat up to [opened]: the newest stored copy first, then the running turn on top of
     * it. A turn's sealed segments are stored as it goes, so its text so far is placed only once the
     * history is in (see [ChatState.withRunningTurn]).
     */
    private suspend fun catchUp(opened: OpenedChat) {
        if (storedId != null) {
            newestJob?.cancelAndJoin()
            refreshNewest()
        }
        val streaming = chat.turn.any { it is ChatItem.Assistant && it.streaming }
        chat = when {
            opened.running && !streaming -> chat.withRunningTurn(opened.inflightReply)
            else -> chat.withRunning(opened.running)
        }.let {
            it.copy(
                title = opened.title ?: it.title,
                model = opened.model ?: it.model,
                settings = opened.settings ?: it.settings,
                cwd = opened.cwd ?: it.cwd,
            )
        }
        publish()
        // Whether a turn runs decides how a call with no result reads.
        if (rows.isNotEmpty()) applyRows(chat.turnsEnded)
        settleUnclear(opened)
    }

    /** After a reconnect or a replay gap: Hermes' snapshot says where the chat is now. */
    private fun resync() {
        if (resyncJob?.isActive == true) return
        resyncJob = viewModelScope.launch {
            val opened = open()
            if (opened == null) {
                refreshNewest()
            } else {
                catchUp(opened)
                sendWaiting()
            }
        }
    }

    /**
     * Messages whose reply was lost with the connection: Hermes may have them. One is delivered if
     * the stored history holds it (a user message newer than those there when it was sent, with
     * its text), or the snapshot shows it running or queued; otherwise it is sent again, once.
     */
    private fun settleUnclear(opened: OpenedChat) {
        val stored = chat.history.filterIsInstance<ChatItem.User>()
        for ((key, message) in outgoing.entries.toList()) {
            if (!message.unclear) continue
            message.unclear = false
            val settled = UnclearDelivery.settle(
                message.sentText,
                message.knownUsers,
                stored,
                opened.inflightPrompt,
                opened.queuedPrompt,
            )
            when (settled) {
                is UnclearDelivery.Stored ->
                    delivered(key, rowId = settled.row.key.removePrefix("row-"), intoTurn = settled.row.isSteer)

                UnclearDelivery.Received -> delivered(key, rowId = null, intoTurn = false)

                UnclearDelivery.Lost -> viewModelScope.launch { submit(key) }
            }
        }
    }

    /**
     * Keeps the chat read on Hermes while it shows: now, once it is attached, and after each turn
     * ends. Hermes counts a chat unread while it has activity newer than its read mark, which
     * clients move, and attaching counts as activity. The screen runs it while started.
     */
    suspend fun keepRead() {
        combine(turnsEnded, attaches) { ended, attached -> ended to attached }.collect { markRead() }
    }

    // A failed mark leaves the chat unread in the list, which the next one corrects.
    private suspend fun markRead() {
        val id = dashboards.activeDashboardId.first() ?: return
        val session = storedId ?: return
        sessions.setUnread(id, profile, session, unread = false)
    }

    private fun onEvent(event: GatewayEvent) {
        if (event.sessionId == null || event.sessionId != runtimeId) return
        if (storedId == null) learnStoredId(event)
        val wasRunning = chat.running
        chat = chat.reduce(event)
        publish()
        HapticSignal.of(event.type, event.payload)?.let(::feel)
        if (!event.replayed) noteConfirmTool(event)
        if (!event.replayed) noteSettingsTool(event)
        if (!event.replayed) noteMapsTool(event)
        if (!event.replayed) voiceHears(event, wasRunning)
        if (wasRunning && !chat.running) sendNextQueued()
    }

    /** Hermes read or asked to change this phone's settings: their sync picks it up. */
    private fun noteSettingsTool(event: GatewayEvent) {
        if (event.type != "tool.complete") return
        val payload = event.payload ?: return
        val name = (payload["name"] as? JsonPrimitive)?.contentOrNull
        if (ToolCalls.names(name, payload["args"]).any { it.equals(SETTINGS_TOOL, ignoreCase = true) } ||
            name.equals(SETTINGS_TOOL, ignoreCase = true)
        ) {
            hermesChanges.sync()
        }
    }

    /** Hermes asked to open Maps from this chat (`talaria_maps navigate`): its Go opens Maps without a tap. */
    private fun noteMapsTool(event: GatewayEvent) {
        if (event.type != "tool.complete") return
        val payload = event.payload ?: return
        val name = (payload["name"] as? JsonPrimitive)?.contentOrNull
        if (!name.equals(MAPS_TOOL, ignoreCase = true)) return
        val promptId = MapsDirections.navigatePromptId(payload["result"]) ?: return
        confirms.expectMaps(promptId, storedId ?: return)
    }

    /** Calls that ask for a confirmation, by tool id, while they run. */
    private val confirmCalls = mutableSetOf<String>()

    /**
     * Hermes asking for a confirmation (`talaria_present`, on its own or in a `tool_call` wrapper)
     * shows it without waiting on the push.
     */
    private fun noteConfirmTool(event: GatewayEvent) {
        val payload = event.payload ?: return
        val name = (payload["name"] as? JsonPrimitive)?.contentOrNull
        val id = (payload["tool_id"] as? JsonPrimitive)?.contentOrNull
        when (event.type) {
            "tool.start" -> {
                if (ToolCalls.names(name, payload["args"]).none { it.equals(CONFIRM_TOOL, ignoreCase = true) }) return
                id?.let(confirmCalls::add)
                // The tool may wait for the answer: fetch once it has stored the prompt.
                viewModelScope.launch {
                    delay(CONFIRM_STORED_MS)
                    confirms.sync()
                }
            }

            "tool.complete" -> {
                val asked = (id != null && confirmCalls.remove(id)) || name.equals(CONFIRM_TOOL, ignoreCase = true)
                if (asked) confirms.sync()
            }
        }
    }

    private fun feel(signal: HapticSignal) {
        val (effects, end) = haptics.on(signal, clock())
        effects.forEach(_hapticEffects::tryEmit)
        if (end != null) {
            hapticEnd?.cancel()
            hapticEnd = viewModelScope.launch {
                delay(ResponseHaptics.CONCLUSION_DELAY_MS)
                haptics.conclude(end)?.let(_hapticEffects::tryEmit)
            }
        }
    }

    /** A new chat has no stored row until its first message; Hermes names it in its events. */
    private fun learnStoredId(event: GatewayEvent) {
        val payload = event.payload ?: return
        val id = when (event.type) {
            "session.info" -> payload["stored_session_id"]
            "session.title" -> payload["session_id"]
            else -> null
        }
        storedId = (id as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)
    }

    private fun onNotice(notice: GatewayNotice) {
        when (notice) {
            is GatewayNotice.SessionRebound -> if (notice.previousId == runtimeId) runtimeId = notice.sessionId

            is GatewayNotice.ReplayGap -> if (notice.sessionId == runtimeId) {
                // A restarted Hermes has forgotten what was staged for a message not yet sent.
                if (notice.reason == GatewayNotice.GapReason.GatewayRestarted) {
                    outgoing.values.forEach { it.forgetUploads() }
                }
                resync()
            }
        }
    }

    /** Reconnects a connection held for repair, without repairing it. */
    fun reconnectAnyway() = gateway.endHold(reconnect = true)

    // ---- Composer ------------------------------------------------------------------------------

    fun updateDraft(text: String) {
        // Typing ends dictation; the words it heard stay.
        if (_state.value.dictation == DictationPhase.Listening) endDictation()
        val suggestions = commands.suggestions(text)
        _state.update { it.copy(draft = text, suggestions = suggestions, mentions = mentionsFor(text, suggestions)) }
        saveDraft()
    }

    /** The bots an `@` at the end of the draft could mean (not this chat's own bot); read once when needed. */
    private fun mentionsFor(text: String, slash: List<SlashCommand>): List<MentionCandidate> {
        if (slash.isNotEmpty()) return emptyList()
        val query = MentionAutocomplete.query(text) ?: return emptyList()
        if (bots.state.value == BotsState.Loading && !rosterAsked) {
            rosterAsked = true
            viewModelScope.launch {
                bots.refresh()
                val draft = _state.value.draft
                _state.update { it.copy(mentions = mentionsFor(draft, it.suggestions)) }
            }
        }
        return MentionAutocomplete.filter(MentionAutocomplete.bots(bots.roster, profile), query).take(MAX_MENTIONS)
    }

    private var rosterAsked = false

    /** Puts the picked bot's tag in place of the `@` being typed. */
    fun pickMention(candidate: MentionCandidate) {
        val text = MentionAutocomplete.complete(_state.value.draft, candidate)
        _state.update { it.copy(draft = text, mentions = emptyList()) }
        saveDraft()
    }

    /** What goes to Hermes for [text]: as typed, plus a note naming the bots it @mentions. */
    private fun withMentions(text: String): String = BotMentions.annotation(text, bots.roster, profile) ?: text

    private fun saveDraft() {
        val key = memoryKey() ?: return
        val current = _state.value
        memory.saveDraft(key, ChatMemory.Draft(current.draft, current.replyTo))
    }

    private fun memoryKey(): String? = (storedId ?: route.runtimeId)?.let { "$profile/$it" }

    // ---- Dictation and read aloud ---------------------------------------------------------------

    private var dictating: Job? = null

    /** The reply being read aloud, if any; one at a time, across chats. */
    val reading: StateFlow<ReadAloudState?> = readAloud.state

    /**
     * Starts dictation, or stops it so its words arrive. The words go after what the draft held
     * when it started; nothing is sent. While Hermes transcribes, a tap does nothing.
     */
    fun toggleDictation() {
        when (_state.value.dictation) {
            DictationPhase.Transcribing -> return

            DictationPhase.Starting, DictationPhase.Listening -> {
                dictation.stop()
                return
            }

            null -> Unit
        }
        val before = _state.value.draft
        _state.update { it.copy(dictation = DictationPhase.Starting) }
        dictating = viewModelScope.launch {
            try {
                val id = dashboards.activeDashboardId.first() ?: return@launch
                dictation.dictate(id, profile).collect { update ->
                    when (update) {
                        is DictationUpdate.Listening -> _state.update {
                            it.copy(dictation = DictationPhase.Listening, draft = dictated(before, update.words))
                        }

                        DictationUpdate.Transcribing -> _state.update {
                            it.copy(dictation = DictationPhase.Transcribing)
                        }

                        is DictationUpdate.Done -> {
                            _state.update { it.copy(draft = dictated(before, update.words)) }
                            saveDraft()
                        }

                        is DictationUpdate.Failed -> _state.update {
                            it.copy(message = ChatMessage.DictationFailed(update.problem))
                        }
                    }
                }
            } finally {
                _state.update { it.copy(dictation = null) }
                dictating = null
            }
        }
    }

    /** Ends dictation where it is, keeping what it heard. */
    private fun endDictation() {
        dictating?.cancel()
        dictating = null
        _state.update { it.copy(dictation = null) }
        saveDraft()
    }

    /** Reads [reply] aloud, or stops it if it's being read. */
    fun readAloud(reply: ChatItem.Assistant) {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            readAloud.toggle(reply.key, reply.text, id, profile)
        }
    }

    fun pauseReading() = readAloud.pause()

    fun resumeReading() = readAloud.resume()

    fun setReadingSpeed(speed: Float) = readAloud.setSpeed(speed)

    fun stopReading() = readAloud.stop()

    // ---- Voice conversation ----------------------------------------------------------------------

    private var conversation: VoiceConversation? = null
    private val _voice = MutableStateFlow<VoiceState?>(null)

    /** The voice conversation in this chat, while one is open. */
    val voice: StateFlow<VoiceState?> = _voice.asStateFlow()
    private val _voiceCards = MutableStateFlow<List<ScreenCard>>(emptyList())

    /** What Hermes put on the conversation's screen, newest last. */
    val voiceCards: StateFlow<List<ScreenCard>> = _voiceCards.asStateFlow()

    private val voiceHost = object : VoiceHost {
        override val turnRunning: Boolean get() = chat.running

        override suspend fun said(text: String, afterInterrupt: Boolean): Boolean {
            // Spoken over a reply that is stopping: the words are a new turn, not a steer of it.
            if (afterInterrupt) withTimeoutOrNull(INTERRUPT_WAIT_MS) { while (chat.running) delay(INTERRUPT_POLL_MS) }
            answerBySpeech(text)
            return sendSpoken(text)
        }

        override fun interrupt() = stop()
    }

    /**
     * A call from Hermes answered for this chat: its voice conversation opens saying who called and
     * why, then reads the latest reply (unless Hermes waits on the user, which the brief says).
     */
    private fun answerCallIfWaiting() {
        val call = answeredCalls.take(route.sessionId, route.runtimeId, storedId) ?: return
        val reply = _state.value.items.lastOrNull { it is ChatItem.Assistant && !it.streaming } as? ChatItem.Assistant
        val result = reply?.text?.trim()?.takeIf { it.isNotEmpty() && !call.waitsOnUser }
            ?.let { if (it.length > MAX_CALL_RESULT) it.take(MAX_CALL_RESULT) + " […]" else it }
        openVoice(listOfNotNull(call.intro, result).joinToString(PARAGRAPH))
    }

    /** Opens a voice conversation in this chat; reading aloud stops. [opening] is said first. */
    fun openVoice(opening: String? = null) {
        if (conversation?.active == true) return
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            if (dictating != null) endDictation()
            readAloud.stop()
            val started = voices.create(viewModelScope, id, profile, voiceHost)
            conversation = started
            _voiceCards.value = emptyList()
            launch {
                started.state.collect { state ->
                    _voice.value = state.takeIf { it.phase != VoicePhase.Ended }
                    if (state.phase == VoicePhase.Ended) {
                        if (state.problem == VoiceProblem.NoPermission) {
                            _state.update {
                                it.copy(message = ChatMessage.DictationFailed(DictationProblem.NoPermission))
                            }
                        }
                        if (conversation === started) conversation = null
                        cancel()
                    }
                }
            }
            launch { confirms.cards.collect { cards -> if (started.active) _voiceCards.update { it + cards } } }
            started.start(opening)
            // A position now, and as the phone moves, while "talking to Hermes" is shared.
            location?.voiceCallStarted { started.active }
        }
    }

    fun endVoice() = conversation?.end()

    fun pauseVoice() = conversation?.pause()

    fun resumeVoice() = conversation?.resumeMic()

    fun interruptVoice() = conversation?.interrupt()

    fun setVoiceMuted(muted: Boolean) = conversation?.setMuted(muted)

    fun voiceProblemShown() = conversation?.problemShown()

    /** The chat's events the conversation speaks from, and the cards it shows. */
    private fun voiceHears(event: GatewayEvent, wasRunning: Boolean) {
        val talking = conversation?.takeIf { it.active } ?: return
        val payload = event.payload
        when (event.type) {
            "message.delta" -> (payload?.get("text") as? JsonPrimitive)?.contentOrNull?.let(talking::replyDelta)

            "message.complete", "error" -> {
                talking.replyEnded()
                confirms.sync()
            }

            // Text the model wrote before a tool call was working aloud, not the answer.
            "message.interim" -> talking.replySegmentEnded()

            "tool.start", "tool.complete" -> {
                if (event.type == "tool.start") talking.replySegmentEnded()
                val name = (payload?.get("name") as? JsonPrimitive)?.contentOrNull
                if (ToolCalls.names(name, payload?.get("args")).any { it.equals(SHOW_TOOL, ignoreCase = true) }) {
                    // The card is kept just after the tool starts.
                    viewModelScope.launch {
                        if (event.type == "tool.start") delay(CONFIRM_STORED_MS)
                        confirms.sync()
                    }
                }
            }
        }
        if (wasRunning && !chat.running) talking.replyEnded()
    }

    /**
     * A plain yes or no answers the one thing waiting on the user (a confirmation, or a command's
     * approval in this chat) as a tap would, as on iOS. Hermes still hears the words.
     */
    private suspend fun answerBySpeech(words: String) {
        val prompts = confirms.waiting()
        val approvals = _state.value.requests.filterIsInstance<ChatRequest.Approval>()
        if (prompts.size + approvals.size != 1) return
        val answer = SpokenAnswer.of(words)
        prompts.firstOrNull()?.let { prompt ->
            val outcome = when {
                SpokenAnswer.confirms(words, prompt.spokenYes) -> ConfirmOutcome.Confirmed
                answer == SpokenAnswer.Answer.No -> ConfirmOutcome.Declined
                else -> return
            }
            confirms.answer(prompt, outcome, AnswerVia.Voice)
            return
        }
        val approval = approvals.first()
        when (answer) {
            SpokenAnswer.Answer.Yes -> if (ApprovalChoice.Once in
                approval.choices
            ) {
                approve(approval, ApprovalChoice.Once)
            }

            SpokenAnswer.Answer.No -> approve(approval, ApprovalChoice.Deny)

            SpokenAnswer.Answer.Other -> Unit
        }
    }

    /** What was said, sent as a spoken turn (or a steer of the running one). False when it couldn't go. */
    private suspend fun sendSpoken(text: String): Boolean {
        if (!attached || gateway.status.value.state !is GatewayState.Connected) return false
        val key = "out-${nextId++}"
        outgoing[key] = Outgoing(text, emptyList(), knownUsers(), spoken = true)
        val message = ChatItem.User(key = key, text = text, timestampMs = clock(), delivery = Delivery.Sending)
        chat = chat.withOutgoing(message)
        publish()
        if (chat.running) correct(key) else submit(key)
        return key !in outgoing ||
            chat.items.none { it is ChatItem.User && it.key == key && it.delivery == Delivery.Failed }
    }

    // ---- Replies, reactions, branches ----------------------------------------------------------

    /** The next message answers [reply]: it is quoted above what is typed. */
    fun replyTo(reply: ChatItem.Assistant) {
        _state.update { it.copy(replyTo = reply.text) }
        saveDraft()
    }

    fun cancelReply() {
        _state.update { it.copy(replyTo = null) }
        saveDraft()
    }

    /** Whether the user can react to [reply]: a stored one, or the newest while no turn runs. */
    fun canReact(reply: ChatItem.Assistant): Boolean = attached && !reply.streaming &&
        (reply.rowId != null || (!chat.running && chat.items.lastOrNull { it is ChatItem.Assistant } == reply))

    /** Reacts to [reply] with [emoji], or takes the reaction back when it is the one already there. */
    fun react(reply: ChatItem.Assistant, emoji: String) {
        val runtime = runtimeId ?: return
        if (!canReact(reply)) return
        val before = reply.reactions
        chat = chat.withReactions(reply.key, before.picking(emoji))
        publish()
        viewModelScope.launch {
            when (val result = chats.react(runtime, reply.rowId, emoji)) {
                is NetResult.Success -> chat = chat.withReactions(reply.key, result.value.reactions, result.value.rowId)

                is NetResult.Failure -> {
                    chat = chat.withReactions(reply.key, before)
                    _state.update { it.copy(message = ChatMessage.ReactFailed(result.failure)) }
                }
            }
            publish()
        }
    }

    /**
     * A new chat with this one's messages up to and including [reply] (`session.branch`), opened
     * over this one. Hermes counts the messages to keep, so the whole stored history is read first.
     */
    fun branchFrom(reply: ChatItem.Assistant) {
        val runtime = runtimeId ?: return
        val rowId = reply.rowId ?: return
        val id = dashboardId ?: return
        val session = storedId ?: return
        if (_state.value.branching || chat.running) return
        _state.update { it.copy(branching = true) }
        viewModelScope.launch {
            try {
                val all = allRows(id, session) ?: return@launch _state.update {
                    it.copy(message = ChatMessage.BranchFailed(null))
                }
                val count = branchCount(all, rowId) ?: return@launch _state.update {
                    it.copy(message = ChatMessage.BranchFailed(null))
                }
                when (val branch = chats.branch(runtime, count)) {
                    is NetResult.Success -> _events.trySend(
                        ChatEvent.OpenChat(
                            ChatRoute(profile, branch.value.storedId, branch.value.runtimeId, branch.value.title),
                        ),
                    )

                    is NetResult.Failure -> _state.update {
                        it.copy(message = ChatMessage.BranchFailed(branch.failure))
                    }
                }
            } finally {
                _state.update { it.copy(branching = false) }
            }
        }
    }

    /** Every stored row of the chat, oldest first: the loaded ones, and the older pages read now. */
    private suspend fun allRows(id: DashboardId, session: String): List<JsonObject>? {
        var all = rows
        var more = _state.value.hasOlder
        while (more) {
            when (val page = chats.history(id, profile, session, offset = all.size)) {
                is NetResult.Success -> {
                    val known = all.mapNotNullTo(HashSet()) { it.rowId() }
                    all = page.value.rows.filter { it.rowId() == null || it.rowId() !in known } + all
                    more = page.value.hasMore && page.value.rows.isNotEmpty()
                }

                is NetResult.Failure -> return null
            }
        }
        return all
    }

    fun copied() = _state.update { it.copy(message = ChatMessage.Copied) }

    // ---- Taking a chat over ----------------------------------------------------------------------

    /** The chat taken over automatically once already: a second refusal asks instead of looping. */
    private var tookOverAutomatically = false
    private var takeoverJob: Job? = null
    private var takeoverIds: List<String> = emptyList()

    /** [key] was refused: Hermes Desktop or a terminal holds the chat. Offers to take it, or takes it. */
    private fun offerTakeover(key: String, refusal: String) {
        val (refusedId, surface) = PushPluginJson.ownerOf(refusal)
        takeoverIds = listOfNotNull(runtimeId, refusedId, storedId).distinct()
        _state.update { it.copy(takeover = Takeover(key, TakeoverOwner.of(surface), TakeoverPhase.Offered)) }
        viewModelScope.launch {
            if (appSettings.preferences.first().takeOverChats && !tookOverAutomatically) {
                tookOverAutomatically = true
                takeOver()
            }
        }
    }

    /**
     * Takes the chat over, waiting while the other app replies (every [TAKEOVER_POLL_MS], for up to
     * [TAKEOVER_WAIT_MS]), then sends the refused message again.
     */
    fun takeOver() {
        val current = _state.value.takeover ?: return
        if (current.phase !is TakeoverPhase.Offered && current.phase !is TakeoverPhase.Failed) return
        val id = dashboardId ?: return
        setTakeover(TakeoverPhase.Waiting)
        takeoverJob?.cancel()
        takeoverJob = viewModelScope.launch {
            val deadline = clock() + TAKEOVER_WAIT_MS
            var transient = 0
            while (true) {
                when (val result = plugin.takeover(id, profile, takeoverIds)) {
                    is NetResult.Success -> when (result.value) {
                        TakeoverStatus.Free, TakeoverStatus.TakenOver -> {
                            _state.update { it.copy(takeover = null) }
                            retrySend(current.key)
                            return@launch
                        }

                        TakeoverStatus.SameHost -> return@launch setTakeover(TakeoverPhase.HeldHere)

                        TakeoverStatus.Busy -> {
                            transient = 0
                            if (clock() > deadline) {
                                return@launch setTakeover(TakeoverPhase.Failed(TakeoverProblem.StillReplying))
                            }
                        }
                    }

                    is NetResult.Failure -> {
                        val failure = result.failure
                        val status = (failure as? ConnectionFailure.UnexpectedResponse)?.status
                        when {
                            failure is ConnectionFailure.NotFound || status == HTTP_METHOD_NOT_ALLOWED ->
                                return@launch setTakeover(TakeoverPhase.Unavailable(TakeoverProblem.PluginMissing))

                            status == HTTP_NOT_IMPLEMENTED ->
                                return@launch setTakeover(TakeoverPhase.Unavailable(TakeoverProblem.Unsupported))

                            failure.isTransient() && ++transient <= TAKEOVER_TRANSIENT_TRIES -> Unit

                            else -> return@launch setTakeover(TakeoverPhase.Failed(TakeoverProblem.Error(failure)))
                        }
                    }
                }
                delay(TAKEOVER_POLL_MS)
            }
        }
    }

    fun dismissTakeover() {
        takeoverJob?.cancel()
        _state.update { it.copy(takeover = null) }
    }

    private fun setTakeover(phase: TakeoverPhase) = _state.update { state ->
        state.copy(takeover = state.takeover?.copy(phase = phase))
    }

    private fun ConnectionFailure.isTransient(): Boolean = when (this) {
        is ConnectionFailure.NetworkError, ConnectionFailure.TimedOut, ConnectionFailure.ConnectionRefused -> true

        is ConnectionFailure.RateLimited -> true

        is ConnectionFailure.DashboardUnavailable -> true

        is ConnectionFailure.UnexpectedResponse -> status.let { code ->
            code != null && (code == HTTP_TIMEOUT || code >= HTTP_SERVER_ERROR)
        }

        else -> false
    }

    /** Lists the projects this chat can move to: those with a folder, but not its own. */
    fun openMove() {
        val stored = storedId ?: return
        _state.update { it.copy(moveTargets = emptyList()) }
        viewModelScope.launch {
            when (val tree = projects.tree(profile)) {
                is NetResult.Success -> _state.update { state ->
                    val all = tree.value.orEmpty()
                    val own = all.firstOrNull { !it.isHome && stored in it.sessionIds }?.id
                    state.copy(
                        moveTargets = state.moveTargets?.let {
                            all.filter { p -> p.canTakeChats && p.id != own }
                        },
                    )
                }

                is NetResult.Failure -> _state.update {
                    it.copy(moveTargets = null, message = ChatMessage.MoveFailed(tree.failure))
                }
            }
        }
    }

    fun closeMove() = _state.update { it.copy(moveTargets = null) }

    /** Moves the chat to [project]'s folder: Hermes points it there, a running agent too. No files move. */
    fun move(project: Project) {
        val stored = storedId ?: return
        val path = project.primaryPath ?: return
        _state.update { it.copy(moveTargets = null) }
        viewModelScope.launch {
            val message = when (val result = projects.move(profile, stored, path)) {
                is NetResult.Success -> ChatMessage.Moved(project.title)
                is NetResult.Failure -> ChatMessage.MoveFailed(result.failure)
            }
            _state.update { it.copy(message = message) }
        }
    }

    // ---- Files on the host ---------------------------------------------------------------------

    /** Files already read, by path, so scrolling back doesn't read them again. */
    private val mediaCache = object : android.util.LruCache<String, MediaFile>(MEDIA_CACHE_BYTES) {
        override fun sizeOf(key: String, value: MediaFile) = value.bytes.size
    }

    /**
     * The file at [path] on the host; for a picture the user sent whose file Hermes has cleared, the
     * copy kept in its prompt row [rowId] (the [index]th picture there). Null when neither can be read.
     */
    suspend fun loadMedia(path: String, rowId: Long?, index: Int): MediaFile? {
        mediaCache.get(path)?.let { return it }
        val id = dashboardId ?: dashboards.activeDashboardId.first() ?: return null
        val file = when (val read = media.file(id, profile, path)) {
            is NetResult.Success -> read.value

            is NetResult.Failure -> {
                val session = storedId
                if (rowId == null || session == null) return null
                (media.promptImage(id, profile, session, rowId, index) as? NetResult.Success)?.value ?: return null
            }
        }
        mediaCache.put(path, file)
        return file
    }

    /** Opens a chat a reply links to. */
    fun openLinkedChat(linkProfile: String?, sessionId: String) {
        _events.trySend(ChatEvent.OpenChat(ChatRoute(linkProfile ?: profile, sessionId)))
    }

    // ---- Reading place -------------------------------------------------------------------------

    /** Where the chat is being read: the newest row's key while it follows the newest, else the row shown. */
    fun savePlace(rowKey: String?) {
        val key = memoryKey() ?: return
        memory.savePlace(key, rowKey)
    }

    fun placeRestored() = _state.update { it.copy(placeToRestore = null) }

    /** A suggested command, put in the composer to finish. */
    fun pickCommand(name: String) = updateDraft("/$name ")

    fun attach(uri: Uri) {
        _state.update { it.copy(attaching = true) }
        viewModelScope.launch {
            when (val result = attachmentReader.read(uri, _state.value.prefs.attachmentLimitBytes)) {
                is AttachmentReader.Result.Read -> {
                    val id = nextId++
                    picked[id] = result.file
                    _state.update { state ->
                        state.copy(
                            attaching = false,
                            attachments = state.attachments + ComposerAttachment(
                                id,
                                result.file.name,
                                result.file.isImage,
                                result.file.bytes.size,
                            ),
                        )
                    }
                }

                is AttachmentReader.Result.Failed -> _state.update {
                    it.copy(attaching = false, message = ChatMessage.AttachFailed(result.failure))
                }
            }
        }
    }

    fun removeAttachment(id: Long) {
        picked.remove(id)
        _state.update { state -> state.copy(attachments = state.attachments.filter { it.id != id }) }
    }

    /**
     * Sends what is in the composer. While a turn runs, a message with no files follows Hermes'
     * busy setting: it corrects the turn (the default), adds to it (steer), or waits for it to end
     * (queue). A message with files always waits, as Hermes would otherwise stop the turn for it.
     */
    fun send() {
        val current = _state.value
        if (!current.canSend) return
        val files = current.attachments.mapNotNull { picked[it.id] }
        val text = current.draft.trim()
        if (files.isEmpty() && commands.isCommand(text)) {
            // A command keeps the reply for the next message, as on iOS.
            _state.update { it.copy(draft = "", suggestions = emptyList()) }
            saveDraft()
            commands.run(text)
            return
        }
        val reply = current.replyTo
        val outgoingText = if (reply != null && text.isNotEmpty()) replyEnvelope(reply, text) else text
        picked.clear()
        _state.update { it.copy(draft = "", attachments = emptyList(), sent = it.sent + 1, replyTo = null) }
        saveDraft()
        val key = "out-${nextId++}"
        outgoing[key] = Outgoing(outgoingText, files, knownUsers())
        val message = ChatItem.User(
            key = key,
            text = outgoingText,
            attachments = files.map { ChatAttachment(it.name, path = "", isImage = it.isImage) },
            timestampMs = clock(),
            delivery = Delivery.Sending,
        )
        if (!attached || gateway.status.value.state !is GatewayState.Connected) {
            wait(key, message)
            return
        }
        if (chat.running && (files.isNotEmpty() || busyMode == BusyMode.Queue)) {
            chat = chat.withQueued(message.copy(delivery = Delivery.Queued))
            publish()
            return
        }
        chat = chat.withOutgoing(message)
        publish()
        viewModelScope.launch {
            // While "talking to Hermes" is shared, the message carries where the phone is (waiting a moment at most).
            location?.beforeSending()
            if (chat.running) correct(key) else submit(key)
        }
    }

    /** Sends a failed message again. */
    fun retrySend(key: String) {
        if (key !in outgoing) return
        chat = chat.withDelivery(key, Delivery.Sending)
        publish()
        viewModelScope.launch { submit(key) }
    }

    /** Takes a failed or waiting message back into the composer, to edit it or let it go. */
    fun takeBack(key: String) {
        val message = outgoing.remove(key) ?: return
        if (key == waitingKey) dropWaiting()
        forgetInFlight(key)
        chat = chat.withoutOutgoing(key).withoutQueued(key)
        val restored = message.files.map { file ->
            val id = nextId++
            picked[id] = file
            ComposerAttachment(id, file.name, file.isImage, file.bytes.size)
        }
        _state.update { state ->
            state.copy(
                draft = listOf(message.text, state.draft).filter(String::isNotBlank).joinToString("\n"),
                attachments = restored + state.attachments,
            )
        }
        publish()
    }

    // ---- Model and reasoning -------------------------------------------------------------------

    /** Opens the picker and loads the models this chat can switch to. */
    fun openModels() {
        val runtime = runtimeId ?: return
        _state.update { it.copy(picker = ModelPicker()) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val visibility = appSettings.modelVisibility(id).first()
            _state.update { it.copy(picker = it.picker?.copy(visibility = visibility)) }
        }
        viewModelScope.launch {
            val loaded = chats.modelChoices(runtime)
            _state.update { state ->
                val picker = state.picker ?: return@update state
                state.copy(
                    picker = when (loaded) {
                        is NetResult.Success -> picker.copy(choices = loaded.value)
                        is NetResult.Failure -> picker.copy(failure = loaded.failure)
                    },
                )
            }
        }
    }

    fun closeModels() = _state.update { it.copy(picker = null) }

    fun editModels(editing: Boolean) = _state.update { it.copy(picker = it.picker?.copy(editing = editing)) }

    fun showProvider(provider: String, show: Boolean) = changeVisibility {
        it.copy(hiddenProviders = if (show) it.hiddenProviders - provider else it.hiddenProviders + provider)
    }

    fun showModel(provider: String, model: String, show: Boolean) = changeVisibility {
        val key = ModelVisibility.modelKey(provider, model)
        it.copy(hiddenModels = if (show) it.hiddenModels - key else it.hiddenModels + key)
    }

    fun showAllModels() = changeVisibility { ModelVisibility() }

    private fun changeVisibility(change: (ModelVisibility) -> ModelVisibility) {
        val picker = _state.value.picker ?: return
        val next = change(picker.visibility)
        _state.update { it.copy(picker = it.picker?.copy(visibility = next)) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            appSettings.setModelVisibility(id, next)
        }
    }

    fun pickModel(provider: String, model: String) = switchModel(provider, model, confirmed = false)

    /** Yes to Hermes' question about a costly model. */
    fun confirmModel() {
        val confirm = _state.value.picker?.confirm ?: return
        switchModel(confirm.provider, confirm.model, confirmed = true)
    }

    fun cancelConfirm() = _state.update { it.copy(picker = it.picker?.copy(confirm = null)) }

    private fun switchModel(provider: String, model: String, confirmed: Boolean) {
        val runtime = runtimeId ?: return
        _state.update { it.copy(picker = it.picker?.copy(switching = true, confirm = null)) }
        viewModelScope.launch {
            when (val result = chats.switchModel(runtime, provider, model, confirmed)) {
                is NetResult.Success -> when (val switch = result.value) {
                    is ModelSwitch.ConfirmRequired -> _state.update {
                        it.copy(
                            picker = it.picker?.copy(
                                switching = false,
                                confirm = ModelPicker.Confirm(provider, model, switch.message),
                            ),
                        )
                    }

                    is ModelSwitch.Done -> {
                        // Hermes sends session.info too; until then the picked model shows.
                        val picked = ChatModel(switch.model, provider, chat.model?.reasoning.orEmpty())
                        chat = chat.copy(model = picked)
                        _state.update {
                            it.copy(
                                picker = null,
                                model = picked,
                                message = ChatMessage.ModelSwitched(switch.model, switch.deferred),
                            )
                        }
                    }
                }

                is NetResult.Failure -> _state.update {
                    it.copy(
                        picker = it.picker?.copy(switching = false),
                        message = ChatMessage.ModelFailed(result.failure),
                    )
                }
            }
        }
    }

    fun pickReasoning(level: String) {
        val runtime = runtimeId ?: return
        _state.update { it.copy(picker = it.picker?.copy(switching = true)) }
        viewModelScope.launch {
            when (val result = chats.setReasoning(runtime, level)) {
                is NetResult.Success -> {
                    chat = chat.copy(model = chat.model?.copy(reasoning = level))
                    _state.update { it.copy(picker = it.picker?.copy(switching = false), model = chat.model) }
                }

                is NetResult.Failure -> _state.update {
                    it.copy(
                        picker = it.picker?.copy(switching = false),
                        message = ChatMessage.ModelFailed(result.failure),
                    )
                }
            }
        }
    }

    // ---- Fast mode and approvals --------------------------------------------------------------

    fun setFast(on: Boolean) = changeSetting { chats.setFast(it, on) }

    /** Runs this chat's commands without asking; not while approvals are off for the whole profile. */
    fun setYolo(on: Boolean) {
        if (chat.settings?.yoloLocked == true) {
            _state.update { it.copy(message = ChatMessage.YoloLocked) }
            return
        }
        changeSetting { chats.setYolo(it, on) }
    }

    // Hermes answers with a session.info, which shows the change.
    private fun changeSetting(call: suspend (String) -> NetResult<Unit>) {
        val runtime = runtimeId ?: return _state.update { it.copy(message = ChatMessage.NotConnected) }
        viewModelScope.launch {
            val result = call(runtime)
            if (result is NetResult.Failure) {
                _state.update {
                    it.copy(message = ChatMessage.SettingFailed(result.failure))
                }
            }
        }
    }

    // ---- Sub-agents ----------------------------------------------------------------------------

    /** Opens the sub-agents sheet, with what Hermes still has for the chat. */
    fun openAgents() {
        _state.update { it.copy(agentsSheet = AgentsSheet()) }
        val runtime = runtimeId ?: return
        viewModelScope.launch {
            when (val listed = chats.subagents(runtime)) {
                is NetResult.Success -> {
                    chat = chat.withListedAgents(listed.value)
                    publish()
                }

                is NetResult.Failure -> _state.update {
                    it.copy(agentsSheet = it.agentsSheet?.copy(failure = listed.failure))
                }
            }
        }
    }

    fun closeAgents() = _state.update { it.copy(agentsSheet = null) }

    /** Shows [agentId]'s live output (null hides it); [followAgentOutput] keeps it fresh. */
    fun followAgent(agentId: String?) = _state.update {
        it.copy(agentsSheet = it.agentsSheet?.copy(following = agentId, output = null))
    }

    /** Reads the followed agent's output every few seconds while the screen shows it. */
    suspend fun followAgentOutput(agentId: String) {
        while (true) {
            val runtime = runtimeId
            val subagent = chat.agents.firstOrNull { it.id == agentId }?.subagentId
            if (runtime != null && subagent != null) {
                val output = chats.subagentOutput(runtime, subagent)
                if (output is NetResult.Success) {
                    _state.update { state ->
                        val sheet = state.agentsSheet?.takeIf { it.following == agentId } ?: return@update state
                        // A stopped or finished agent's transcript goes; what was shown stays.
                        val text = output.value?.takeIf(String::isNotBlank) ?: sheet.output ?: ""
                        state.copy(agentsSheet = sheet.copy(output = text))
                    }
                }
            }
            delay(AGENT_OUTPUT_EVERY_MS)
        }
    }

    fun askSteer(agent: DelegateAgent?) = _state.update {
        it.copy(agentsSheet = it.agentsSheet?.copy(steering = agent))
    }

    /** Sends the agent being steered a note; one that no longer takes notes has finished. */
    fun steerAgent(text: String) {
        val agent = _state.value.agentsSheet?.steering ?: return
        askSteer(null)
        val runtime = runtimeId ?: return
        val subagent = agent.subagentId ?: return
        viewModelScope.launch {
            when (val steered = chats.steerSubagent(runtime, subagent, text.trim())) {
                is NetResult.Success -> {
                    if (!steered.value) {
                        chat = chat.withAgent(agent.copy(status = AgentStatus.Completed))
                        publish()
                    }
                    _state.update { it.copy(message = ChatMessage.AgentSteered(steered.value)) }
                }

                is NetResult.Failure -> _state.update { it.copy(message = ChatMessage.AgentFailed(steered.failure)) }
            }
        }
    }

    fun stopAgent(agent: DelegateAgent) {
        val runtime = runtimeId ?: return
        val subagent = agent.subagentId ?: return
        viewModelScope.launch {
            when (val stopped = chats.stopSubagent(runtime, subagent)) {
                is NetResult.Success -> {
                    val status = if (stopped.value) AgentStatus.Interrupted else AgentStatus.Completed
                    chat = chat.withAgent(agent.copy(status = status))
                    publish()
                }

                is NetResult.Failure -> _state.update { it.copy(message = ChatMessage.AgentFailed(stopped.failure)) }
            }
        }
    }

    // ---- Context -------------------------------------------------------------------------------

    fun openContext() {
        _state.update { it.copy(contextSheet = ContextSheet()) }
        val runtime = runtimeId ?: return
        viewModelScope.launch {
            val loaded = chats.contextBreakdown(runtime)
            _state.update { state ->
                val sheet = state.contextSheet ?: return@update state
                state.copy(
                    contextSheet = when (loaded) {
                        is NetResult.Success -> sheet.copy(breakdown = loaded.value)
                        is NetResult.Failure -> sheet.copy(failure = loaded.failure)
                    },
                )
            }
        }
    }

    fun closeContext() = _state.update { it.copy(contextSheet = null) }

    /** Stops the running turn. */
    fun stop() {
        val runtime = runtimeId ?: return
        _state.update { it.copy(stopping = true) }
        viewModelScope.launch {
            val result = chats.stop(runtime)
            _state.update {
                it.copy(
                    stopping = false,
                    message = (result as? NetResult.Failure)?.let { f ->
                        ChatMessage.StopFailed(f.failure)
                    },
                )
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** A message typed while a turn runs, put into that turn. */
    private suspend fun correct(key: String) {
        val runtime = runtimeId ?: return fail(key)
        val message = outgoing[key] ?: return
        val steer = message.spoken || busyMode == BusyMode.Steer
        when (val result = chats.correct(runtime, withMentions(message.text), steer = steer)) {
            is NetResult.Success -> when (result.value) {
                Correction.Accepted, Correction.Queued -> delivered(key, rowId = null, intoTurn = true)

                // The turn ended meanwhile: an ordinary message.
                Correction.Idle -> submit(key)
            }

            is NetResult.Failure -> lost(key, result.failure)
        }
    }

    /** Uploads the message's files (each only once, if this is a retry) and sends it. */
    private suspend fun submit(key: String, queued: Boolean = false) {
        sendsOut++
        followWork()
        try {
            submitNow(key, queued)
        } finally {
            sendsOut--
            followWork()
        }
    }

    /**
     * Holds [WorkOnItsWay] while a send is out or a turn runs here, so leaving the app finishes
     * them out of sight ([lab.dawnforge.hermes.core.data.gateway.FinishWorkService]).
     */
    private fun followWork() {
        val busy = sendsOut > 0 || chat.running
        if (busy && workHold == null) {
            workHold = onItsWay?.hold()
        } else if (!busy) {
            workHold?.invoke()
            workHold = null
        }
    }

    private suspend fun submitNow(key: String, queued: Boolean) {
        val runtime = runtimeId ?: return fail(key)
        val message = outgoing[key] ?: return
        message.files.forEachIndexed { index, file ->
            if (file.isImage) {
                if (index in message.imagesSent) return@forEachIndexed
                if (chats.attachImage(runtime, file.bytes, file.name) !is NetResult.Success) return fail(key)
                message.imagesSent += index
            } else if (index !in message.references) {
                val reference = chats.attachFile(runtime, file.bytes, file.name, file.mimeType)
                if (reference !is NetResult.Success) return fail(key)
                message.references[index] = reference.value
            }
        }
        val references = message.files.indices.mapNotNull(message.references::get)
        val typed = message.text.ifEmpty { if (message.imagesSent.isNotEmpty()) IMAGE_ONLY_PROMPT else "" }
        val text = (references + listOfNotNull(typed.takeIf(String::isNotEmpty))).joinToString("\n\n")
        keepInFlight(key)
        liveTurns.submitting(listOfNotNull(runtime, storedId))
        when (val result = chats.send(runtime, withMentions(text), queued, voiceTurn = message.spoken)) {
            is NetResult.Success -> {
                val status = result.value.status
                delivered(
                    key,
                    result.value.userRowId,
                    intoTurn =
                        status == SendStatus.Steered || status == SendStatus.Redirected,
                )
                // A turn started for it: its live notification follows it while the app is away.
                if (status == SendStatus.Streaming) {
                    dashboardId?.let { id ->
                        liveTurns.started(id, profile, listOfNotNull(runtime, storedId), _state.value.title)
                    }
                }
            }

            is NetResult.Failure -> lost(key, result.failure)
        }
    }

    /**
     * A send that failed. If the connection dropped while it was out, Hermes may have it: it stays
     * "sending" until the reconnect says ([settleUnclear]). Otherwise it failed for sure.
     */
    private fun lost(key: String, failure: ConnectionFailure) {
        val message = outgoing[key] ?: return
        if (failure is ConnectionFailure.OwnedElsewhere) {
            fail(key)
            offerTakeover(key, failure.detail)
            return
        }
        val mayHaveArrived = failure is ConnectionFailure.TimedOut || failure is ConnectionFailure.NetworkError
        if (mayHaveArrived && attached) {
            message.unclear = true
            if (gateway.status.value.state is GatewayState.Connected) resync()
        } else {
            fail(key)
        }
    }

    private fun knownUsers(): Set<String> = chat.history.filterIsInstance<ChatItem.User>().mapTo(HashSet()) { it.key }

    private fun delivered(key: String, rowId: String?, intoTurn: Boolean) {
        everStored = true
        forgetInFlight(key)
        outgoing.remove(key)
        chat = chat.withDelivery(key, delivery = null, rowId = rowId, isSteer = intoTurn)
        publish()
    }

    private fun fail(key: String) {
        chat = chat.withDelivery(key, Delivery.Failed)
        publish()
    }

    /** The turn ended: the first message waiting for it goes now; the rest wait for the turn it starts. */
    private fun sendNextQueued() {
        val next = chat.queued.firstOrNull() ?: return
        chat = chat.withOutgoing(next.copy(delivery = Delivery.Sending))
        publish()
        viewModelScope.launch { submit(next.key, queued = true) }
    }

    // ---- Waiting for the connection ------------------------------------------------------------

    /**
     * A message typed while the app cannot reach Hermes. A chat holds one: a second one typed
     * meanwhile joins it. It goes once the chat is attached again, or, after [WAIT_LIMIT_MS] away,
     * back into the composer, as what it answered may have moved on.
     */
    private fun wait(key: String, message: ChatItem.User) {
        val previous = waitingKey?.let(outgoing::get)
        val typed = outgoing.getValue(key)
        val joined = if (previous == null) {
            typed
        } else {
            Outgoing(
                listOf(previous.text, typed.text).filter(String::isNotBlank).joinToString("\n\n"),
                previous.files + typed.files,
                previous.knownUsers,
            )
        }
        waitingKey?.let { old ->
            outgoing.remove(old)
            chat = chat.withoutQueued(old)
        }
        outgoing.remove(key)
        outgoing[key] = joined
        waitingKey = key
        waitingSince = if (previous == null) clock() else waitingSince
        chat = chat.withQueued(
            message.copy(
                text = joined.text,
                attachments = joined.files.map { ChatAttachment(it.name, path = "", isImage = it.isImage) },
                delivery = Delivery.Waiting,
            ),
        )
        publish()
        persistWaiting(joined.text)
        armWaitLimit()
    }

    /** The waiting message goes now that the chat is attached, unless it waited too long. */
    private fun sendWaiting() {
        val key = waitingKey ?: return
        val message = outgoing[key] ?: return dropWaiting()
        if (clock() - waitingSince > WAIT_LIMIT_MS) return returnWaiting()
        waitLimitJob?.cancel()
        dropWaiting()
        val bubble = chat.queued.firstOrNull { it.key == key } ?: return
        chat = chat.withoutQueued(key)
        val sending = bubble.copy(delivery = Delivery.Sending)
        if (chat.running && (message.files.isNotEmpty() || busyMode == BusyMode.Queue)) {
            chat = chat.withQueued(sending.copy(delivery = Delivery.Queued))
            publish()
            return
        }
        chat = chat.withOutgoing(sending)
        publish()
        viewModelScope.launch { if (chat.running) correct(key) else submit(key) }
    }

    private fun armWaitLimit() {
        waitLimitJob?.cancel()
        waitLimitJob = viewModelScope.launch {
            delay((waitingSince + WAIT_LIMIT_MS - clock()).coerceAtLeast(0))
            if (waitingKey != null) returnWaiting()
        }
    }

    private fun returnWaiting() {
        val key = waitingKey ?: return
        takeBack(key)
        _state.update { it.copy(message = ChatMessage.WaitingReturned) }
    }

    /** The waiting message left the queue (sent, taken back, or returned). */
    private fun dropWaiting() {
        waitingKey = null
        waitLimitJob?.cancel()
        val id = dashboardId ?: return
        val chatKey = chatKey() ?: return
        keep { cache.clearWaiting(id, profile, chatKey) }
    }

    private fun persistWaiting(text: String) {
        val id = dashboardId ?: return
        val chatKey = chatKey() ?: return
        val waiting = Waiting(text, waitingSince, newChatRef())
        keep { cache.saveWaiting(id, profile, chatKey, waiting) }
    }

    /**
     * A message going out in a chat Hermes hasn't stored: kept on the phone until Hermes has it, so
     * leaving the chat, or the app being closed, doesn't lose it. The chat list shows the chat
     * meanwhile; opened again, the message is settled against Hermes like any whose reply was lost,
     * never sent again blindly. Only its words are kept, as for a waiting message.
     */
    private fun keepInFlight(key: String) {
        val newChat = newChatRef() ?: return
        // The chat keeps one message: a waiting one stays.
        if (waitingKey != null) return
        val text = outgoing[key]?.text?.takeIf(String::isNotBlank) ?: return
        val id = dashboardId ?: return
        val chatKey = chatKey() ?: return
        inFlightKey = key
        val waiting = Waiting(text, clock(), newChat, unclear = true)
        keep { cache.saveWaiting(id, profile, chatKey, waiting) }
    }

    /** Hermes has the message kept by [keepInFlight], or it went back into the composer. */
    private fun forgetInFlight(key: String) {
        if (key != inFlightKey) return
        inFlightKey = null
        val id = dashboardId ?: return
        val chatKey = chatKey() ?: return
        keep { cache.clearWaiting(id, profile, chatKey) }
    }

    /** What the chat list needs to show this chat, and to start it again, while Hermes hasn't stored it. */
    private fun newChatRef(): NewChatRef? = if (startedHere && !hasStoredMessages()) {
        NewChatRef(reservedId = storedId ?: route.reservedId, cwd = chat.cwd ?: route.newChatCwd)
    } else {
        null
    }

    /**
     * Writes the chat's kept message after the write before it. Once started, a write finishes
     * even if the chat is closed meanwhile: the message must not be lost with the screen.
     */
    private fun keep(write: suspend () -> Unit) {
        val previous = keeping
        keeping = viewModelScope.launch {
            withContext(NonCancellable) {
                previous?.join()
                write()
            }
        }
    }

    /** A message left waiting when the app was closed: back as it was, or into the composer if it is too old. */
    private suspend fun restoreWaiting() {
        val id = dashboardId ?: return
        val chatKey = chatKey() ?: return
        val saved = cache.waiting(id, profile, chatKey) ?: return
        if (saved.unclear) {
            // It went out, and its reply was lost when the chat was left: Hermes says, on attaching.
            val key = "out-${nextId++}"
            outgoing[key] = Outgoing(saved.text, emptyList(), knownUsers()).apply { unclear = true }
            inFlightKey = key
            chat = chat.withOutgoing(
                ChatItem.User(key, saved.text, timestampMs = saved.typedAtMs, delivery = Delivery.Sending),
            )
            publish()
            return
        }
        if (clock() - saved.typedAtMs > WAIT_LIMIT_MS) {
            cache.clearWaiting(id, profile, chatKey)
            _state.update { state ->
                state.copy(
                    draft = listOf(saved.text, state.draft).filter(String::isNotBlank).joinToString("\n"),
                    message = ChatMessage.WaitingReturned,
                )
            }
            return
        }
        val key = "out-${nextId++}"
        outgoing[key] = Outgoing(saved.text, emptyList(), knownUsers())
        waitingKey = key
        waitingSince = saved.typedAtMs
        chat = chat.withQueued(
            ChatItem.User(key, saved.text, timestampMs = saved.typedAtMs, delivery = Delivery.Waiting),
        )
        publish()
        armWaitLimit()
    }

    /** A waiting message belongs to the chat by its stored id, or its live id until it has one. */
    private fun chatKey(): String? = route.sessionId ?: route.runtimeId

    // ---- Saved copy ----------------------------------------------------------------------------

    /** The chat as last loaded, shown while the newest is asked for: it opens at once, and with no network. */
    private suspend fun showSaved() {
        val id = dashboardId ?: return
        val session = storedId ?: return
        val saved = cache.transcript(id, profile, session) ?: return
        if (rows.isNotEmpty()) return
        rows = saved.rows
        applyRows(chat.turnsEnded)
        _state.update { it.copy(loading = false, hasOlder = saved.hasOlder) }
    }

    private suspend fun saveCopy(resolvedId: String) {
        val id = dashboardId ?: return
        val session = route.sessionId ?: storedId ?: return
        cache.saveTranscript(id, profile, session, resolvedId, rows, _state.value.hasOlder)
    }

    // ---- Requests ------------------------------------------------------------------------------

    private fun onRequests(pending: Map<String, ServerRequest>) {
        this.pending = pending
        showRequests()
    }

    /** This chat's open requests, from [pending]: an approval or a question waiting on the user. */
    private fun showRequests() {
        val mine = pending.values.filter { request ->
            val session = request.sessionId
            session != null && (session == runtimeId || session == storedId)
        }
        val shown = mine.mapNotNull { request ->
            ChatRequest.from(request) ?: run {
                // Not one this app shows (a password vault, a desktop window): leave it to another client.
                chats.decline(request)
                null
            }
        }
        _state.update { it.copy(requests = shown) }
    }

    fun approve(request: ChatRequest.Approval, choice: ApprovalChoice) = answer(request, request.answer(choice))

    fun answerQuestions(request: ChatRequest.Clarify, answers: Map<String, List<String>>) =
        answer(request, request.answer(answers))

    fun skipQuestions(request: ChatRequest.Clarify) = answer(request, request.cancel())

    /** A password or a secret; empty declines it. */
    fun answerValue(request: ChatRequest, value: String) = answer(request, ChatRequest.value(value))

    private fun answer(request: ChatRequest, result: JsonObject) {
        if (!chats.answer(request.request, result)) _state.update { it.copy(message = ChatMessage.AnswerNotSent) }
    }

    // ---- History -------------------------------------------------------------------------------

    fun retry() {
        _state.update { it.copy(loading = true, error = null) }
        loadNewest()
    }

    private fun loadNewest() {
        newestJob?.cancel()
        newestJob = viewModelScope.launch { refreshNewest() }
    }

    /** The newest page, merged over what is loaded: older pages stay. */
    private suspend fun refreshNewest() {
        val id = dashboardId ?: return
        val session = storedId ?: return
        run {
            val endedBefore = chat.turnsEnded
            val firstLoad = rows.isEmpty()
            when (val result = chats.history(id, profile, session)) {
                is NetResult.Success -> {
                    val page = result.value
                    val merged = mergeNewest(rows, page.rows)
                    // A page that does not reach what was loaded leaves a gap: start the paging again from it.
                    val restarted = merged === page.rows && rows.isNotEmpty()
                    rows = merged
                    applyRows(endedBefore)
                    _state.update {
                        it.copy(
                            loading = false,
                            error = null,
                            hasOlder = if (restarted || firstLoad) page.hasMore else it.hasOlder,
                            savedCopy = false,
                        )
                    }
                    saveCopy(page.sessionId)
                    reveal()
                }

                is NetResult.Failure -> {
                    // A new chat is not stored until its first message.
                    val missing = result.failure is ConnectionFailure.NotFound && rows.isEmpty()
                    _state.update {
                        it.copy(
                            loading = false,
                            error = if (missing || rows.isNotEmpty()) null else result.failure,
                            savedCopy = rows.isNotEmpty() && !missing,
                        )
                    }
                }
            }
        }
    }

    /** Loads the page before the oldest row loaded. */
    fun loadOlder() {
        val id = dashboardId ?: return
        val session = storedId ?: return
        val current = _state.value
        if (!current.hasOlder || current.loadingOlder || olderJob?.isActive == true) return
        _state.update { it.copy(loadingOlder = true, olderError = null) }
        olderJob = viewModelScope.launch {
            when (val result = chats.history(id, profile, session, offset = rows.size)) {
                is NetResult.Success -> {
                    val page: HistoryPage = result.value
                    // Rows stored since the newest page shift the offsets; drop any overlap.
                    val known = rows.mapNotNullTo(HashSet()) { it.rowId() }
                    rows = page.rows.filter { it.rowId() == null || it.rowId() !in known } + rows
                    applyRows(chat.turnsEnded)
                    _state.update { it.copy(loadingOlder = false, hasOlder = page.hasMore) }
                    reveal()
                }

                is NetResult.Failure -> _state.update { it.copy(loadingOlder = false, olderError = result.failure) }
            }
        }
    }

    private suspend fun applyRows(loadedAtTurnsEnded: Int) {
        val snapshot = rows
        val running = chat.running
        val items = withContext(work) { normalizeHistory(snapshot, running) }
        chat = chat.withHistory(items, loadedAtTurnsEnded)
        publish()
    }

    private fun publish() {
        val current = chat
        followWork()
        turnsEnded.value = current.turnsEnded
        _state.update {
            it.copy(
                // A bot's chat is titled with the bot, never its wire title "Bot Chat".
                title = route.title?.takeIf { route.botChat } ?: current.title ?: it.title,
                items = current.items,
                running = current.running,
                status = current.status,
                context = current.context,
                model = current.model ?: it.model,
                agents = current.agents,
                settings = current.settings ?: it.settings,
                cwd = current.cwd ?: it.cwd,
            )
        }
    }

    /**
     * "Show in chat": once the stored row [ChatRoute.revealRowId] is loaded, scrolls to it, or to
     * the last row before it (a tool call has no row of its own), paging back for it a while.
     */
    private fun reveal() {
        val target = revealing ?: return
        val hit = _state.value.items
            .mapNotNull { item -> item.storedRow()?.takeIf { it <= target }?.let { it to item } }
            .maxByOrNull { it.first }
            ?.second
        when {
            hit != null -> {
                revealing = null
                _state.update { it.copy(placeToRestore = hit.key) }
            }

            _state.value.hasOlder && revealPages < MAX_REVEAL_PAGES -> {
                revealPages++
                loadOlder()
            }

            else -> revealing = null
        }
    }

    private fun ChatItem.storedRow(): Long? = when (this) {
        is ChatItem.User -> rowId
        is ChatItem.Assistant -> rowId
        else -> null
    }

    /** [words] after what the draft held, a space between them. */
    private fun dictated(before: String, words: String): String = when {
        words.isBlank() -> before
        before.isBlank() -> words.trim()
        else -> before.trimEnd() + " " + words.trim()
    }

    private companion object {
        const val RELOAD_DEBOUNCE_MS = 300L

        /** Taking a chat over asks again this often while the other app replies, for this long, as iOS. */
        const val TAKEOVER_POLL_MS = 3_000L
        const val TAKEOVER_WAIT_MS = 15 * 60_000L
        const val TAKEOVER_TRANSIENT_TRIES = 5

        /** The confirmation tool, and how long after it starts the prompt is surely stored. */
        const val CONFIRM_TOOL = "talaria_present"

        /** Hermes' tool for a voice conversation's screen. */
        const val SHOW_TOOL = "talaria_show"

        /** Hermes' tool for this phone's settings. */
        const val SETTINGS_TOOL = "talaria_settings"

        /** Hermes' maps tool, whose navigate asks this phone to open Maps. */
        const val MAPS_TOOL = "talaria_maps"

        /** The most of a reply a call from Hermes reads out, as iOS. */
        const val MAX_CALL_RESULT = 4_000
        const val MAX_MENTIONS = 5
        const val PARAGRAPH = "\n\n"

        /** How long a turn the user spoke over gets to stop before their words go as a new one. */
        const val INTERRUPT_WAIT_MS = 5_000L
        const val INTERRUPT_POLL_MS = 100L
        const val CONFIRM_STORED_MS = 1_000L
        const val HTTP_TIMEOUT = 408
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val HTTP_NOT_IMPLEMENTED = 501
        const val HTTP_SERVER_ERROR = 500

        /** "Show in chat" pages back this far for the message before giving up. */
        const val MAX_REVEAL_PAGES = 20

        /** Haptics a briefly slow screen may still play; more are dropped. */
        const val HAPTIC_BUFFER = 8

        /** Files read from the host kept in memory: a few pictures and a voice note or two. */
        const val MEDIA_CACHE_BYTES = 48 * 1024 * 1024

        /** How often a sub-agent's live output is read while shown. */
        const val AGENT_OUTPUT_EVERY_MS = 2_000L

        /** How long a message typed offline waits for the connection before it goes back to the composer. */
        const val WAIT_LIMIT_MS = 10 * 60 * 1000L

        /** What Hermes' desktop app sends with images and no text. */
        const val IMAGE_ONLY_PROMPT = "What do you see in this image?"

        fun JsonObject.rowId(): String? = (this["id"] as? JsonPrimitive)?.contentOrNull

        /**
         * [newest] laid over [loaded]: everything before the first row they share, then [newest]. A
         * page sharing no row with what is loaded replaces it (more rows arrived than a page holds).
         */
        fun mergeNewest(loaded: List<JsonObject>, newest: List<JsonObject>): List<JsonObject> {
            if (loaded.isEmpty()) return newest
            val first = newest.firstOrNull()?.rowId() ?: return newest
            val at = loaded.indexOfFirst { it.rowId() == first }
            return if (at < 0) newest else loaded.subList(0, at) + newest
        }
    }
}
