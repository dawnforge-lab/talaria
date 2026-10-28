package lab.dawnforge.hermes

import android.app.PendingIntent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.designsystem.theme.HermesTheme
import lab.dawnforge.hermes.core.push.calls.HermesCallService

/**
 * The call screen of a call from Hermes, shown over the lock screen while it rings: who's
 * calling, what it's about, Answer and Decline. It closes when the call stops ringing.
 */
class IncomingCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        val caller = intent.getStringExtra(HermesCallService.EXTRA_CALLER).orEmpty()
        val summary = intent.getStringExtra(HermesCallService.EXTRA_SUMMARY).orEmpty()
        val answer = IntentCompat.getParcelableExtra(intent, HermesCallService.EXTRA_ANSWER, PendingIntent::class.java)
        val decline = IntentCompat.getParcelableExtra(
            intent,
            HermesCallService.EXTRA_DECLINE,
            PendingIntent::class.java,
        )
        // Answered, turned down or rung out elsewhere: this screen goes too.
        lifecycleScope.launch {
            HermesCallService.ringingNow.first { it }
            HermesCallService.ringingNow.first { !it }
            finish()
        }
        setContent {
            HermesTheme {
                CallScreen(
                    caller = caller,
                    summary = summary,
                    onAnswer = {
                        runCatching { answer?.send() }
                        finish()
                    },
                    onDecline = {
                        runCatching { decline?.send() }
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun CallScreen(caller: String, summary: String, onAnswer: () -> Unit, onDecline: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.app_incoming_call),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(caller, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            if (summary.isNotBlank()) {
                Text(summary, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.weight(2f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onDecline,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.app_call_decline)) }
                Button(onClick = onAnswer, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_call_answer))
                }
            }
        }
    }
}
