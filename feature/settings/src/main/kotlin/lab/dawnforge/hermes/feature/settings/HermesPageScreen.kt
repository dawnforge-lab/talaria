package lab.dawnforge.hermes.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lab.dawnforge.hermes.core.ui.failureText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HermesPageScreen(viewModel: HermesPageViewModel, phone: PhoneSettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by phone.preferences.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(state.page.title))
                        state.profile?.let {
                            Text(
                                stringResource(R.string.feature_settings_profile, it),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_settings_back))
                    }
                },
            )
        },
    ) { padding ->
        val failure = state.failure
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                failure != null && state.values.isEmpty() -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                ) {
                    Text(failureText(failure), color = MaterialTheme.colorScheme.error)
                    Button(onClick = viewModel::refresh) { Text(stringResource(R.string.feature_settings_retry)) }
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (state.page == HermesPage.Model) defaultModelCard(state, viewModel)
                    state.cards.forEach { card ->
                        item(key = "card-${card.title}") { CardHeader(stringResource(card.title)) }
                        items(card.fields, key = Field::key) { field ->
                            FieldRow(
                                field = field,
                                value = state.values[field.key],
                                choices = state.choices[field.key].orEmpty(),
                                save = state.saves[field.key],
                                onSet = { viewModel.set(field, it) },
                                onSetText = { viewModel.setText(field, it) },
                            )
                        }
                    }
                    if (state.page == HermesPage.Model) fallbacksCard(state, viewModel)
                    if (state.page == HermesPage.Memory) delegationCards(state, viewModel)
                    if (state.page == HermesPage.Chat) prefs?.let { phoneCard(it, phone::update) }
                    item(key = "end") { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}
