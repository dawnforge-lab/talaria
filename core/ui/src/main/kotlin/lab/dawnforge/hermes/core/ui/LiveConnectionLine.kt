package lab.dawnforge.hermes.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import lab.dawnforge.hermes.core.gateway.CloseReason
import lab.dawnforge.hermes.core.gateway.GatewayFailure
import lab.dawnforge.hermes.core.gateway.GatewayState
import lab.dawnforge.hermes.core.gateway.HandshakeFailure

/**
 * One line on the state of the dashboard's live chat connection.
 *
 * With [onRepair], a failed connection offers Repair connection, as iOS does once a failure has
 * shown. While [held] (the user is repairing it, or closed a repair) it says so, with Repair
 * connection and [onReconnect] to try again anyway.
 */
@Composable
fun LiveConnectionLine(
    state: GatewayState,
    held: GatewayState? = null,
    onRepair: (() -> Unit)? = null,
    onReconnect: (() -> Unit)? = null,
) {
    if (held != null) {
        Column {
            Text(
                stringResource(R.string.core_ui_live_held, heldReason(held)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onRepair?.let { TextButton(onClick = it) { Text(stringResource(R.string.core_ui_live_repair)) } }
                onReconnect?.let { TextButton(onClick = it) { Text(stringResource(R.string.core_ui_live_reconnect)) } }
            }
        }
        return
    }
    val failed = state is GatewayState.Waiting || state is GatewayState.SignInRequired
    val color = when {
        state is GatewayState.Connected -> MaterialTheme.colorScheme.primary
        failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state is GatewayState.Connecting) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(liveText(state), style = MaterialTheme.typography.bodyMedium, color = color)
        }
        if (failed && onRepair != null) {
            TextButton(onClick = onRepair) { Text(stringResource(R.string.core_ui_live_repair)) }
        }
    }
}

/** Why a held connection stopped, without the retry countdown it no longer has. */
@Composable
private fun heldReason(state: GatewayState): String = when (state) {
    is GatewayState.Waiting -> failureReason(state.failure)
    is GatewayState.SignInRequired -> stringResource(R.string.core_ui_live_sign_in)
    else -> stringResource(R.string.core_ui_live_stopped)
}

@Composable
private fun liveText(state: GatewayState): String = when (state) {
    is GatewayState.Connected -> stringResource(R.string.core_ui_live_connected, timeOfDay(state.sinceMs))

    is GatewayState.Connecting -> stringResource(R.string.core_ui_live_connecting)

    is GatewayState.Waiting -> {
        val now by produceState(System.currentTimeMillis(), state.retryAtMs) {
            while (true) {
                value = System.currentTimeMillis()
                delay(TICK_MS)
            }
        }
        val seconds = ((state.retryAtMs - now + 999) / 1000).coerceAtLeast(0).toInt()
        stringResource(R.string.core_ui_live_waiting, failureReason(state.failure), seconds)
    }

    is GatewayState.SignInRequired -> stringResource(R.string.core_ui_live_sign_in)

    GatewayState.Stopped -> stringResource(R.string.core_ui_live_stopped)
}

@Composable
private fun failureReason(failure: GatewayFailure): String = when (failure) {
    is GatewayFailure.NotReachable -> failureText(failure.failure)

    is GatewayFailure.Ticket -> failureText(failure.failure)

    is GatewayFailure.Handshake -> when (val handshake = failure.failure) {
        is HandshakeFailure.Rejected -> stringResource(R.string.core_ui_live_reason_refused)

        HandshakeFailure.SignInRequired -> stringResource(R.string.core_ui_live_sign_in)

        is HandshakeFailure.Unavailable -> stringResource(R.string.core_ui_error_unavailable, handshake.status)

        is HandshakeFailure.UnexpectedStatus -> stringResource(
            R.string.core_ui_error_unexpected,
            handshake.status,
        )

        is HandshakeFailure.Network -> failureText(handshake.failure)

        HandshakeFailure.Timeout -> stringResource(R.string.core_ui_error_timeout)
    }

    is GatewayFailure.Lost -> when (val reason = failure.reason) {
        CloseReason.KeepaliveTimeout -> stringResource(R.string.core_ui_live_reason_silent)
        is CloseReason.Remote -> stringResource(R.string.core_ui_live_reason_closed)
        is CloseReason.Failure -> failureText(reason.failure)
        CloseReason.Local -> stringResource(R.string.core_ui_live_reason_closed)
    }
}

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun timeOfDay(epochMs: Long): String =
    timeFormat.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private const val TICK_MS = 1_000L
