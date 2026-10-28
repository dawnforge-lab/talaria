package lab.dawnforge.hermes.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.R
import lab.dawnforge.hermes.core.data.cache.ChatCache
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.designsystem.theme.HermesTheme
import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.model.SessionSummary

/** A widget's settings, as iOS's: its dashboard, the chat its title opens, and hiding that title. */
@AndroidEntryPoint
class LauncherWidgetConfigActivity : ComponentActivity() {
    @Inject
    lateinit var dashboards: DashboardRepository

    @Inject
    lateinit var cache: ChatCache

    @Inject
    lateinit var appSettings: AppSettings

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Backing out leaves the widget as it was (or, when adding it, with its defaults).
        setResult(RESULT_CANCELED, result())
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            val preferences by appSettings.preferences.collectAsStateWithLifecycle(initialValue = null)
            preferences?.let {
                HermesTheme(theme = it.theme, accent = it.accent) { Settings() }
            }
        }
    }

    private fun result() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Settings() {
        val scope = rememberCoroutineScope()
        var saved by remember { mutableStateOf(emptyList<Dashboard>()) }
        var dashboard by remember { mutableStateOf<Dashboard?>(null) }
        var profile by remember { mutableStateOf("") }
        var chats by remember { mutableStateOf(emptyList<SessionSummary>()) }
        var chat by remember { mutableStateOf<String?>(null) }
        var hideTitle by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            saved = dashboards.dashboards.first()
            val active = dashboards.activeDashboardId.first()
            dashboard = saved.firstOrNull { it.id == active } ?: saved.firstOrNull()
        }
        LaunchedEffect(dashboard) {
            val current = dashboard ?: return@LaunchedEffect
            profile = dashboards.activeProfile(current.id).first()
            chats = LauncherWidget.recentChats(cache, current.id, profile)
            chat = null
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_widget_settings)) },
                    actions = {
                        TextButton(onClick = { scope.launch { save(dashboard, profile, chat, hideTitle) } }) {
                            Text(stringResource(R.string.app_widget_save))
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                if (saved.size > 1) {
                    item { Heading(stringResource(R.string.app_share_workspace)) }
                    items(saved, key = { "d-" + it.id.value }) { item ->
                        Choice(item.url.host, item == dashboard) { dashboard = item }
                    }
                }
                item { Heading(stringResource(R.string.app_share_chat)) }
                item { Choice(stringResource(R.string.app_widget_last_chat), chat == null) { chat = null } }
                items(chats, key = { "c-" + it.id }) { item ->
                    Choice(item.displayTitle, chat == item.id) { chat = item.id }
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.app_widget_hide_title)) },
                        trailingContent = { Switch(checked = hideTitle, onCheckedChange = { hideTitle = it }) },
                        modifier = Modifier.clickable { hideTitle = !hideTitle },
                    )
                }
            }
        }
    }

    private suspend fun save(dashboard: Dashboard?, profile: String, chat: String?, hideTitle: Boolean) {
        val manager = GlanceAppWidgetManager(this)
        val id = manager.getGlanceIdBy(widgetId)
        updateAppWidgetState(this, id) { prefs ->
            prefs[LauncherWidget.HIDE_TITLE] = hideTitle
            // The last chat follows whatever the app shows; a chosen one stays with its workspace.
            if (dashboard != null && chat != null) {
                prefs[LauncherWidget.DASHBOARD] = dashboard.id.value
                prefs[LauncherWidget.PROFILE] = profile
                prefs[LauncherWidget.SESSION] = chat
            } else {
                prefs.remove(LauncherWidget.DASHBOARD)
                prefs.remove(LauncherWidget.PROFILE)
                prefs.remove(LauncherWidget.SESSION)
            }
        }
        LauncherWidget().update(this, id)
        setResult(RESULT_OK, result())
        finish()
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Choice(title: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { RadioButton(selected = selected, onClick = onClick) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
