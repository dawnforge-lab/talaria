package lab.dawnforge.hermes.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import lab.dawnforge.hermes.R
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.designsystem.theme.HermesTheme

/**
 * How the app uses health data, as Health Connect asks every app that reads it to say: from its
 * permission screen ("Read privacy policy") and from Android's permission usage page.
 */
@AndroidEntryPoint
class HealthPrivacyActivity : ComponentActivity() {
    @Inject
    lateinit var appSettings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val preferences by appSettings.preferences.collectAsStateWithLifecycle(initialValue = null)
            val chosen = preferences ?: return@setContent
            HermesTheme(theme = chosen.theme, accent = chosen.accent) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(
                            rememberScrollState(),
                        ).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            stringResource(R.string.app_health_privacy_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        listOf(
                            R.string.app_health_privacy_what,
                            R.string.app_health_privacy_where,
                            R.string.app_health_privacy_reads,
                            R.string.app_health_privacy_never,
                            R.string.app_health_privacy_stop,
                        ).forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyLarge) }
                        TextButton(onClick = ::finish) { Text(stringResource(R.string.app_health_privacy_close)) }
                    }
                }
            }
        }
    }
}
