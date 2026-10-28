package lab.dawnforge.hermes.feature.bots.groups

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import lab.dawnforge.hermes.core.data.chat.PickFailure
import lab.dawnforge.hermes.core.data.groups.GroupApproval
import lab.dawnforge.hermes.core.data.groups.GroupRoomTurns
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.feature.bots.R

/**
 * One hosted group chat, as iOS's room: the members' messages, who is responding, approvals the
 * room holds, and a composer with @mentions and files. The room keeps running on Hermes after the
 * screen closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupRoomScreen(viewModel: GroupRoomViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pictures by viewModel.pictures.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.follow() } }
    LaunchedEffect(state.gone) { if (state.gone) onBack() }

    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.let { roomMessageText(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.messageShown()
        }
    }
    var disbanding by remember { mutableStateOf(false) }
    val choosePicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::setPicture)
    }
    val picture = state.room?.roomId?.let { pictures[it] }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        GroupRoomIcon(picture, state.members, 32.dp, state.name)
                        Column {
                            Text(state.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                subtitle(state),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_bots_back))
                    }
                },
                actions = {
                    RoomMenu(
                        state = state,
                        hasPicture = picture != null,
                        onChoosePicture = {
                            choosePicture.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onRemovePicture = viewModel::removePicture,
                        onStop = viewModel::stop,
                        onDisband = { disbanding = true },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Transcript(state, picture, viewModel::retry, Modifier.weight(1f))
            Approvals(state, viewModel::answer)
            RoomComposer(state, viewModel)
        }
    }
    if (disbanding) {
        AlertDialog(
            onDismissRequest = { disbanding = false },
            title = { Text(stringResource(R.string.feature_bots_group_disband_title)) },
            text = { Text(stringResource(R.string.feature_bots_group_disband_body)) },
            confirmButton = {
                TextButton(onClick = {
                    disbanding = false
                    viewModel.disband()
                }) {
                    Text(stringResource(R.string.feature_bots_group_disband), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { disbanding = false }) { Text(stringResource(R.string.feature_bots_cancel)) }
            },
        )
    }
}

@Composable
private fun subtitle(state: GroupRoomUiState): String {
    val count = state.members.size
    val responder = state.responder
    return when {
        state.approvals.isNotEmpty() -> stringResource(R.string.feature_bots_group_needs_approval)
        responder != null -> stringResource(R.string.feature_bots_group_responding, memberName(responder))
        state.driver?.working == true -> pluralString(R.plurals.feature_bots_group_members_working, count)
        else -> pluralString(R.plurals.feature_bots_group_members, count)
    }
}

@Composable
private fun pluralString(id: Int, count: Int): String = pluralStringResource(id, count, count)

@Composable
private fun RoomMenu(
    state: GroupRoomUiState,
    hasPicture: Boolean,
    onChoosePicture: () -> Unit,
    onRemovePicture: () -> Unit,
    onStop: () -> Unit,
    onDisband: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.feature_bots_group_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val close = { action: () -> Unit ->
                open = false
                action()
            }
            if (state.canSetPicture && state.room != null) {
                MenuItem(R.string.feature_bots_group_choose_picture) { close(onChoosePicture) }
                if (hasPicture) MenuItem(R.string.feature_bots_group_remove_picture) { close(onRemovePicture) }
            }
            if (state.canStop) MenuItem(R.string.feature_bots_group_stop) { close(onStop) }
            if (state.capabilities?.supports("groups.disband") == true) {
                MenuItem(R.string.feature_bots_group_disband, destructive = true) { close(onDisband) }
            }
        }
    }
}

@Composable
private fun MenuItem(label: Int, destructive: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                stringResource(label),
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        onClick = onClick,
    )
}

@Composable
private fun Transcript(state: GroupRoomUiState, picture: ByteArray?, onRetry: () -> Unit, modifier: Modifier) {
    val events = state.visibleEvents
    val responder = state.responder
    val list = rememberLazyListState()
    val rows = events.size + (if (state.pending != null) 1 else 0) + (if (responder != null) 1 else 0)
    LaunchedEffect(state.replay.cursor, responder?.identityKey, state.pending?.eventId) {
        if (rows > 0) list.animateScrollToItem(rows - 1)
    }
    if (rows == 0) {
        Column(
            modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            if (state.loading) {
                CircularProgressIndicator()
            } else {
                GroupRoomIcon(picture, state.members, 56.dp, state.name)
                Caption(stringResource(R.string.feature_bots_group_empty))
            }
        }
        return
    }
    LazyColumn(
        modifier.fillMaxWidth(),
        state = list,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(events.size, key = { events[it].key }) { index ->
            val event = events[index]
            when {
                event.isUserMessage -> UserBubble(event.text.orEmpty(), state.members, event.createdAt)

                event.isMemberMessage -> MemberBubble(
                    event,
                    state.members,
                    continuesRun = index > 0 && sameSpeaker(events[index - 1], event),
                )

                else -> SystemCaption(event, state.members)
            }
        }
        state.pending?.let { pending ->
            item(key = "pending") {
                UserBubble(pending.text, state.members, timestamp = null) {
                    if (state.sending) {
                        Caption(stringResource(R.string.feature_bots_group_sending))
                    } else {
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.feature_bots_group_retry)) }
                    }
                }
            }
        }
        responder?.let { member -> item(key = "responding") { RespondingRow(member) } }
    }
}

@Composable
private fun Approvals(state: GroupRoomUiState, onAnswer: (GroupApproval, String) -> Unit) {
    val approvals = state.approvals
    if (approvals.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        approvals.forEach { approval ->
            val member = GroupRoomTurns.member(approval.memberId, state.members)
            ApprovalCard(
                approval,
                member?.label ?: approval.memberId.ifBlank { stringResource(R.string.feature_bots_group_a_member) },
                state.answers[approval.id],
            ) { choice -> onAnswer(approval, choice) }
        }
    }
}

@Composable
private fun RoomComposer(state: GroupRoomUiState, viewModel: GroupRoomViewModel) {
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::attach)
    }
    var field by remember { mutableStateOf(TextFieldValue(state.draft, TextRange(state.draft.length))) }
    val shown = if (field.text == state.draft) field else TextFieldValue(state.draft, TextRange(state.draft.length))
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
            state.mentions.forEach { mention ->
                Row(
                    Modifier.fillMaxWidth().clickable { viewModel.pickMention(mention) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val everyone = mention.title == GroupRoomViewModel.EVERYONE
                    Text(
                        if (everyone) stringResource(R.string.feature_bots_group_everyone) else mention.title,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "@" + mention.tag,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.attachments.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    state.attachments.forEach { file ->
                        InputChip(
                            selected = false,
                            onClick = { viewModel.removeAttachment(file.id) },
                            label = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    stringResource(R.string.feature_bots_group_remove_file, file.name),
                                    Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
            }
            if (state.uploading) Caption(stringResource(R.string.feature_bots_group_uploading))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.canAttach) {
                    IconButton(onClick = { pickFile.launch(arrayOf("*/*")) }, enabled = !state.uploading) {
                        if (state.attaching) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Add, stringResource(R.string.feature_bots_group_attach))
                        }
                    }
                }
                OutlinedTextField(
                    value = shown,
                    onValueChange = { typed ->
                        val changed = typed.text != field.text
                        field = typed
                        if (changed) viewModel.updateDraft(typed.text)
                    },
                    placeholder = { Text(stringResource(R.string.feature_bots_group_composer)) },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = COMPOSER_LINES,
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = viewModel::send,
                    enabled = state.canSend,
                    modifier = Modifier.padding(start = 6.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.feature_bots_group_send))
                }
            }
        }
    }
}

@Composable
private fun roomMessageText(message: RoomMessage): String = when (message) {
    is RoomMessage.LoadFailed -> stringResource(R.string.feature_bots_group_load_failed, failureText(message.failure))

    is RoomMessage.SendFailed -> stringResource(R.string.feature_bots_group_send_failed, failureText(message.failure))

    RoomMessage.PendingFirst -> stringResource(R.string.feature_bots_group_pending_first)

    is RoomMessage.AttachFailed -> when (val failure = message.failure) {
        is PickFailure.TooLarge -> stringResource(
            R.string.feature_bots_group_file_too_large,
            failure.limitBytes / MEGABYTE,
        )

        PickFailure.Unreadable -> stringResource(R.string.feature_bots_group_file_unreadable)
    }

    is RoomMessage.UploadFailed -> stringResource(
        R.string.feature_bots_group_upload_failed,
        failureText(message.failure),
    )

    is RoomMessage.StopFailed -> stringResource(R.string.feature_bots_group_stop_failed, failureText(message.failure))

    is RoomMessage.DisbandFailed -> stringResource(
        R.string.feature_bots_group_disband_failed,
        failureText(message.failure),
    )

    is RoomMessage.PictureFailed -> message.failure?.let {
        stringResource(R.string.feature_bots_group_picture_failed, failureText(it))
    } ?: stringResource(R.string.feature_bots_group_picture_unusable)
}

private const val COMPOSER_LINES = 6
private const val MEGABYTE = 1024 * 1024
