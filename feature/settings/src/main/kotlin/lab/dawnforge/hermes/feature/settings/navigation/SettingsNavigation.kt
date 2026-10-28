package lab.dawnforge.hermes.feature.settings.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import lab.dawnforge.hermes.feature.settings.AppearanceScreen
import lab.dawnforge.hermes.feature.settings.CallsSettingsScreen
import lab.dawnforge.hermes.feature.settings.CapabilitiesScreen
import lab.dawnforge.hermes.feature.settings.CatalogSkillScreen
import lab.dawnforge.hermes.feature.settings.ConnectorScreen
import lab.dawnforge.hermes.feature.settings.GatewayScreen
import lab.dawnforge.hermes.feature.settings.HealthScreen
import lab.dawnforge.hermes.feature.settings.HermesChangesViewModel
import lab.dawnforge.hermes.feature.settings.HermesPageScreen
import lab.dawnforge.hermes.feature.settings.LocationScreen
import lab.dawnforge.hermes.feature.settings.LogsScreen
import lab.dawnforge.hermes.feature.settings.PhoneDataScreen
import lab.dawnforge.hermes.feature.settings.PushPluginScreen
import lab.dawnforge.hermes.feature.settings.SettingsScreen
import lab.dawnforge.hermes.feature.settings.SkillScreen
import lab.dawnforge.hermes.feature.settings.ToolsetScreen
import lab.dawnforge.hermes.feature.settings.VoiceSettingsScreen

/** The settings home: Hermes' pages for the active profile, and the phone's own. */
@Serializable
data object SettingsRoute

/** One Hermes settings page, by its [HermesPage][lab.dawnforge.hermes.feature.settings.HermesPage] name. */
@Serializable
data class HermesPageRoute(val page: String)

/** Hermes' gateway: its status, connectors and Restart Gateway. */
@Serializable
data object GatewayRoute

/** The app's theme and accent. */
@Serializable
data object AppearanceRoute

/** Skills, tools, connectors and plugins of the active profile. */
@Serializable
data object CapabilitiesRoute

/** An installed skill. */
@Serializable
data class SkillRoute(val name: String)

/** A skill in Hermes' official catalog, to read and install. */
@Serializable
data class CatalogSkillRoute(val identifier: String, val name: String, val category: String)

/** A toolset: its tools, backends, keys, and where the terminal runs. */
@Serializable
data class ToolsetRoute(val name: String)

/** An MCP server: its state, its tools, and signing in to it. */
@Serializable
data class ConnectorRoute(val name: String)

/** Hermes' recent log lines. */
@Serializable
data object LogsRoute

/** The host's push plugin. */
@Serializable
data object PushPluginRoute

/** Voice: dictation, read aloud, the voice conversation, and what Hermes may change. */
@Serializable
data object VoiceRoute

/** Calls from Hermes: when it may phone the user, and how a call rings. */
@Serializable
data object CallsRoute

/** Calendar & phone: what of them goes to Hermes. */
@Serializable
data object PhoneDataRoute

/** Location: where the phone is, shared with Hermes. */
@Serializable
data object LocationRoute

/** Health: health data from Health Connect, shared with Hermes. */
@Serializable
data object HealthRoute

fun NavController.navigateToSettings() = navigate(SettingsRoute) { launchSingleTop = true }

fun NavController.navigateToPushPlugin() = navigate(PushPluginRoute) { launchSingleTop = true }

/**
 * The Settings page a reply's `talaria://settings/<page>` link names.
 */
fun NavController.navigateToSettingsPage(page: String) {
    when (page) {
        "calls" -> navigate(CallsRoute) { launchSingleTop = true }
        "voice" -> navigate(VoiceRoute) { launchSingleTop = true }
        "plugin" -> navigateToPushPlugin()
        "location" -> navigate(LocationRoute) { launchSingleTop = true }
        "phone" -> navigate(PhoneDataRoute) { launchSingleTop = true }
        "health" -> navigate(HealthRoute) { launchSingleTop = true }
        else -> navigateToSettings()
    }
}

/** The settings screens. The phone's notification and dashboard screens live in other features. */
fun NavGraphBuilder.settingsScreens(
    navController: NavController,
    onOpenNotifications: () -> Unit,
    onOpenDashboards: () -> Unit,
    onOpenProfiles: () -> Unit,
    /** A chat just started in the profile ([runtimeId]), with [text] ready to send. */
    onNewChat: (profile: String, runtimeId: String, text: String) -> Unit,
) {
    composable<SettingsRoute> {
        SettingsScreen(
            viewModel = hiltViewModel(),
            onBack = { navController.popBackStack() },
            onOpenPage = { navController.navigate(HermesPageRoute(it.name)) },
            onOpenGateway = { navController.navigate(GatewayRoute) },
            onOpenAppearance = { navController.navigate(AppearanceRoute) },
            onOpenProfiles = onOpenProfiles,
            onOpenPhone = { navController.navigate(PhoneDataRoute) },
            onOpenLocation = { navController.navigate(LocationRoute) },
            onOpenHealth = { navController.navigate(HealthRoute) },
            onOpenCapabilities = { navController.navigate(CapabilitiesRoute) },
            onOpenNotifications = onOpenNotifications,
            onOpenDashboards = onOpenDashboards,
            onOpenPlugin = { navController.navigate(PushPluginRoute) },
            onOpenVoice = { navController.navigate(VoiceRoute) },
            onOpenCalls = { navController.navigate(CallsRoute) },
        )
    }
    composable<PhoneDataRoute> {
        PhoneDataScreen(viewModel = hiltViewModel(), onBack = { navController.popBackStack() })
    }
    composable<LocationRoute> {
        LocationScreen(viewModel = hiltViewModel(), onBack = { navController.popBackStack() })
    }
    composable<HealthRoute> {
        HealthScreen(viewModel = hiltViewModel(), onBack = { navController.popBackStack() })
    }
    composable<CallsRoute> {
        CallsSettingsScreen(viewModel = hiltViewModel(), onBack = {
            navController.popBackStack()
        }, onNewChat = onNewChat)
    }
    composable<PushPluginRoute> {
        PushPluginScreen(viewModel = hiltViewModel(), onBack = { navController.popBackStack() })
    }
    composable<HermesPageRoute> {
        HermesPageScreen(viewModel = hiltViewModel(), phone = hiltViewModel(), onBack = {
            navController.popBackStack()
        })
    }
    composable<GatewayRoute> {
        GatewayScreen(
            viewModel = hiltViewModel(),
            onBack = { navController.popBackStack() },
            onOpenLogs = { navController.navigate(LogsRoute) },
        )
    }
    composable<CapabilitiesRoute> {
        CapabilitiesScreen(
            viewModel = hiltViewModel(),
            connectorsModel = hiltViewModel(),
            pluginsModel = hiltViewModel(),
            onBack = { navController.popBackStack() },
            onOpenSkill = { navController.navigate(SkillRoute(it)) },
            onOpenCatalogSkill = { navController.navigate(CatalogSkillRoute(it.identifier, it.name, it.category)) },
            onOpenToolset = { navController.navigate(ToolsetRoute(it)) },
            onOpenConnector = { navController.navigate(ConnectorRoute(it)) },
            onOpenGateway = { navController.navigate(GatewayRoute) },
        )
    }
    composable<SkillRoute> {
        SkillScreen(viewModel = hiltViewModel(), onBack = { navController.popBackStack() })
    }
    composable<CatalogSkillRoute> { entry ->
        CatalogSkillScreen(
            viewModel = hiltViewModel(),
            category = entry.toRoute<CatalogSkillRoute>().category,
            onBack = { navController.popBackStack() },
        )
    }
    composable<ConnectorRoute> {
        ConnectorScreen(viewModel = hiltViewModel(), onBack = { navController.popBackStack() })
    }
    composable<ToolsetRoute> {
        ToolsetScreen(viewModel = hiltViewModel(), onBack = { navController.popBackStack() })
    }
    composable<AppearanceRoute> {
        AppearanceScreen(viewModel = hiltViewModel(), onBack = { navController.popBackStack() })
    }
    composable<LogsRoute> {
        LogsScreen(viewModel = hiltViewModel(), onBack = { navController.popBackStack() })
    }
    composable<VoiceRoute> {
        val changes: HermesChangesViewModel = hiltViewModel()
        VoiceSettingsScreen(
            viewModel = hiltViewModel(),
            onBack = { navController.popBackStack() },
            onOpenToolset = { name -> navController.navigate(ToolsetRoute(name)) },
            onReviewChanges = { changes.openReview(true) },
        )
    }
}
