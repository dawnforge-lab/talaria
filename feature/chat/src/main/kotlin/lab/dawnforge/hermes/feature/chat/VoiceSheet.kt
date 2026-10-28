package lab.dawnforge.hermes.feature.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.json.JsonObject
import lab.dawnforge.hermes.core.chat.RichCards
import lab.dawnforge.hermes.core.data.plugin.ScreenCard
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.core.voice.VoicePhase
import lab.dawnforge.hermes.core.voice.VoiceProblem
import lab.dawnforge.hermes.core.voice.VoiceState

/** What the voice screen can do. */
internal class VoiceActions(
    val onListen: () -> Unit,
    val onPause: () -> Unit,
    val onInterrupt: () -> Unit,
    val onMute: (Boolean) -> Unit,
    val onEnd: () -> Unit,
    val onProblemShown: () -> Unit,
)

/**
 * A voice conversation, over the chat: what it's doing, the microphone's level, the words heard,
 * the conversation so far and what Hermes put on its screen. Back ends it.
 */
@Composable
internal fun VoiceSheet(state: VoiceState, cards: List<ScreenCard>, actions: VoiceActions) {
    Dialog(
        onDismissRequest = actions.onEnd,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        // Keep phone awake, when the user asked for it.
        val view = LocalView.current
        DisposableEffect(state.keepAwake) {
            view.keepScreenOn = state.keepAwake
            onDispose { view.keepScreenOn = false }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(phaseText(state.phase)), style = MaterialTheme.typography.headlineSmall)
                LevelRing(state)
                if (state.hearing.isNotBlank()) {
                    Text(
                        state.hearing,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.problem?.let { problem ->
                    Text(problemText(problem), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    LaunchedEffect(problem) {
                        kotlinx.coroutines.delay(PROBLEM_SHOWN_MS)
                        actions.onProblemShown()
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Conversation(state, cards)
                }
                Controls(state, actions)
            }
        }
    }
}

@Composable
private fun LevelRing(state: VoiceState) {
    val listening = state.phase == VoicePhase.Listening || state.phase == VoicePhase.Thinking
    val grow by animateFloatAsState(
        1f + if (listening) (state.level * LEVEL_GAIN).coerceAtMost(1f) * 0.4f else 0f,
        label = "level",
    )
    Box(
        Modifier.size(120.dp).scale(grow).background(
            if (state.phase == VoicePhase.Speaking) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            CircleShape,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.feature_chat_ic_mic),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(40.dp),
        )
    }
}

/** The conversation so far, newest at the bottom, with what Hermes put on screen after it. */
@Composable
private fun Conversation(state: VoiceState, cards: List<ScreenCard>) {
    val list = rememberLazyListState()
    val count = state.lines.size + cards.size
    LaunchedEffect(count) { if (count > 0) list.animateScrollToItem(count - 1) }
    LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(state.lines.size) { index ->
            val line = state.lines[index]
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start,
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (line.fromUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ) {
                    Text(line.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        items(cards, key = { it.id }) { card -> ScreenCardView(card) }
    }
}

@Composable
private fun ScreenCardView(card: ScreenCard) {
    val drawn = remember(card.id) { card.cards.orEmpty().mapNotNull { (it as? JsonObject)?.let(RichCards::parse) } }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (card.title.isNotEmpty()) Text(card.title, style = MaterialTheme.typography.titleMedium)
            if (drawn.isNotEmpty()) RichCardsView(drawn, superseded = emptySet())
            if (card.markdown.isNotEmpty()) ChatMarkdown(card.markdown, streaming = false)
        }
    }
}

@Composable
private fun Controls(state: VoiceState, actions: VoiceActions) {
    val paused = state.phase == VoicePhase.Paused
    val busy = state.phase == VoicePhase.Thinking || state.phase == VoicePhase.Speaking
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = if (paused) actions.onListen else actions.onPause, modifier = Modifier.weight(1f)) {
            Text(stringResource(if (paused) R.string.feature_chat_voice_listen else R.string.feature_chat_voice_pause))
        }
        OutlinedButton(onClick = actions.onInterrupt, enabled = busy, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.feature_chat_voice_interrupt))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { actions.onMute(!state.muted) }, modifier = Modifier.weight(1f)) {
            Text(
                stringResource(
                    if (state.muted) R.string.feature_chat_voice_sound_on else R.string.feature_chat_voice_mute,
                ),
            )
        }
        Button(
            onClick = actions.onEnd,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.feature_chat_voice_end))
        }
    }
    Spacer(Modifier.height(4.dp))
}

private fun phaseText(phase: VoicePhase): Int = when (phase) {
    VoicePhase.Starting -> R.string.feature_chat_voice_starting
    VoicePhase.Listening -> R.string.feature_chat_voice_listening
    VoicePhase.Transcribing -> R.string.feature_chat_voice_transcribing
    VoicePhase.Thinking -> R.string.feature_chat_voice_thinking
    VoicePhase.Speaking -> R.string.feature_chat_voice_speaking
    VoicePhase.Paused -> R.string.feature_chat_voice_paused
    VoicePhase.Ended -> R.string.feature_chat_voice_paused
}

@Composable
private fun problemText(problem: VoiceProblem): String = when (problem) {
    VoiceProblem.NoPermission -> stringResource(R.string.feature_chat_dictate_no_permission)

    VoiceProblem.MicBusy -> stringResource(R.string.feature_chat_dictate_mic_busy)

    is VoiceProblem.Recognizer -> stringResource(R.string.feature_chat_voice_recognizer_failed)

    is VoiceProblem.Transcription -> stringResource(
        R.string.feature_chat_dictate_hermes_failed,
        failureText(problem.failure),
    )

    VoiceProblem.NotSent -> stringResource(R.string.feature_chat_voice_not_sent)
}

private const val LEVEL_GAIN = 6f
private const val PROBLEM_SHOWN_MS = 6_000L
