package lab.dawnforge.hermes.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import lab.dawnforge.hermes.core.data.settings.DeviceVoicePreferences
import lab.dawnforge.hermes.core.voice.ReadAloudState

/** What the read-aloud bar can do. */
internal class ReaderActions(
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onSpeed: (Float) -> Unit,
    val onStop: () -> Unit,
)

/** The reply being read aloud: pause or resume, speed, and stop, above the composer. */
@Composable
internal fun ReadAloudBar(state: ReadAloudState, actions: ReaderActions) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val paused = state.phase == ReadAloudState.Phase.Paused
            IconButton(onClick = if (paused) actions.onResume else actions.onPause) {
                if (paused) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.feature_chat_read_aloud_resume),
                    )
                } else {
                    Icon(
                        painterResource(R.drawable.feature_chat_ic_pause),
                        contentDescription = stringResource(R.string.feature_chat_read_aloud_pause),
                    )
                }
            }
            Text(
                stringResource(
                    if (paused) R.string.feature_chat_read_aloud_paused else R.string.feature_chat_read_aloud_reading,
                ),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            // Each tap moves to the next speed, back to 1x after the fastest.
            val speeds = DeviceVoicePreferences.SPEEDS
            val next = speeds[(speeds.indexOf(state.speed).coerceAtLeast(0) + 1) % speeds.size]
            val speedText = speedLabel(state.speed)
            TextButton(onClick = { actions.onSpeed(next) }) {
                Text(stringResource(R.string.feature_chat_read_aloud_speed, speedText))
            }
            IconButton(onClick = actions.onStop) {
                val stop = stringResource(R.string.feature_chat_read_aloud_stop)
                Box(
                    Modifier
                        .size(14.dp)
                        .background(MaterialTheme.colorScheme.onSecondaryContainer, RoundedCornerShape(2.dp))
                        .semantics { contentDescription = stop },
                )
            }
        }
    }
}

/** "1×", "1.25×". */
private fun speedLabel(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"
