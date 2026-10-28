package lab.dawnforge.hermes.feature.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lab.dawnforge.hermes.core.net.ConnectLink
import lab.dawnforge.hermes.core.net.ProxyHeaderRules
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.feature.connect.setup.SetupStart
import lab.dawnforge.hermes.feature.connect.setup.troubleshootStart

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddressScreen(
    viewModel: ConnectViewModel,
    onContinueToSignIn: () -> Unit,
    onScan: () -> Unit,
    onSetup: (SetupStart, address: String?) -> Unit,
    onSignedIn: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EventEffect(viewModel.events) { event ->
        when (event) {
            ConnectEvent.ShowSignIn -> onContinueToSignIn()

            ConnectEvent.SignedIn -> onSignedIn()

            // The browser sign-in starts and ends on the sign-in screen.
            is ConnectEvent.OpenBrowser, ConnectEvent.ShowWebSignIn -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_connect_add_title)) },
                actions = {
                    if (onCancel != null) {
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.feature_connect_cancel)) }
                    }
                },
            )
        },
    ) { padding ->
        FormColumn(Modifier.padding(padding)) {
            val link = state.pendingLink
            if (link != null) {
                LinkCard(
                    link = link,
                    loopbackOnly = state.linkIsLoopbackOnly,
                    busy = state.busy,
                    error = state.addressError?.let { failureText(it) },
                    onSignIn = viewModel::checkAddress,
                    onDismiss = viewModel::dismissLink,
                )
                return@FormColumn
            }

            Text(stringResource(R.string.feature_connect_add_intro), style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = state.address,
                onValueChange = viewModel::onAddressChange,
                label = { Text(stringResource(R.string.feature_connect_address_label)) },
                placeholder = { Text(stringResource(R.string.feature_connect_address_placeholder)) },
                singleLine = true,
                isError = state.addressError != null,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onGo = { viewModel.checkAddress() }),
                modifier = Modifier.fillMaxWidth(),
            )
            state.addressError?.let { failure ->
                ErrorText(failureText(failure))
                failure.troubleshootStart()?.let { start ->
                    OutlinedButton(onClick = { onSetup(start, state.address.ifBlank { null }) }) {
                        Text(stringResource(R.string.feature_connect_setup_troubleshoot))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::checkAddress, enabled = !state.busy && state.address.isNotBlank()) {
                    if (state.checkingAddress) {
                        ButtonProgress()
                    } else {
                        Text(
                            stringResource(R.string.feature_connect_continue),
                        )
                    }
                }
                OutlinedButton(onClick = onScan, enabled = !state.busy) {
                    Text(stringResource(R.string.feature_connect_scan))
                }
            }

            TextButton(onClick = {
                onSetup(SetupStart.Dashboard, state.address.ifBlank { null })
            }, enabled = !state.busy) {
                Text(stringResource(R.string.feature_connect_setup_help))
            }

            TextButton(onClick = viewModel::onToggleCloudflare) {
                val label = if (state.cloudflareShown) {
                    R.string.feature_connect_cloudflare_hide
                } else {
                    R.string.feature_connect_cloudflare_show
                }
                Text(stringResource(label))
            }
            if (state.cloudflareShown) {
                CloudflareFields(state, viewModel)
            }

            TextButton(onClick = viewModel::onToggleHeaders) {
                val count = ProxyHeaderRules.sendable(state.headers).size
                Text(
                    if (state.headersShown) {
                        stringResource(R.string.feature_connect_headers_hide_section)
                    } else if (count > 0) {
                        stringResource(R.string.feature_connect_headers_show_count, count)
                    } else {
                        stringResource(R.string.feature_connect_headers_show_section)
                    },
                )
            }
            if (state.headersShown) {
                if (state.headersNeedHttps) ErrorText(stringResource(R.string.feature_connect_headers_https_only))
                ProxyHeadersEditor(state.headers, viewModel::onHeadersChange, enabled = !state.busy)
            }
        }
    }
}

@Composable
private fun CloudflareFields(state: ConnectUiState, viewModel: ConnectViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.feature_connect_cloudflare_note), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = state.cloudflareClientId,
            onValueChange = viewModel::onCloudflareClientIdChange,
            label = { Text(stringResource(R.string.feature_connect_cloudflare_client_id)) },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.cloudflareClientSecret,
            onValueChange = viewModel::onCloudflareClientSecretChange,
            label = { Text(stringResource(R.string.feature_connect_cloudflare_client_secret)) },
            singleLine = true,
            enabled = !state.busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Confirms a `talaria://connect` link before anything is sent: nothing connects until "Sign in". */
@Composable
private fun LinkCard(
    link: ConnectLink,
    loopbackOnly: Boolean,
    busy: Boolean,
    error: String?,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.feature_connect_link_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.feature_connect_link_address, link.address))
            link.username?.let { Text(stringResource(R.string.feature_connect_link_username, it)) }
            if (link.password != null) {
                Text(
                    stringResource(R.string.feature_connect_link_has_password),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (loopbackOnly) {
                Text(
                    stringResource(R.string.feature_connect_link_loopback),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            error?.let { ErrorText(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSignIn, enabled = !busy) {
                    if (busy) ButtonProgress() else Text(stringResource(R.string.feature_connect_link_sign_in))
                }
                TextButton(onClick = onDismiss, enabled = !busy) {
                    Text(stringResource(R.string.feature_connect_link_dismiss))
                }
            }
        }
    }
}
