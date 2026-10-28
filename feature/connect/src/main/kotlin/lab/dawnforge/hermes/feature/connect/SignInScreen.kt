package lab.dawnforge.hermes.feature.connect

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lab.dawnforge.hermes.core.model.SignInMethod
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.feature.connect.setup.SetupStart
import lab.dawnforge.hermes.feature.connect.setup.troubleshootStart

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SignInScreen(
    viewModel: ConnectViewModel,
    onSignedIn: () -> Unit,
    onBack: () -> Unit,
    onTroubleshoot: (SetupStart, address: String?) -> Unit,
    onWebSignIn: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    EventEffect(viewModel.events) { event ->
        when (event) {
            ConnectEvent.SignedIn -> onSignedIn()
            is ConnectEvent.OpenBrowser -> openCustomTab(context, event.url)
            ConnectEvent.ShowWebSignIn -> onWebSignIn()
            ConnectEvent.ShowSignIn -> Unit
        }
    }
    // Collected while the screen is behind the Custom Tab, which is exactly when it is needed.
    LaunchedEffect(viewModel) { viewModel.returnFromBrowser.collect { returnFromBrowser(context) } }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val target = state.target
    val passwordForm = target?.method is SignInMethod.Password

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_connect_sign_in_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack, enabled = !state.signingIn) {
                        Text(stringResource(R.string.feature_connect_back))
                    }
                },
            )
        },
    ) { padding ->
        FormColumn(Modifier.padding(padding)) {
            if (target != null) {
                Text(
                    stringResource(R.string.feature_connect_sign_in_to, target.url.toString()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.signingInWithBrowser) {
                BrowserWaiting(onCancel = viewModel::cancelBrowserSignIn)
                state.signInError?.let { ErrorText(failureText(it)) }
                return@FormColumn
            }
            if (target?.method == SignInMethod.Browser) {
                Text(stringResource(R.string.feature_connect_web_intro), style = MaterialTheme.typography.bodyMedium)
                state.signInError?.let { ErrorText(failureText(it)) }
                Button(onClick = viewModel::openWebSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feature_connect_web_open))
                }
                return@FormColumn
            }
            if (!passwordForm) {
                Text(
                    stringResource(R.string.feature_connect_browser_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.signInError?.let { ErrorText(failureText(it)) }
                Button(onClick = viewModel::signInWithBrowser, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feature_connect_browser_continue))
                }
                return@FormColumn
            }
            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text(stringResource(R.string.feature_connect_username)) },
                singleLine = true,
                enabled = !state.signingIn,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentType = ContentType.Username },
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text(stringResource(R.string.feature_connect_password)) },
                singleLine = true,
                enabled = !state.signingIn,
                isError = state.signInError != null,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        val label = if (passwordVisible) {
                            R.string.feature_connect_password_hide
                        } else {
                            R.string.feature_connect_password_show
                        }
                        Text(stringResource(label))
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { viewModel.signIn() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentType = ContentType.Password },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = state.savePassword,
                        enabled = !state.signingIn,
                        role = Role.Switch,
                        onValueChange = viewModel::onSavePasswordChange,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.feature_connect_save_password))
                    Text(
                        stringResource(R.string.feature_connect_save_password_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.savePassword, onCheckedChange = null, enabled = !state.signingIn)
            }

            if (state.biometricsAvailable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = state.unlockWithBiometrics && state.savePassword,
                            enabled = !state.signingIn && state.savePassword,
                            role = Role.Switch,
                            onValueChange = viewModel::onUnlockChange,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.feature_connect_unlock))
                        Text(
                            stringResource(R.string.feature_connect_unlock_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.unlockWithBiometrics && state.savePassword,
                        onCheckedChange = null,
                        enabled = !state.signingIn && state.savePassword,
                    )
                }
            }

            state.signInError?.let { failure ->
                ErrorText(failureText(failure))
                failure.troubleshootStart()?.let { start ->
                    OutlinedButton(onClick = { onTroubleshoot(start, target.url.toString()) }) {
                        Text(stringResource(R.string.feature_connect_setup_troubleshoot))
                    }
                }
            }

            Button(
                onClick = viewModel::signIn,
                enabled = !state.signingIn && state.username.isNotBlank() && state.password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.signingIn) ButtonProgress() else Text(stringResource(R.string.feature_connect_sign_in))
            }
            if (target.browserAvailable) {
                Text(
                    stringResource(R.string.feature_connect_browser_or),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                OutlinedButton(
                    onClick = viewModel::signInWithBrowser,
                    enabled = !state.signingIn,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.feature_connect_browser_instead)) }
            }
        }
    }
}

@Composable
private fun BrowserWaiting(onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.feature_connect_browser_waiting), style = MaterialTheme.typography.bodyMedium)
    }
    TextButton(onClick = onCancel) { Text(stringResource(R.string.feature_connect_browser_cancel)) }
}

/** Hermes' sign-in page in a Custom Tab: the user's own browser, with its saved passwords and sessions. */
private fun openCustomTab(context: Context, url: String) {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url.toUri())
}

/**
 * Back to the app from the Custom Tab, which stays open on Hermes' "signed in" page otherwise.
 * Relaunching the app's own activity with CLEAR_TOP closes the tab above it in the task.
 */
private fun returnFromBrowser(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
}
