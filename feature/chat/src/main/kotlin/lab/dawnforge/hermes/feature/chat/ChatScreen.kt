package lab.dawnforge.hermes.feature.chat

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import lab.dawnforge.hermes.core.chat.HapticEffect
import lab.dawnforge.hermes.core.chat.SettingsLink
import lab.dawnforge.hermes.core.chat.replyAttachments
import lab.dawnforge.hermes.core.data.chat.PickFailure
import lab.dawnforge.hermes.core.data.session.Project
import lab.dawnforge.hermes.core.gateway.GatewayState
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.ui.LiveConnectionLine
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.core.ui.profileGlowBrush
import lab.dawnforge.hermes.core.voice.DictationProblem
import lab.dawnforge.hermes.core.voice.ReadAloudState
import lab.dawnforge.hermes.feature.chat.navigation.ChatRoute

@Composable
internal fun ChatScreen(
    onBack: () -> Unit,
    onOpenChat: (ChatRoute) -> Unit,
    onOpenWorkspace: (root: String) -> Unit,
    onOpenArtifacts: (sessionIds: List<String>, title: String?) -> Unit,
    onRepairConnection: (() -> Unit)? = null,
    onOpenSettings: (SettingsLink) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val reading by viewModel.reading.collectAsStateWithLifecycle()
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    val voiceCards by viewModel.voiceCards.collectAsStateWithLifecycle()
    val openChat by rememberUpdatedState(onOpenChat)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event -> if (event is ChatEvent.OpenChat) openChat(event.route) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.keepRead() }
    }
    val view = LocalView.current
    val hapticsOn by rememberUpdatedState(state.prefs.haptics)
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.hapticEffects.collect { if (hapticsOn) view.performHapticFeedback(it.feedback()) }
        }
    }
    val actions = remember(viewModel) {
        ChatActions(
            onRetry = viewModel::retry,
            onLoadOlder = viewModel::loadOlder,
            onMessageShown = viewModel::messageShown,
            composer = ComposerActions(
                onDraftChange = viewModel::updateDraft,
                onAttach = viewModel::attach,
                onRemoveAttachment = viewModel::removeAttachment,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onPickCommand = viewModel::pickCommand,
                onPickMention = viewModel::pickMention,
                onCancelReply = viewModel::cancelReply,
                onOpenWorkspace = { viewModel.state.value.cwd?.let(onOpenWorkspace) },
                onDictate = viewModel::toggleDictation,
                onVoice = { viewModel.openVoice() },
            ),
            requests = RequestActions(
                onApprove = viewModel::approve,
                onAnswerQuestions = viewModel::answerQuestions,
                onSkipQuestions = viewModel::skipQuestions,
                onAnswerValue = viewModel::answerValue,
            ),
            rows = RowActions(
                onRetry = viewModel::retrySend,
                onTakeBack = viewModel::takeBack,
                canReact = viewModel::canReact,
                onReact = viewModel::react,
                onReply = viewModel::replyTo,
                canBranch = { it.rowId != null && !viewModel.state.value.running },
                onBranch = viewModel::branchFrom,
                onCopied = viewModel::copied,
                reading = { key -> reading?.takeIf { it.key == key }?.phase },
                onReadAloud = viewModel::readAloud,
            ),
            onSavePlace = viewModel::savePlace,
            onPlaceRestored = viewModel::placeRestored,
            onRepairConnection = onRepairConnection,
            onReconnect = viewModel::reconnectAnyway,
            models = ModelActions(
                onOpen = viewModel::openModels,
                onClose = viewModel::closeModels,
                onPickModel = viewModel::pickModel,
                onConfirm = viewModel::confirmModel,
                onCancelConfirm = viewModel::cancelConfirm,
                onPickReasoning = viewModel::pickReasoning,
                onFast = viewModel::setFast,
                onYolo = viewModel::setYolo,
                onEdit = viewModel::editModels,
                onShowProvider = viewModel::showProvider,
                onShowModel = viewModel::showModel,
                onShowAll = viewModel::showAllModels,
            ),
            agents = AgentActions(
                onOpen = viewModel::openAgents,
                onClose = viewModel::closeAgents,
                onFollow = viewModel::followAgent,
                followOutput = viewModel::followAgentOutput,
                onAskSteer = viewModel::askSteer,
                onSteer = viewModel::steerAgent,
                onStop = viewModel::stopAgent,
            ),
            onOpenContext = viewModel::openContext,
            onCloseContext = viewModel::closeContext,
            onOpenArtifacts = {
                val state = viewModel.state.value
                state.storedId?.let { onOpenArtifacts(listOf(it), state.title) }
            },
            onOpenMove = viewModel::openMove,
            onTakeOver = viewModel::takeOver,
            onDismissTakeover = viewModel::dismissTakeover,
            onCloseMove = viewModel::closeMove,
            onMove = viewModel::move,
        )
    }
    val openSettings by rememberUpdatedState(onOpenSettings)
    val media = remember(viewModel) {
        MediaActions(load = viewModel::loadMedia, openChat = viewModel::openLinkedChat, openSettings = {
            openSettings(it)
        })
    }
    CompositionLocalProvider(LocalMediaActions provides media) {
        voice?.let { conversation ->
            VoiceSheet(
                conversation,
                voiceCards,
                VoiceActions(
                    onListen = viewModel::resumeVoice,
                    onPause = viewModel::pauseVoice,
                    onInterrupt = viewModel::interruptVoice,
                    onMute = viewModel::setVoiceMuted,
                    onEnd = viewModel::endVoice,
                    onProblemShown = viewModel::voiceProblemShown,
                ),
            )
        }
        ChatScreen(
            state = state,
            onBack = onBack,
            actions = actions,
            reading = reading,
            reader = ReaderActions(
                onPause = viewModel::pauseReading,
                onResume = viewModel::resumeReading,
                onSpeed = viewModel::setReadingSpeed,
                onStop = viewModel::stopReading,
            ),
        )
    }
}

/** Everything the chat screen can ask of its view model. */
internal class ChatActions(
    val onRetry: () -> Unit,
    val onLoadOlder: () -> Unit,
    val onMessageShown: () -> Unit,
    val composer: ComposerActions,
    val requests: RequestActions,
    val rows: RowActions,
    val models: ModelActions,
    val agents: AgentActions,
    val onOpenContext: () -> Unit,
    val onCloseContext: () -> Unit,
    /** This chat's artifacts; only a stored chat has them. */
    val onOpenArtifacts: () -> Unit,
    val onOpenMove: () -> Unit,
    val onTakeOver: () -> Unit,
    val onDismissTakeover: () -> Unit,
    val onCloseMove: () -> Unit,
    val onMove: (Project) -> Unit,
    /** Where the chat is being read (null: at its newest), kept for when it is opened again. */
    val onSavePlace: (String?) -> Unit,
    val onPlaceRestored: () -> Unit,
    /** Repair connection, for the live connection that failed; null where it isn't offered. */
    val onRepairConnection: (() -> Unit)? = null,
    val onReconnect: () -> Unit = {},
)

/** A chat's transcript, newest at the bottom, with the running turn streaming in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    actions: ChatActions,
    reading: ReadAloudState? = null,
    reader: ReaderActions? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    val note = state.message?.let { chatMessageText(it) }
    LaunchedEffect(state.message) {
        if (note != null) {
            snackbar.showSnackbar(note)
            actions.onMessageShown()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                if (state.requests.isNotEmpty()) {
                    // Hermes waits on these, so they stay in view above the composer.
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .heightIn(max = REQUESTS_MAX_HEIGHT)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        state.requests.forEach { request -> key(request.id) { RequestCard(request, actions.requests) } }
                    }
                }
                if (state.agents.isNotEmpty()) {
                    AssistChip(
                        onClick = actions.agents.onOpen,
                        label = {
                            Text(
                                if (state.agents.any { it.isActive }) {
                                    pluralStringResource(
                                        R.plurals.feature_chat_agents_working,
                                        state.agents.count { it.isActive },
                                        state.agents.count { it.isActive },
                                    )
                                } else {
                                    stringResource(R.string.feature_chat_agents_title)
                                },
                            )
                        },
                        leadingIcon = {
                            if (state.agents.any { it.isActive }) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                state.takeover?.let { TakeoverNotice(it, actions.onTakeOver, actions.onDismissTakeover) }
                if (reading != null && reader != null) ReadAloudBar(reading, reader)
                ChatComposer(state, actions.composer)
            }
        },
        topBar = {
            TopAppBar(
                modifier = Modifier.background(profileGlowBrush(state.glow)),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    // The model line opens the model and reasoning picker.
                    val model = state.model
                    Column(if (model != null) Modifier.clickable(onClick = actions.models.onOpen) else Modifier) {
                        Text(
                            state.title ?: stringResource(R.string.feature_chat_new_chat),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val details = listOfNotNull(
                            model?.let { modelLabel(it) },
                            state.context?.let {
                                stringResource(R.string.feature_chat_context, it.percent.roundToInt())
                            },
                        )
                        if (details.isNotEmpty()) {
                            Text(
                                details.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.feature_chat_back),
                        )
                    }
                },
                actions = { ChatMenu(state, actions) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.live !is GatewayState.Connected || state.liveHeld != null) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    LiveConnectionLine(
                        state.live,
                        held = state.liveHeld,
                        onRepair = actions.onRepairConnection,
                        onReconnect = actions.onReconnect,
                    )
                }
            }
            if (state.savedCopy) {
                Text(
                    stringResource(R.string.feature_chat_saved_copy),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            when {
                state.loading && state.items.isEmpty() -> Centered { CircularProgressIndicator() }

                state.error != null && state.items.isEmpty() -> Centered {
                    Text(failureText(state.error), textAlign = TextAlign.Center)
                    TextButton(onClick = actions.onRetry) { Text(stringResource(R.string.feature_chat_retry)) }
                }

                state.items.isEmpty() && !state.running -> Centered {
                    Text(
                        stringResource(R.string.feature_chat_empty),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, density.fontScale * state.prefs.textScale),
                    ) { Transcript(state, actions) }
                }
            }
        }
    }
    state.picker?.let { ModelSheet(it, state.model, state.settings, actions.models) }
    state.agentsSheet?.let { AgentsSheet(it, state.agents, actions.agents) }
    state.contextSheet?.let { ContextSheet(it, actions.onCloseContext) }
    state.moveTargets?.let { targets -> MoveDialog(targets, actions.onMove, actions.onCloseMove) }
}

/** The projects this chat can move to; it works in the chosen one's folder from then on. */
@Composable
private fun MoveDialog(targets: List<Project>, onMove: (Project) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feature_chat_move)) },
        text = {
            if (targets.isEmpty()) {
                Text(
                    stringResource(R.string.feature_chat_move_none),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    items(targets, key = { it.id }) { project ->
                        ListItem(
                            modifier = Modifier.clickable { onMove(project) },
                            headlineContent = { Text(project.title) },
                            supportingContent = project.primaryPath?.let { path ->
                                {
                                    Text(
                                        path,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_chat_model_cancel)) }
        },
    )
}

/** The chat's menu: its context usage and its sub-agents. */
@Composable
private fun ChatMenu(state: ChatUiState, actions: ChatActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.feature_chat_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_chat_context_title)) },
                onClick = {
                    open = false
                    actions.onOpenContext()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_chat_agents_title)) },
                onClick = {
                    open = false
                    actions.agents.onOpen()
                },
            )
            if (state.model != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.feature_chat_model_title)) },
                    onClick = {
                        open = false
                        actions.models.onOpen()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_chat_artifacts)) },
                enabled = state.storedId != null,
                onClick = {
                    open = false
                    actions.onOpenArtifacts()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_chat_move)) },
                enabled = state.storedId != null,
                onClick = {
                    open = false
                    actions.onOpenMove()
                },
            )
        }
    }
}

@Composable
private fun Transcript(state: ChatUiState, actions: ChatActions) {
    val listState = rememberLazyListState()
    val newestFirst = state.items.asReversed()
    // A live card is drawn where its name was last shown; earlier ones say it was updated below.
    val liveOwners = remember(state.items) { liveOwners(state.items) }
    // A turn's voice messages come with its reply, as Hermes sends them on Telegram.
    val attachments = remember(state.items) { replyAttachments(state.items) }
    // The list is laid out from the bottom: index 0 is the newest row, so a growing reply stays in
    // view. A row added below it would not be (the list holds on to the row it shows), so while the
    // reader is at the newest end, new rows are scrolled to; once they scroll up to read, they stay.
    var following by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.filter { !it }.collect {
            following = listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset < FOLLOW_SLOP_PX
        }
    }
    LaunchedEffect(state.items.lastOrNull()?.key, state.items.size, state.running) {
        if (following) listState.scrollToItem(0)
    }
    // Back where the chat was last read, once that row is loaded; a row in a page not loaded yet
    // gives way to the newest end.
    LaunchedEffect(state.placeToRestore, newestFirst.size, state.loading) {
        val place = state.placeToRestore ?: return@LaunchedEffect
        val index = newestFirst.indexOfFirst { it.key == place }
        if (index >= 0) {
            following = false
            listState.scrollToItem(index + if (state.running) 1 else 0)
            actions.onPlaceRestored()
        } else if (!state.loading && newestFirst.isNotEmpty()) {
            actions.onPlaceRestored()
        }
    }
    // Leaving the chat keeps where it was read: the newest row shown, unless it was at the newest end.
    val currentFollowing by rememberUpdatedState(following)
    DisposableEffect(listState) {
        onDispose {
            val shown = listState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { info ->
                (info.key as? String)?.takeIf { key -> key != "status" && key != "older" }
            }
            actions.onSavePlace(if (currentFollowing) null else shown)
        }
    }
    // Whoever sends a message wants to see the reply, wherever they had scrolled to.
    LaunchedEffect(state.sent) {
        if (state.sent > 0) {
            following = true
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_OLDER_AHEAD
        }.distinctUntilChanged().filter { it }.collect { actions.onLoadOlder() }
    }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.running) {
            item(key = "status") { WorkingLine(state.status) }
        }
        items(newestFirst, key = { it.key }, contentType = { it::class }) { item ->
            // Fade rows in and out, but do not animate their moves: a row that grows as it streams would
            // be drawn over its neighbours mid-move.
            ChatRow(
                item,
                actions.rows,
                Modifier.animateItem(placementSpec = null),
                supersededIn(item, liveOwners),
                attachments[item.key].orEmpty(),
            )
        }
        when {
            state.loadingOlder -> item(key = "older") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }

            state.olderError != null -> item(key = "older") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        failureText(state.olderError),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(onClick = actions.onLoadOlder) { Text(stringResource(R.string.feature_chat_retry)) }
                }
            }
        }
    }
}

@Composable
private fun WorkingLine(status: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            status ?: stringResource(R.string.feature_chat_working),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun chatMessageText(message: ChatMessage): String = when (message) {
    is ChatMessage.AttachFailed -> when (val failure = message.failure) {
        is PickFailure.TooLarge -> stringResource(
            R.string.feature_chat_attach_too_large,
            failure.limitBytes / (1024 * 1024),
        )

        PickFailure.Unreadable -> stringResource(R.string.feature_chat_attach_unreadable)
    }

    is ChatMessage.StopFailed -> stringResource(R.string.feature_chat_stop_failed, failureText(message.failure))

    is ChatMessage.DictationFailed -> when (val problem = message.problem) {
        DictationProblem.NoPermission -> stringResource(R.string.feature_chat_dictate_no_permission)

        DictationProblem.MicBusy -> stringResource(R.string.feature_chat_dictate_mic_busy)

        is DictationProblem.Phone -> stringResource(R.string.feature_chat_dictate_phone_failed)

        is DictationProblem.Hermes -> stringResource(
            R.string.feature_chat_dictate_hermes_failed,
            failureText(problem.failure),
        )

        DictationProblem.TimedOut -> stringResource(R.string.feature_chat_dictate_timed_out)
    }

    is ChatMessage.Moved -> stringResource(R.string.feature_chat_moved, message.project)

    is ChatMessage.MoveFailed -> stringResource(R.string.feature_chat_move_failed, failureText(message.failure))

    ChatMessage.AnswerNotSent -> stringResource(R.string.feature_chat_answer_not_sent)

    ChatMessage.WaitingReturned -> stringResource(R.string.feature_chat_waiting_returned)

    ChatMessage.StartedAgain -> stringResource(R.string.feature_chat_started_again)

    is ChatMessage.ModelSwitched -> if (message.nextTurn) {
        stringResource(R.string.feature_chat_model_switched_next, message.model)
    } else {
        stringResource(R.string.feature_chat_model_switched, message.model)
    }

    is ChatMessage.ModelFailed -> stringResource(R.string.feature_chat_model_failed, failureText(message.failure))

    ChatMessage.NotConnected -> stringResource(R.string.feature_chat_not_connected)

    is ChatMessage.SideAgentStarted -> stringResource(
        if (message.background) R.string.feature_chat_background_started else R.string.feature_chat_btw_started,
    )

    ChatMessage.Compressed -> stringResource(R.string.feature_chat_compressed)

    ChatMessage.BotChatNeverResets -> stringResource(R.string.feature_chat_bot_never_resets)

    ChatMessage.SharedReady -> stringResource(R.string.feature_chat_shared_ready)

    ChatMessage.NothingToCompress -> stringResource(R.string.feature_chat_nothing_to_compress)

    ChatMessage.CompressPending -> stringResource(R.string.feature_chat_compress_pending)

    is ChatMessage.CommandFailed ->
        stringResource(R.string.feature_chat_command_failed, message.command, failureText(message.failure))

    is ChatMessage.SettingFailed -> stringResource(R.string.feature_chat_setting_failed, failureText(message.failure))

    ChatMessage.YoloLocked -> stringResource(R.string.feature_chat_yolo_locked)

    is ChatMessage.AgentSteered -> stringResource(
        if (message.queued) R.string.feature_chat_agents_steered else R.string.feature_chat_agents_steer_rejected,
    )

    is ChatMessage.AgentFailed -> stringResource(
        R.string.feature_chat_agents_failed_action,
        failureText(message.failure),
    )

    is ChatMessage.ReactFailed -> stringResource(R.string.feature_chat_react_failed, failureText(message.failure))

    is ChatMessage.BranchFailed -> message.failure?.let {
        stringResource(R.string.feature_chat_branch_failed, failureText(it))
    } ?: stringResource(R.string.feature_chat_branch_failed_unknown)

    ChatMessage.Copied -> stringResource(R.string.feature_chat_copied)
}

/** The request cards take at most this much of the screen; more scroll. */
private val REQUESTS_MAX_HEIGHT = 360.dp

/** How far up the newest row the reader may be and still count as following the chat. */
private const val FOLLOW_SLOP_PX = 48

/** How close to the oldest loaded row the next older page is asked for. */
private const val LOAD_OLDER_AHEAD = 8

/** The system haptic closest to each of the iOS app's; the system's own haptics setting still applies. */
private fun HapticEffect.feedback(): Int = when (this) {
    HapticEffect.ResponseStarted -> HapticFeedbackConstants.CONTEXT_CLICK

    HapticEffect.ToolStarted -> HapticFeedbackConstants.CLOCK_TICK

    HapticEffect.ResponseConcluded -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.VIRTUAL_KEY
    }

    HapticEffect.Error -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.REJECT
    } else {
        HapticFeedbackConstants.LONG_PRESS
    }
}

/** Each live card name, and the key of the last row that shows it. */
internal fun liveOwners(items: List<ChatItem>): Map<String, String> = buildMap {
    items.forEach { item ->
        if (item is ChatItem.Tool) item.cards.forEach { card -> card.live?.let { put(it, item.key) } }
    }
}

/** The live names on [item] that a later row shows instead. */
internal fun supersededIn(item: ChatItem, owners: Map<String, String>): Set<String> {
    if (item !is ChatItem.Tool || item.cards.isEmpty()) return emptySet()
    return item.cards.mapNotNullTo(HashSet()) { card -> card.live?.takeIf { owners[it] != item.key } }
}
