package lab.dawnforge.hermes.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.ui.failureText

/** Who holds a chat Hermes wouldn't take a message for, from its refusal. */
enum class TakeoverOwner {
    Desktop,
    Terminal,
    Other,
    ;

    companion object {
        fun of(surface: String?): TakeoverOwner = when (surface?.lowercase()) {
            "desktop" -> Desktop
            "cli", "tui" -> Terminal
            else -> Other
        }
    }
}

sealed interface TakeoverProblem {
    /** The other app was still replying when the wait ran out. */
    data object StillReplying : TakeoverProblem

    /** The host's push plugin is missing or too old to take chats over. */
    data object PluginMissing : TakeoverProblem

    /** This Hermes can't hand a chat over. */
    data object Unsupported : TakeoverProblem

    data class Error(val failure: ConnectionFailure) : TakeoverProblem
}

sealed interface TakeoverPhase {
    data object Offered : TakeoverPhase

    /** Asking Hermes, and waiting while the other app replies. */
    data object Waiting : TakeoverPhase

    /** Another phone or the web chat on this dashboard holds it: nothing to take. */
    data object HeldHere : TakeoverPhase

    /** It failed and can be tried again. */
    data class Failed(val problem: TakeoverProblem) : TakeoverPhase

    /** It can't be done on this host. */
    data class Unavailable(val problem: TakeoverProblem) : TakeoverPhase
}

/** The message [key], refused because [owner] has the chat, and how taking the chat over goes. */
data class Takeover(val key: String, val owner: TakeoverOwner, val phase: TakeoverPhase)

/** Above the composer while a message waits on taking the chat over, as iOS shows it. */
@Composable
internal fun TakeoverNotice(takeover: Takeover, onTakeOver: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (takeover.phase) {
                    TakeoverPhase.Waiting -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)

                    TakeoverPhase.Offered -> Unit

                    else -> Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    takeoverText(takeover),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    val label = if (takeover.phase == TakeoverPhase.Waiting) {
                        R.string.feature_chat_takeover_stop_waiting
                    } else {
                        R.string.feature_chat_takeover_dismiss
                    }
                    Icon(Icons.Filled.Close, stringResource(label))
                }
            }
            if (takeover.phase == TakeoverPhase.Offered || takeover.phase is TakeoverPhase.Failed) {
                OutlinedButton(onClick = onTakeOver, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feature_chat_takeover_button))
                }
            }
        }
    }
}

@Composable
private fun takeoverText(takeover: Takeover): String {
    fun by(desktop: Int, terminal: Int, other: Int): Int = when (takeover.owner) {
        TakeoverOwner.Desktop -> desktop
        TakeoverOwner.Terminal -> terminal
        TakeoverOwner.Other -> other
    }
    return when (val phase = takeover.phase) {
        TakeoverPhase.Offered -> stringResource(
            by(
                R.string.feature_chat_takeover_offer_desktop,
                R.string.feature_chat_takeover_offer_terminal,
                R.string.feature_chat_takeover_offer_other,
            ),
        )

        TakeoverPhase.Waiting -> stringResource(
            by(
                R.string.feature_chat_takeover_wait_desktop,
                R.string.feature_chat_takeover_wait_terminal,
                R.string.feature_chat_takeover_wait_other,
            ),
        )

        TakeoverPhase.HeldHere -> stringResource(R.string.feature_chat_takeover_held_here)

        is TakeoverPhase.Failed -> problemText(phase.problem, takeover.owner)

        is TakeoverPhase.Unavailable -> problemText(phase.problem, takeover.owner)
    }
}

@Composable
private fun problemText(problem: TakeoverProblem, owner: TakeoverOwner): String = when (problem) {
    TakeoverProblem.StillReplying -> stringResource(
        when (owner) {
            TakeoverOwner.Desktop -> R.string.feature_chat_takeover_replying_desktop
            TakeoverOwner.Terminal -> R.string.feature_chat_takeover_replying_terminal
            TakeoverOwner.Other -> R.string.feature_chat_takeover_replying_other
        },
    )

    TakeoverProblem.PluginMissing -> stringResource(R.string.feature_chat_takeover_plugin_missing)

    TakeoverProblem.Unsupported -> stringResource(R.string.feature_chat_takeover_unsupported)

    is TakeoverProblem.Error -> stringResource(R.string.feature_chat_takeover_failed, failureText(problem.failure))
}
