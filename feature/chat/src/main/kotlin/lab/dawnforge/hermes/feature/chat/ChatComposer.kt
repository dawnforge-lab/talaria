package lab.dawnforge.hermes.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import lab.dawnforge.hermes.core.data.bots.MentionCandidate

/** What the composer can do; the screen passes the view model's methods. */
internal class ComposerActions(
    val onDraftChange: (String) -> Unit,
    val onAttach: (Uri) -> Unit,
    val onRemoveAttachment: (Long) -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    /** A suggested slash command, by name. */
    val onPickCommand: (String) -> Unit,
    /** A bot picked for the `@` being typed. */
    val onPickMention: (MentionCandidate) -> Unit,
    val onCancelReply: () -> Unit,
    /** The chat's working folder on the host, once Hermes names one. */
    val onOpenWorkspace: () -> Unit,
    /** Starts or stops dictation into the draft. */
    val onDictate: () -> Unit,
    /** Opens a voice conversation in this chat. */
    val onVoice: () -> Unit,
)

/**
 * The message field, with the files picked for the message above it. While the agent works and
 * nothing is typed, the send button becomes a stop button.
 */
@Composable
internal fun ChatComposer(state: ChatUiState, actions: ComposerActions, modifier: Modifier = Modifier) {
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(actions.onAttach)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(actions.onAttach)
    }
    val takePhoto = rememberPhotoTaker(actions.onAttach)
    var choosing by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val field = rememberDraftState(state.draft, actions.onDraftChange)
    if (editing) FullEditor(state, actions, onClose = { editing = false })
    Surface(tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
            state.workingCommand?.let { command ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.feature_chat_command_running, command),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.replyTo?.let { reply ->
                InputChip(
                    selected = false,
                    onClick = actions.onCancelReply,
                    label = {
                        Text(
                            stringResource(R.string.feature_chat_replying_to_chip, reply.lineSequence().first()),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.feature_chat_reply_cancel),
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (state.suggestions.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                    state.suggestions.forEach { command ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { actions.onPickCommand(command.name) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text("/" + command.name, style = MaterialTheme.typography.bodyMedium)
                            if (command.description.isNotBlank()) {
                                Text(
                                    command.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            if (state.mentions.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                    state.mentions.forEach { mention ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { actions.onPickMention(mention) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(mention.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "@" + mention.tag,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (state.attachments.isNotEmpty() || state.attaching) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                ) {
                    state.attachments.forEach { attachment ->
                        InputChip(
                            selected = false,
                            onClick = { actions.onRemoveAttachment(attachment.id) },
                            label = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(
                                        R.string.feature_chat_attachment_remove,
                                        attachment.name,
                                    ),
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                    if (state.attaching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { choosing = true }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.feature_chat_attach))
                    }
                    DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.feature_chat_attach_photo)) },
                            onClick = {
                                choosing = false
                                pickPhoto.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.feature_chat_attach_camera)) },
                            onClick = {
                                choosing = false
                                takePhoto()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.feature_chat_attach_file)) },
                            onClick = {
                                choosing = false
                                pickFile.launch(arrayOf("*/*"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.feature_chat_workspace)) },
                            enabled = state.cwd != null,
                            onClick = {
                                choosing = false
                                actions.onOpenWorkspace()
                            },
                        )
                    }
                }
                OutlinedTextField(
                    state = field,
                    placeholder = {
                        Text(
                            stringResource(
                                if (state.running) {
                                    R.string.feature_chat_composer_running
                                } else {
                                    R.string.feature_chat_composer
                                },
                            ),
                        )
                    },
                    shape = RoundedCornerShape(24.dp),
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = COMPOSER_LINES),
                    trailingIcon = if (state.draft.isLong()) {
                        {
                            IconButton(onClick = { editing = true }) {
                                Icon(
                                    Icons.Filled.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.feature_chat_full_editor),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.weight(
                        1f,
                    ).heightIn(min = 48.dp).pastedImages(actions.onAttach).onPreviewKeyEvent { key ->
                        val returnKey = key.key == Key.Enter || key.key == Key.NumPadEnter
                        val sends = state.prefs.returnKeySends && returnKey && !key.isShiftPressed &&
                            key.nativeKeyEvent.device?.isVirtual == false
                        if (sends && key.type == KeyEventType.KeyDown && state.canSend &&
                            !state.attaching
                        ) {
                            actions.onSend()
                        }
                        // The key's release is used up too, so it adds no line after sending.
                        sends
                    },
                )
                DictateButton(state.dictation, actions.onDictate)
                val stopShown = state.running && !state.canSend
                // Nothing typed and nothing running: a voice conversation, as on iOS.
                if (!stopShown && !state.canSend && state.dictation == null) {
                    VoiceButton(actions.onVoice)
                    return@Row
                }
                FilledIconButton(
                    onClick = if (stopShown) actions.onStop else actions.onSend,
                    enabled = if (stopShown) !state.stopping else state.canSend && !state.attaching,
                    modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                ) {
                    if (stopShown) {
                        val stop = stringResource(R.string.feature_chat_stop)
                        // A filled square, the usual stop sign (the core icon set has none).
                        Box(
                            Modifier
                                .size(14.dp)
                                .background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(2.dp))
                                .semantics { contentDescription = stop },
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.feature_chat_send),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dictation's microphone: red while it listens, pulsing while it starts or Hermes transcribes. It
 * asks for the microphone the first time.
 */
@Composable
private fun DictateButton(phase: DictationPhase?, onDictate: () -> Unit) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onDictate()
    }
    val pulse = rememberInfiniteTransition(label = "dictation")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = PULSE_LOW,
        animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
        label = "dictation-alpha",
    )
    val label = stringResource(
        when (phase) {
            null -> R.string.feature_chat_dictate
            DictationPhase.Transcribing -> R.string.feature_chat_dictate_transcribing
            else -> R.string.feature_chat_dictate_stop
        },
    )
    IconButton(
        onClick = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted || phase != null) onDictate() else ask.launch(Manifest.permission.RECORD_AUDIO)
        },
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        Icon(
            painterResource(R.drawable.feature_chat_ic_mic),
            contentDescription = label,
            tint = when (phase) {
                null -> MaterialTheme.colorScheme.onSurfaceVariant
                DictationPhase.Listening -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
            modifier = if (phase == DictationPhase.Starting || phase == DictationPhase.Transcribing) {
                Modifier.alpha(alpha)
            } else {
                Modifier
            },
        )
    }
}

/** Opens a voice conversation; asks for the microphone first if needed. */
@Composable
private fun VoiceButton(onVoice: () -> Unit) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onVoice()
    }
    FilledIconButton(
        onClick = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) onVoice() else ask.launch(Manifest.permission.RECORD_AUDIO)
        },
        modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
    ) {
        Icon(
            painterResource(R.drawable.feature_chat_ic_voice),
            contentDescription = stringResource(R.string.feature_chat_voice_open),
        )
    }
}

private const val PULSE_LOW = 0.35f
private const val PULSE_MS = 600
private const val COMPOSER_LINES = 6

/**
 * The draft as the field's state. Typing reports each change. A draft changed from outside the
 * field (a picked @mention or command, a cleared draft) replaces its text with the cursor at the
 * end, ready for the next word; the view model's echo of what was typed never does, so a fast
 * typist's newer keystrokes are kept.
 */
@Composable
private fun rememberDraftState(draft: String, onDraftChange: (String) -> Unit): TextFieldState {
    val field = rememberTextFieldState(draft)
    val report by rememberUpdatedState(onDraftChange)
    val current by rememberUpdatedState(draft)
    // What the field reported that the draft hasn't caught up with yet, oldest first.
    val sent = remember { ArrayDeque<String>() }
    LaunchedEffect(draft) {
        val echo = sent.indexOf(draft)
        if (echo >= 0) {
            repeat(echo + 1) { sent.removeFirst() }
            return@LaunchedEffect
        }
        if (field.text.toString() != draft) field.setTextAndPlaceCursorAtEnd(draft)
    }
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.collect { text ->
            if (text != current) {
                sent.addLast(text)
                report(text)
            }
        }
    }
    return field
}

/**
 * Images pasted, or sent from the keyboard, go to the message as files ([onImage]). As on iOS, text
 * that came with an image isn't inserted.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.pastedImages(onImage: (Uri) -> Unit): Modifier = contentReceiver { content ->
    if (!content.hasMediaType(MediaType.Image)) return@contentReceiver content
    val clip = content.clipEntry.clipData
    val images = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    images.forEach(onImage)
    if (images.isEmpty()) content else null
}

/** Long enough to be easier to write in the full editor: a few lines, or a long paragraph. */
private fun String.isLong(): Boolean = lines().size >= LONG_DRAFT_LINES || length >= LONG_DRAFT_CHARS

/** The draft on the whole screen, for a long message; it is the same draft as the composer\'s. */
@Composable
private fun FullEditor(state: ChatUiState, actions: ComposerActions, onClose: () -> Unit) {
    val field = rememberDraftState(state.draft, actions.onDraftChange)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().imePadding().padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.feature_chat_full_editor_close),
                        )
                    }
                    Text(
                        stringResource(R.string.feature_chat_full_editor_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    FilledIconButton(
                        onClick = {
                            onClose()
                            actions.onSend()
                        },
                        enabled = state.canSend && !state.attaching,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.feature_chat_send),
                        )
                    }
                }
                OutlinedTextField(
                    state = field,
                    modifier = Modifier.fillMaxWidth().weight(1f).pastedImages(actions.onAttach),
                )
            }
        }
    }
}

/**
 * Takes a photo with the camera app into the app\'s cache and hands it on. The app holds the camera
 * permission (for scanning codes), so Android asks for it before the camera app may be used.
 */
@Composable
private fun rememberPhotoTaker(onTaken: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Uri?>(null) }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        pending?.takeIf { taken }?.let(onTaken)
        pending = null
    }
    fun launch() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        // Earlier photos were read when they were attached; only the new one is needed.
        dir.listFiles()?.forEach(File::delete)
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        pending = uri
        take.launch(uri)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launch()
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launch()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }
}

private const val LONG_DRAFT_LINES = 3
private const val LONG_DRAFT_CHARS = 160
