package lab.dawnforge.hermes.alarm

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import lab.dawnforge.hermes.R
import lab.dawnforge.hermes.core.data.phonedata.alarms.AlarmBook
import lab.dawnforge.hermes.core.data.phonedata.alarms.AlarmReceiver
import lab.dawnforge.hermes.core.data.phonedata.alarms.PhoneAlarm
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.designsystem.theme.HermesTheme
import lab.dawnforge.hermes.core.model.ThemeChoice

/**
 * An alarm or timer from Hermes ringing, over the lock screen: its name, the time, and Stop, as
 * iOS's AlarmKit alert. It closes once the alarm is stopped.
 */
@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {
    @Inject
    lateinit var book: AlarmBook

    @Inject
    lateinit var appSettings: AppSettings

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
        val id = intent.getStringExtra(AlarmReceiver.EXTRA_ID)
        setContent {
            // Nothing until the settings are read, so the chosen theme never flashes the default.
            val preferences by appSettings.preferences.collectAsStateWithLifecycle(initialValue = null)
            val chosen = preferences ?: return@setContent
            val dark = when (chosen.theme) {
                ThemeChoice.System -> isSystemInDarkTheme()
                ThemeChoice.Light -> false
                ThemeChoice.Dark -> true
            }
            // The status bar's icons follow the app's theme, as in the app itself.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose {}
            }
            HermesTheme(theme = chosen.theme, accent = chosen.accent) {
                val alarm by produceState(
                    initialValue = null as PhoneAlarm?,
                    id,
                ) {
                    book.alarms.collect { list ->
                        val found = list.firstOrNull { it.id == id }
                        // Stopped (here, from the notification, or by Hermes): nothing left to show.
                        if (found == null || !found.alerting) finish()
                        value = found
                    }
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                    ) {
                        Text(
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()),
                            style = MaterialTheme.typography.displayLarge,
                        )
                        Text(
                            alarm?.label ?: stringResource(
                                if (alarm?.kind == "timer") R.string.app_alarm_timer else R.string.app_alarm_alarm,
                            ),
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            stringResource(R.string.app_alarm_from_hermes),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { stop(id) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.app_alarm_stop), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }

    private fun stop(id: String?) {
        if (id != null) {
            sendBroadcast(
                Intent(
                    this,
                    AlarmReceiver::class.java,
                ).setAction(AlarmReceiver.ACTION_STOP).putExtra(AlarmReceiver.EXTRA_ID, id),
            )
        }
        finish()
    }
}
