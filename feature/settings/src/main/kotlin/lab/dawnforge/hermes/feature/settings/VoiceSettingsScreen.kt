package lab.dawnforge.hermes.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lab.dawnforge.hermes.core.data.settings.DeviceVoicePreferences
import lab.dawnforge.hermes.core.data.settings.DictationEngine
import lab.dawnforge.hermes.core.data.settings.HermesChangesMode
import lab.dawnforge.hermes.core.data.settings.SpeechOutput
import lab.dawnforge.hermes.core.data.settings.VoicePreferences
import lab.dawnforge.hermes.core.data.settings.VoiceReplies
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.core.voice.OfflineSpeech
import lab.dawnforge.hermes.core.voice.SpokenCommands

/** The voice page: setting up, the conversation, Hermes' voice, commands, dictation, read aloud. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceSettingsScreen(
    viewModel: VoiceSettingsViewModel,
    onBack: () -> Unit,
    onOpenToolset: (String) -> Unit,
    onReviewChanges: () -> Unit,
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val device by viewModel.device.collectAsStateWithLifecycle()
    val readiness by viewModel.voiceReadiness.collectAsStateWithLifecycle()
    val hermes by viewModel.hermes.collectAsStateWithLifecycle()
    val review by viewModel.review.collectAsStateWithLifecycle()
    val name by viewModel.profileName.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.feature_settings_voice))
                        name?.let {
                            Text(
                                stringResource(R.string.feature_settings_profile, it),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_settings_back))
                    }
                },
            )
        },
    ) { padding ->
        val prefs = profile ?: return@Scaffold
        val phone = device ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            setup(readiness, viewModel)
            conversation(prefs, phone, viewModel)
            hermesVoice(hermes, viewModel, onOpenToolset)
            commands(prefs, viewModel)
            dictation(phone, viewModel)
            readAloud(phone, viewModel)
            hermesChanges(phone, review.size, viewModel, onReviewChanges)
        }
    }
}

private fun LazyListScope.setup(readiness: VoiceReadiness, viewModel: VoiceSettingsViewModel) {
    item { CardHeader(stringResource(R.string.feature_settings_voice_setup)) }
    item { MicrophoneCheck() }
    item {
        Check(
            R.string.feature_settings_voice_phone_recognizes,
            if (readiness.phoneRecognizes) {
                R.string.feature_settings_voice_ready
            } else {
                R.string.feature_settings_voice_not_on_phone
            },
            readiness.phoneRecognizes,
        )
    }
    item {
        Check(
            R.string.feature_settings_voice_phone_speaks,
            if (readiness.phoneSpeaks) {
                R.string.feature_settings_voice_ready
            } else {
                R.string.feature_settings_voice_not_on_phone
            },
            readiness.phoneSpeaks,
        )
    }
    val hermes = readiness.hermes
    item {
        Check(
            R.string.feature_settings_voice_hermes_listens,
            if (hermes?.canListen == true) {
                R.string.feature_settings_voice_ready
            } else {
                R.string.feature_settings_voice_needs_setup
            },
            hermes?.canListen == true,
        )
    }
    item {
        val detail = when {
            hermes?.canSpeak != true -> stringResource(R.string.feature_settings_voice_needs_setup)
            hermes.speakerIsEdge -> stringResource(R.string.feature_settings_voice_edge, hermes.speaker.orEmpty())
            else -> hermes.speaker.orEmpty()
        }
        Check(stringResource(R.string.feature_settings_voice_hermes_speaks), detail, hermes?.canSpeak == true)
    }
    if (readiness.offlineSpeech == OfflineSpeech.Missing) {
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.feature_settings_voice_offline)) },
                supportingContent = { Text(stringResource(R.string.feature_settings_voice_offline_help)) },
                trailingContent = {
                    TextButton(onClick = viewModel::downloadOfflineSpeech) {
                        Text(stringResource(R.string.feature_settings_voice_download))
                    }
                },
            )
        }
    }
    item {
        val sample = stringResource(R.string.feature_settings_voice_sample)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { viewModel.testSpeaking(sample) }) {
                Text(stringResource(R.string.feature_settings_voice_test_speaking))
            }
            TextButton(onClick = viewModel::refresh) {
                Text(stringResource(R.string.feature_settings_voice_check_again))
            }
        }
    }
}

@Composable
private fun MicrophoneCheck() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    ListItem(
        headlineContent = { Text(stringResource(R.string.feature_settings_voice_microphone)) },
        supportingContent = {
            Text(
                stringResource(
                    if (granted) {
                        R.string.feature_settings_voice_allowed
                    } else {
                        R.string.feature_settings_voice_not_allowed
                    },
                ),
            )
        },
        trailingContent = {
            if (!granted) {
                TextButton(onClick = { ask.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text(stringResource(R.string.feature_settings_voice_allow))
                }
            } else {
                Text("✓", color = MaterialTheme.colorScheme.primary)
            }
        },
    )
}

@Composable
private fun Check(title: Int, detail: Int, ok: Boolean) = Check(stringResource(title), stringResource(detail), ok)

@Composable
private fun Check(title: String, detail: String, ok: Boolean) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = {
            Text(
                if (ok) "✓" else "—",
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

private fun LazyListScope.conversation(
    prefs: VoicePreferences,
    device: DeviceVoicePreferences,
    viewModel: VoiceSettingsViewModel,
) {
    item { CardHeader(stringResource(R.string.feature_settings_voice_conversation)) }
    item { SubHeader(R.string.feature_settings_voice_listening_by) }
    items(ENGINES) { (engine, label) ->
        Choice(stringResource(label), selected = prefs.transcription == engine) {
            viewModel.updateProfile { it.copy(transcription = engine) }
        }
    }
    item { SubHeader(R.string.feature_settings_voice_spoken_by) }
    items(OUTPUTS) { (output, label) ->
        Choice(stringResource(label), selected = prefs.speechOutput == output) {
            viewModel.updateProfile { it.copy(speechOutput = output) }
        }
    }
    item {
        Toggle(
            R.string.feature_settings_voice_continuous,
            R.string.feature_settings_voice_continuous_help,
            prefs.continuousConversation,
        ) { on -> viewModel.updateProfile { it.copy(continuousConversation = on) } }
    }
    item {
        Toggle(
            R.string.feature_settings_voice_locked,
            R.string.feature_settings_voice_locked_help,
            prefs.keepListeningWhenLocked,
        ) { on -> viewModel.updateProfile { it.copy(keepListeningWhenLocked = on) } }
    }
    item {
        Toggle(
            R.string.feature_settings_voice_awake,
            R.string.feature_settings_voice_awake_help,
            device.keepScreenAwake,
        ) { on ->
            viewModel.updateDevice { it.copy(keepScreenAwake = on) }
        }
    }
}

private fun LazyListScope.hermesVoice(
    hermes: HermesVoiceSettings,
    viewModel: VoiceSettingsViewModel,
    onOpenToolset: (String) -> Unit,
) {
    item { CardHeader(stringResource(R.string.feature_settings_voice_hermes)) }
    hermes.liveWords?.let { live ->
        item {
            Toggle(R.string.feature_settings_voice_live_words, R.string.feature_settings_voice_live_words_help, live) {
                viewModel.setLiveWords(it)
            }
        }
    }
    hermes.replies?.let { replies ->
        item { VoiceRepliesRows(replies, hermes, viewModel::setVoiceReplies) }
    }
    hermes.failure?.let { failure ->
        item {
            Text(
                failureText(failure),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
    item {
        ListItem(
            headlineContent = { Text(stringResource(R.string.feature_settings_voice_providers_listen)) },
            supportingContent = { Text(stringResource(R.string.feature_settings_voice_providers_help)) },
            modifier = Modifier.clickable { onOpenToolset(STT) },
        )
    }
    item {
        ListItem(
            headlineContent = { Text(stringResource(R.string.feature_settings_voice_providers_speak)) },
            supportingContent = { Text(stringResource(R.string.feature_settings_voice_providers_help)) },
            modifier = Modifier.clickable { onOpenToolset(TTS) },
        )
    }
}

/** The model spoken turns are answered with, and how hard it thinks. */
@Composable
private fun VoiceRepliesRows(replies: VoiceReplies, hermes: HermesVoiceSettings, onChange: (VoiceReplies) -> Unit) {
    var pickingModel by remember { mutableStateOf(false) }
    var pickingReasoning by remember { mutableStateOf(false) }
    val sameAsChats = stringResource(R.string.feature_settings_voice_same_model)
    ListItem(
        headlineContent = { Text(stringResource(R.string.feature_settings_voice_reply_model)) },
        supportingContent = {
            Text(if (replies.provider.isEmpty()) sameAsChats else "${replies.model} · ${replies.provider}")
        },
        modifier = Modifier.clickable(enabled = !hermes.saving && hermes.models != null) { pickingModel = true },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.feature_settings_voice_reply_reasoning)) },
        supportingContent = {
            Column {
                Text(replies.reasoning.replaceFirstChar(Char::uppercaseChar))
                Text(
                    stringResource(R.string.feature_settings_voice_reply_reasoning_help),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        modifier = Modifier.clickable(enabled = !hermes.saving) { pickingReasoning = true },
    )
    if (pickingModel) {
        val providers = hermes.models?.providers.orEmpty()
        AlertDialog(
            onDismissRequest = { pickingModel = false },
            title = { Text(stringResource(R.string.feature_settings_voice_reply_model)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Choice(sameAsChats, selected = replies.provider.isEmpty()) {
                        pickingModel = false
                        onChange(replies.copy(provider = "", model = ""))
                    }
                    providers.forEach { provider ->
                        Text(
                            provider.name,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        provider.models.forEach { model ->
                            Choice(
                                model.id,
                                selected = replies.provider == provider.slug && replies.model == model.id,
                            ) {
                                pickingModel = false
                                onChange(replies.copy(provider = provider.slug, model = model.id))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pickingModel = false
                }) { Text(stringResource(R.string.feature_settings_cancel)) }
            },
        )
    }
    if (pickingReasoning) {
        AlertDialog(
            onDismissRequest = { pickingReasoning = false },
            title = { Text(stringResource(R.string.feature_settings_voice_reply_reasoning)) },
            text = {
                Column {
                    VoiceReplies.REASONING.forEach { level ->
                        Choice(level.replaceFirstChar(Char::uppercaseChar), selected = replies.reasoning == level) {
                            pickingReasoning = false
                            onChange(replies.copy(reasoning = level))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pickingReasoning = false
                }) { Text(stringResource(R.string.feature_settings_cancel)) }
            },
        )
    }
}

private fun LazyListScope.commands(prefs: VoicePreferences, viewModel: VoiceSettingsViewModel) {
    item { CardHeader(stringResource(R.string.feature_settings_voice_commands)) }
    item {
        PhrasesRow(
            R.string.feature_settings_voice_stop_phrases,
            prefs.stopPhrases,
            SpokenCommands.STOP,
        ) { phrases -> viewModel.updateProfile { it.copy(stopPhrases = phrases) } }
    }
    item {
        PhrasesRow(
            R.string.feature_settings_voice_end_phrases,
            prefs.endPhrases,
            SpokenCommands.END,
        ) { phrases -> viewModel.updateProfile { it.copy(endPhrases = phrases) } }
    }
}

/** A list of spoken phrases: the built-ins until edited; emptied, the command is off. */
@Composable
private fun PhrasesRow(title: Int, phrases: List<String>?, builtIn: List<String>, onChange: (List<String>?) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val shown = phrases ?: builtIn
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = {
            Text(
                if (shown.isEmpty()) {
                    stringResource(R.string.feature_settings_voice_phrases_off)
                } else {
                    shown.joinToString(", ") { "“$it”" }
                },
            )
        },
        modifier = Modifier.clickable { editing = true },
    )
    if (editing) {
        ListEditor(
            title = stringResource(title),
            help = stringResource(R.string.feature_settings_voice_phrases_help),
            initial = shown,
            onDismiss = { editing = false },
            onReset = {
                editing = false
                onChange(null)
            },
        ) { edited ->
            editing = false
            // Exactly the built-ins: keep following them.
            onChange(edited.takeUnless { list -> list.map(SpokenCommands::canonical).toSet() == builtIn.toSet() })
        }
    }
}

private fun LazyListScope.dictation(device: DeviceVoicePreferences, viewModel: VoiceSettingsViewModel) {
    item { CardHeader(stringResource(R.string.feature_settings_voice_dictation)) }
    items(ENGINES) { (engine, label) ->
        Choice(stringResource(label), selected = device.dictationEngine == engine) {
            viewModel.updateDevice { it.copy(dictationEngine = engine) }
        }
    }
    item {
        var editing by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.feature_settings_voice_words)) },
            supportingContent = {
                Text(
                    device.dictationWords.joinToString(", ").ifEmpty {
                        stringResource(R.string.feature_settings_voice_words_none)
                    },
                )
            },
            modifier = Modifier.clickable { editing = true },
        )
        if (editing) {
            ListEditor(
                title = stringResource(R.string.feature_settings_voice_words),
                help = stringResource(R.string.feature_settings_voice_words_help),
                initial = device.dictationWords,
                onDismiss = { editing = false },
                onReset = null,
            ) { words ->
                editing = false
                viewModel.updateDevice { it.copy(dictationWords = words) }
            }
        }
    }
}

private fun LazyListScope.readAloud(device: DeviceVoicePreferences, viewModel: VoiceSettingsViewModel) {
    item { CardHeader(stringResource(R.string.feature_settings_voice_read_aloud)) }
    items(DeviceVoicePreferences.SPEEDS) { speed ->
        val label = (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "×"
        Choice(label, selected = device.readAloudSpeed == speed) {
            viewModel.updateDevice { it.copy(readAloudSpeed = speed) }
        }
    }
}

private fun LazyListScope.hermesChanges(
    device: DeviceVoicePreferences,
    waiting: Int,
    viewModel: VoiceSettingsViewModel,
    onReviewChanges: () -> Unit,
) {
    item { CardHeader(stringResource(R.string.feature_settings_voice_changes)) }
    items(MODES) { (mode, label, help) ->
        Column {
            Choice(stringResource(label), selected = device.hermesChanges == mode) {
                viewModel.updateDevice { it.copy(hermesChanges = mode) }
            }
            if (device.hermesChanges == mode) {
                Text(
                    stringResource(help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
                )
            }
        }
    }
    if (waiting > 0) {
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.feature_settings_voice_review, waiting)) },
                modifier = Modifier.clickable(onClick = onReviewChanges),
            )
        }
    }
}

/** One item per line; [onReset] puts back the built-ins. */
@Composable
private fun ListEditor(
    title: String,
    help: String,
    initial: List<String>,
    onDismiss: () -> Unit,
    onReset: (() -> Unit)?,
    onSave: (List<String>) -> Unit,
) {
    var text by remember { mutableStateOf(initial.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(help, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = text, onValueChange = {
                    text = it
                }, minLines = 3, modifier = Modifier.fillMaxWidth())
                onReset?.let {
                    TextButton(onClick = it, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.feature_settings_voice_reset))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.lines().map(String::trim).filter(String::isNotEmpty)) }) {
                Text(stringResource(R.string.feature_settings_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_settings_cancel)) } },
    )
}

@Composable
private fun SubHeader(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

private val ENGINES = listOf(
    null to R.string.feature_settings_voice_automatic,
    DictationEngine.Phone to R.string.feature_settings_voice_this_phone,
    DictationEngine.Hermes to R.string.feature_settings_voice_hermes_engine,
)

private val OUTPUTS = listOf(
    SpeechOutput.Automatic to R.string.feature_settings_voice_automatic,
    SpeechOutput.Phone to R.string.feature_settings_voice_this_phone,
    SpeechOutput.Hermes to R.string.feature_settings_voice_hermes_engine,
)

private val MODES = listOf(
    Triple(HermesChangesMode.Off, R.string.feature_settings_changes_off, R.string.feature_settings_changes_off_help),
    Triple(HermesChangesMode.Ask, R.string.feature_settings_changes_ask, R.string.feature_settings_changes_ask_help),
    Triple(
        HermesChangesMode.Small,
        R.string.feature_settings_changes_small,
        R.string.feature_settings_changes_small_help,
    ),
)

private const val STT = "stt"
private const val TTS = "tts"
