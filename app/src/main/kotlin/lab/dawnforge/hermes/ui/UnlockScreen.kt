package lab.dawnforge.hermes.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import lab.dawnforge.hermes.R
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.feature.connect.PhoneUnlock

/**
 * The app before a locked dashboard shows, as iOS asks Face ID at launch: the phone's unlock
 * opens it, asked at once and again on Unlock. Signing out of the dashboard instead drops its
 * saved sign-in, and the lock with it.
 */
@Composable
internal fun UnlockScreen(dashboard: DashboardId, onUnlocked: () -> Unit, onSignOut: () -> Unit) {
    val activity = LocalActivity.current as? FragmentActivity
    val title = stringResource(R.string.app_unlock_title)
    val subtitle = stringResource(R.string.app_unlock_subtitle)
    fun ask() {
        activity?.let { PhoneUnlock.ask(it, title, subtitle) { ok -> if (ok) onUnlocked() } }
    }
    LaunchedEffect(dashboard) { ask() }
    // Back leaves the app rather than reaching what the lock covers.
    BackHandler { activity?.finish() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.app_unlock_body),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = ::ask) { Text(stringResource(R.string.app_unlock_button)) }
            TextButton(onClick = onSignOut) { Text(stringResource(R.string.app_unlock_sign_out)) }
        }
    }
}
