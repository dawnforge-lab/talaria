package lab.dawnforge.hermes.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.R
import lab.dawnforge.hermes.core.data.cache.ChatCache
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.share.ChatShortcuts
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.SessionSummary
import lab.dawnforge.hermes.launch.LaunchAction
import lab.dawnforge.hermes.launch.LauncherActivity

/** What one widget shows: its workspace, the chat its title opens, and whether to hide the title. */
data class WidgetView(
    val dashboard: String?,
    val dashboardLabel: String,
    val profile: String?,
    val chat: SessionSummary?,
    val hideTitle: Boolean,
)

/**
 * The home-screen launcher, as iOS's: the dashboard and profile, the last (or chosen) chat, and New
 * chat and Talk. It reads the chat list the app saved; the app tells it when that changes.
 */
class LauncherWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val settings = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val view = load(context, settings)
        provideContent { GlanceTheme { Content(view) } }
    }

    private suspend fun load(context: Context, settings: Preferences): WidgetView {
        val deps = EntryPointAccessors.fromApplication(context, WidgetDependencies::class.java)
        val dashboards = deps.dashboards()
        val saved = dashboards.dashboards.first()
        val chosen = settings[DASHBOARD]?.let { id -> saved.firstOrNull { it.id.value == id } }
        val dashboard = chosen ?: dashboards.activeDashboardId.first()?.let { id -> saved.firstOrNull { it.id == id } }
            ?: return WidgetView(null, "", null, null, settings[HIDE_TITLE] == true)
        val profile = settings[PROFILE]?.takeIf { chosen != null } ?: dashboards.activeProfile(dashboard.id).first()
        val chats = recentChats(deps.cache(), dashboard.id, profile)
        val session = settings[SESSION]?.takeIf { chosen != null }
        val chat = if (session != null) chats.firstOrNull { it.id == session } else chats.firstOrNull()
        return WidgetView(dashboard.id.value, dashboard.url.host, profile, chat, settings[HIDE_TITLE] == true)
    }

    @Composable
    private fun Content(view: WidgetView) {
        val context = LocalContext.current
        fun launch(action: LaunchAction) = actionStartActivity(LauncherActivity.intent(context, action))
        val title = when {
            view.dashboard == null -> context.getString(R.string.app_widget_open)
            view.chat == null -> context.getString(R.string.app_widget_open)
            view.hideTitle -> context.getString(R.string.app_widget_resume)
            else -> view.chat.displayTitle
        }
        val open = if (view.dashboard != null && view.profile != null && view.chat != null) {
            LaunchAction.Resume(view.dashboard, view.profile, view.chat.id)
        } else {
            LaunchAction.Open
        }
        Column(
            GlanceModifier.fillMaxSize().cornerRadius(20.dp).background(GlanceTheme.colors.widgetBackground)
                .padding(14.dp),
        ) {
            Text(
                listOf(view.dashboardLabel, view.profile.orEmpty()).filter(String::isNotEmpty).joinToString(" · ")
                    .ifEmpty { context.getString(R.string.app_widget_name) },
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            )
            Text(
                title,
                maxLines = 2,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp).clickable(launch(open)),
            )
            Spacer(GlanceModifier.defaultWeight())
            Row(GlanceModifier.fillMaxWidth()) {
                Button(
                    context.getString(R.string.app_widget_new_chat),
                    onClick = launch(LaunchAction.NewChat(view.dashboard, view.profile)),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Spacer(GlanceModifier.width(8.dp))
                Button(
                    context.getString(R.string.app_widget_talk),
                    onClick = launch(LaunchAction.Talk(view.dashboard, view.profile)),
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
        }
    }

    companion object {
        /** A chosen chat's workspace and id; none means the app's workspace and its last chat. */
        val DASHBOARD = stringPreferencesKey("dashboard")
        val PROFILE = stringPreferencesKey("profile")
        val SESSION = stringPreferencesKey("session")
        val HIDE_TITLE = booleanPreferencesKey("hide_title")

        private const val MAX_CHATS = 20

        /** The workspace's chats as last listed, as iOS offers them: most recent first, no scheduled runs or archived chats. */
        suspend fun recentChats(cache: ChatCache, dashboard: DashboardId, profile: String): List<SessionSummary> =
            cache.sessions(dashboard, profile, archived = false)
                .filter { it.source != "cron" }
                .sortedByDescending { it.lastActiveAtMs ?: 0L }
                .take(MAX_CHATS)
    }
}

class LauncherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LauncherWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // The app published its chats again: the widgets read them.
        if (intent.action == ChatShortcuts.ACTION_CHATS_CHANGED) refresh(context, goAsync())
    }

    private fun refresh(context: Context, pending: BroadcastReceiver.PendingResult) {
        scope.launch {
            try {
                LauncherWidget().updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetDependencies {
    fun dashboards(): DashboardRepository

    fun cache(): ChatCache
}
