package lab.dawnforge.hermes.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.chat.PlainText
import lab.dawnforge.hermes.core.data.chat.ChatMemory
import lab.dawnforge.hermes.core.data.plugin.ConfirmOutcome
import lab.dawnforge.hermes.core.data.plugin.ConfirmPrompt
import lab.dawnforge.hermes.core.data.plugin.PresentedConfirms

/** The confirmation on screen, and the user's answers to it. */
@HiltViewModel
class PresentedConfirmViewModel @Inject constructor(
    private val confirms: PresentedConfirms,
    private val memory: ChatMemory,
) : ViewModel() {
    val current: StateFlow<ConfirmPrompt?> =
        confirms.current.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** The profile the confirmation is from: the active one. */
    private val profile: StateFlow<String?> =
        confirms.place.map { it?.second }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun answer(prompt: ConfirmPrompt, outcome: ConfirmOutcome) {
        viewModelScope.launch { confirms.answer(prompt, outcome) }
    }

    fun putOff(prompt: ConfirmPrompt) = confirms.putOff(prompt)

    fun dropExpired() = confirms.dropExpired()

    /**
     * Edit: Hermes hears the user wants changes, and the chat it came from gets the preview in its
     * composer to rework and send. Answers the chat to open, or null when there is none.
     */
    fun edit(prompt: ConfirmPrompt): Pair<String, String>? {
        val session = prompt.sessionId ?: return null
        val profile = profile.value ?: return null
        // The key the chat keeps its draft under, by its stored id.
        memory.prefill("$profile/$session", PlainText.of(prompt.preview))
        answer(prompt, ConfirmOutcome.Edit)
        return profile to session
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * The confirmation Hermes asks this phone for (`talaria_present`), over whatever is on screen: a
 * sheet with the title, a preview of what will happen, the action, Not now and Edit. Dismissing it
 * puts it off until the app next comes to the front; Hermes keeps waiting until it expires.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresentedConfirmHost(
    onEdit: (profile: String, sessionId: String) -> Unit,
    viewModel: PresentedConfirmViewModel = hiltViewModel(),
) {
    val prompt by viewModel.current.collectAsStateWithLifecycle()
    val shown = prompt ?: return
    // Leaves when its time runs out, not at the next exchange.
    LaunchedEffect(shown.id) {
        val expires = shown.expiresAt ?: return@LaunchedEffect
        delay(((expires * MILLIS).toLong() - System.currentTimeMillis()).coerceAtLeast(0))
        viewModel.dropExpired()
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Slides away before the answer takes it off screen.
    fun closeThen(action: () -> Unit) {
        scope.launch { sheet.hide() }.invokeOnCompletion { action() }
    }
    ModalBottomSheet(onDismissRequest = { viewModel.putOff(shown) }, sheetState = sheet) {
        ConfirmPanel(
            prompt = shown,
            onConfirm = { closeThen { viewModel.answer(shown, ConfirmOutcome.Confirmed) } },
            onDecline = { closeThen { viewModel.answer(shown, ConfirmOutcome.Declined) } },
            // Directions have nothing to rework.
            onEdit = if (shown.sessionId == null || shown.maps != null) {
                null
            } else {
                { closeThen { viewModel.edit(shown)?.let { (profile, session) -> onEdit(profile, session) } } }
            },
        )
    }
}

@Composable
private fun ConfirmPanel(prompt: ConfirmPrompt, onConfirm: () -> Unit, onDecline: () -> Unit, onEdit: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.feature_chat_confirm_asks),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(prompt.title, style = MaterialTheme.typography.titleLarge)
        if (prompt.preview.isNotBlank()) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Box(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                    ChatMarkdown(prompt.preview, streaming = false)
                }
            }
        }
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
            Text(prompt.actionLabel ?: stringResource(R.string.feature_chat_confirm_default))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.feature_chat_confirm_not_now))
            }
            if (onEdit != null) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.feature_chat_confirm_edit))
                }
            }
        }
    }
}

private const val MILLIS = 1000
