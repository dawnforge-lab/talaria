package lab.dawnforge.hermes.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository

/** The profile Hermes' settings are for: the active one. */
@HiltViewModel
internal class SettingsViewModel @Inject constructor(dashboards: DashboardRepository) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    val profile: StateFlow<String?> = dashboards.activeDashboardId.filterNotNull()
        .flatMapLatest { dashboards.activeProfile(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenPage: (HermesPage) -> Unit,
    onOpenGateway: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenPhone: () -> Unit,
    onOpenLocation: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenDashboards: () -> Unit,
    onOpenPlugin: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenCalls: () -> Unit,
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_settings_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                GroupHeader(
                    stringResource(R.string.feature_settings_group_hermes),
                    profile?.let { stringResource(R.string.feature_settings_profile, it) },
                )
            }
            items(HermesPage.entries) { page ->
                Row(stringResource(page.title), stringResource(page.detail)) { onOpenPage(page) }
            }
            item {
                Row(
                    stringResource(R.string.feature_settings_capabilities),
                    stringResource(R.string.feature_settings_capabilities_detail),
                    onOpenCapabilities,
                )
            }
            item { GroupHeader(stringResource(R.string.feature_settings_group_server)) }
            item {
                Row(
                    stringResource(R.string.feature_settings_gateway),
                    stringResource(R.string.feature_settings_gateway_detail),
                    onOpenGateway,
                )
            }
            item {
                Row(
                    stringResource(R.string.feature_settings_dashboards),
                    stringResource(R.string.feature_settings_dashboards_detail),
                    onOpenDashboards,
                )
            }
            item {
                Row(
                    stringResource(R.string.feature_settings_push),
                    stringResource(R.string.feature_settings_push_detail),
                    onOpenPlugin,
                )
            }
            item { GroupHeader(stringResource(R.string.feature_settings_group_phone_data)) }
            item {
                Row(
                    stringResource(R.string.feature_settings_phone),
                    stringResource(R.string.feature_settings_phone_detail),
                    onOpenPhone,
                )
            }
            item {
                Row(
                    stringResource(R.string.feature_settings_location),
                    stringResource(R.string.feature_settings_location_detail),
                    onOpenLocation,
                )
            }
            item {
                Row(
                    stringResource(R.string.feature_settings_health),
                    stringResource(R.string.feature_settings_health_detail),
                    onOpenHealth,
                )
            }
            item { GroupHeader(stringResource(R.string.feature_settings_group_app)) }
            item {
                Row(
                    stringResource(R.string.feature_settings_appearance),
                    stringResource(R.string.feature_settings_appearance_detail),
                    onOpenAppearance,
                )
            }
            item {
                Row(
                    stringResource(R.string.feature_settings_profiles),
                    stringResource(R.string.feature_settings_profiles_detail),
                    onOpenProfiles,
                )
            }
            item {
                Row(
                    stringResource(R.string.feature_settings_voice),
                    stringResource(R.string.feature_settings_voice_detail),
                    onOpenVoice,
                )
            }
            item {
                Row(
                    stringResource(R.string.feature_settings_calls),
                    stringResource(R.string.feature_settings_calls_detail),
                    onOpenCalls,
                )
            }
            item {
                Row(
                    stringResource(R.string.feature_settings_notifications),
                    stringResource(R.string.feature_settings_notifications_detail),
                    onOpenNotifications,
                )
            }
            item {
                Text(
                    stringResource(R.string.feature_settings_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String, detail: String? = null) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        detail?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Row(title: String, detail: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
