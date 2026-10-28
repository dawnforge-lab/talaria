package lab.dawnforge.hermes.ui

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.R
import lab.dawnforge.hermes.core.chat.SettingsLink
import lab.dawnforge.hermes.core.data.share.ChatShortcuts
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.push.HermesNotification
import lab.dawnforge.hermes.feature.bots.navigation.botsScreens
import lab.dawnforge.hermes.feature.bots.navigation.navigateToBots
import lab.dawnforge.hermes.feature.bots.navigation.navigateToProfiles
import lab.dawnforge.hermes.feature.bots.navigation.profilesScreen
import lab.dawnforge.hermes.feature.chat.PresentedConfirmHost
import lab.dawnforge.hermes.feature.chat.navigation.ChatRoute
import lab.dawnforge.hermes.feature.chat.navigation.chatScreen
import lab.dawnforge.hermes.feature.chat.navigation.navigateToArtifacts
import lab.dawnforge.hermes.feature.chat.navigation.navigateToChat
import lab.dawnforge.hermes.feature.chat.navigation.navigateToOtherChat
import lab.dawnforge.hermes.feature.connect.navigation.ConnectGraph
import lab.dawnforge.hermes.feature.connect.navigation.RepairRoute
import lab.dawnforge.hermes.feature.connect.navigation.connectGraph
import lab.dawnforge.hermes.feature.connect.navigation.dashboardsScreen
import lab.dawnforge.hermes.feature.connect.navigation.navigateToConnect
import lab.dawnforge.hermes.feature.connect.navigation.navigateToDashboards
import lab.dawnforge.hermes.feature.connect.navigation.repairScreen
import lab.dawnforge.hermes.feature.cron.navigation.cronScreens
import lab.dawnforge.hermes.feature.cron.navigation.navigateToCronJobs
import lab.dawnforge.hermes.feature.notifications.navigation.navigateToNotifications
import lab.dawnforge.hermes.feature.notifications.navigation.notificationsScreen
import lab.dawnforge.hermes.feature.sessions.ChatTarget
import lab.dawnforge.hermes.feature.sessions.navigation.ProjectsRoute
import lab.dawnforge.hermes.feature.sessions.navigation.SessionsRoute
import lab.dawnforge.hermes.feature.sessions.navigation.navigateToSessions
import lab.dawnforge.hermes.feature.sessions.navigation.projectsScreens
import lab.dawnforge.hermes.feature.sessions.navigation.sessionsScreen
import lab.dawnforge.hermes.feature.settings.HermesChangesHost
import lab.dawnforge.hermes.feature.settings.navigation.navigateToPushPlugin
import lab.dawnforge.hermes.feature.settings.navigation.navigateToSettings
import lab.dawnforge.hermes.feature.settings.navigation.navigateToSettingsPage
import lab.dawnforge.hermes.feature.settings.navigation.settingsScreens
import lab.dawnforge.hermes.feature.tasks.navigation.navigateToTasks
import lab.dawnforge.hermes.feature.tasks.navigation.tasksScreens
import lab.dawnforge.hermes.launch.LaunchAction

/** The app's root: the chat list once a dashboard is added, otherwise the add-a-dashboard flow. */
@Composable
fun HermesApp(viewModel: AppViewModel = hiltViewModel()) {
    val start by viewModel.start.collectAsStateWithLifecycle()
    // Decided once from the first real value: adding or removing dashboards later navigates
    // within the same graph instead of rebuilding it.
    var startAtSessions by rememberSaveable { mutableStateOf<Boolean?>(null) }
    if (startAtSessions == null && start != AppStart.Loading) startAtSessions = start == AppStart.HasDashboard

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        startAtSessions?.let { HermesNavHost(viewModel, startAtSessions = it) }
    }
}

@Composable
private fun HermesNavHost(viewModel: AppViewModel, startAtSessions: Boolean) {
    val navController = rememberNavController()
    // On a wide screen the chat list and the bots each keep a chat open beside them.
    val twoPane = isTwoPane()
    val chatsBeside = rememberNavController()
    val botsBeside = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // The start destination is chosen once; later changes navigate instead.
    val startDestination: Any = remember { if (startAtSessions) SessionsRoute else ConnectGraph() }

    val openRepair: () -> Unit = {
        viewModel.repairRoute()?.let { navController.navigate(it) { launchSingleTop = true } }
    }
    // A reply's link to a Settings page opens it over the chat, as on iOS.
    val openSettingsPage: (SettingsLink) -> Unit = { navController.navigateToSettingsPage(it.path) }

    NavHost(navController = navController, startDestination = startDestination) {
        connectGraph(
            navController = navController,
            onSignedIn = {
                navController.navigateToSessions {
                    popUpTo(navController.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            },
            onCancel = { navController.popBackStack<ConnectGraph>(inclusive = true) },
        )
        sessionsScreen(
            onOpenChat = { target ->
                val route = target.toRoute()
                if (twoPane) chatsBeside.openBeside(route) else navController.navigateToChat(route)
            },
            onOpenDashboards = { navController.navigateToDashboards { launchSingleTop = true } },
            onOpenNotifications = { navController.navigateToNotifications { launchSingleTop = true } },
            onOpenCron = { navController.navigateToCronJobs() },
            onOpenSettings = { navController.navigateToSettings() },
            onOpenTasks = { navController.navigateToTasks() },
            onOpenBots = { navController.navigateToBots() },
            onOpenArtifacts = { profile -> navController.navigateToArtifacts(profile) },
            onOpenProjects = { profile -> navController.navigate(ProjectsRoute(profile)) { launchSingleTop = true } },
            onOpenPlugin = { navController.navigateToPushPlugin() },
            onRepairConnection = openRepair,
            onOpenProfiles = { navController.navigateToProfiles() },
            frame = { list ->
                if (twoPane) {
                    ListBesideChat(
                        detail = chatsBeside,
                        onNewChat = {
                            scope.launch {
                                val started = viewModel.startNewChat(null, null)
                                if (started == null) {
                                    Toast.makeText(context, R.string.app_launch_not_started, Toast.LENGTH_LONG).show()
                                } else {
                                    chatsBeside.openBeside(ChatRoute(started.first, runtimeId = started.second))
                                }
                            }
                        },
                        onOpenChat = { chatsBeside.navigate(it) },
                        onRepairConnection = openRepair,
                        onOpenSettings = openSettingsPage,
                        list = list,
                    )
                } else {
                    list()
                }
            },
        )
        projectsScreens(navController) { target ->
            navController.navigateToChat(target.toRoute())
        }
        chatScreen(
            navController,
            onOpenChat = navController::navigateToOtherChat,
            onRepairConnection = openRepair,
            onOpenSettings = openSettingsPage,
        )
        profilesScreen(onBack = { navController.popBackStack() })
        repairScreen(
            onClose = { navController.popBackStack() },
            onSignIn = { address, username ->
                navController.navigateToConnect(ConnectGraph(address, username)) {
                    popUpTo<RepairRoute> { inclusive = true }
                }
            },
        )
        notificationsScreen(onBack = { navController.popBackStack() })
        tasksScreens(navController)
        botsScreens(
            navController,
            frame = { list ->
                if (twoPane) {
                    ListBesideChat(
                        detail = botsBeside,
                        onNewChat = null,
                        onOpenChat = { botsBeside.navigate(it) },
                        onRepairConnection = openRepair,
                        onOpenSettings = openSettingsPage,
                        list = list,
                    )
                } else {
                    list()
                }
            },
        ) { chat, label ->
            val route = ChatRoute(chat.profile, chat.sessionId, chat.runtimeId, label, botChat = true)
            if (twoPane) botsBeside.openBeside(route) else navController.navigateToChat(route)
        }
        settingsScreens(
            navController,
            onOpenNotifications = { navController.navigateToNotifications { launchSingleTop = true } },
            onOpenDashboards = { navController.navigateToDashboards { launchSingleTop = true } },
            onOpenProfiles = { navController.navigateToProfiles() },
            onNewChat = { profile, runtimeId, text ->
                navController.navigateToChat(ChatRoute(profile, runtimeId = runtimeId, draft = text))
            },
        )
        cronScreens(
            navController,
            // The chat list, now filtered to scheduled runs.
            onShowRuns = { navController.popBackStack<SessionsRoute>(inclusive = false) },
        ) { profile, sessionId, title ->
            navController.navigateToChat(ChatRoute(profile, sessionId, title = title))
        }
        dashboardsScreen(
            onBack = { navController.popBackStack() },
            onAddDashboard = { navController.navigateToConnect() },
            onSignInAgain = { address, username -> navController.navigateToConnect(ConnectGraph(address, username)) },
            onOpenRepair = { navController.navigate(it) { launchSingleTop = true } },
            onNoDashboards = {
                navController.navigateToConnect {
                    popUpTo(navController.graph.id) { inclusive = true }
                }
            },
        )
    }

    // A confirmation Hermes waits on shows over whatever is on screen; Edit opens its chat.
    PresentedConfirmHost(onEdit = { profile, sessionId -> navController.openForEdit(ChatRoute(profile, sessionId)) })
    // Changes Hermes asked of the phone's settings, and its Undo for those made without asking.
    HermesChangesHost()

    // A tapped notification opens its chat over the chat list, whatever was showing. One collector,
    // not an effect keyed on the target: taking the target clears it, which would cancel such an
    // effect while it is still making the dashboard active.
    LaunchedEffect(viewModel) {
        viewModel.pushInbox.target.filterNotNull().collect {
            val target = viewModel.pushInbox.take() ?: return@collect
            if (!viewModel.prepare(target)) return@collect
            navController.navigateToSessions {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
            target.sessionId?.let { navController.navigateToChat(ChatRoute(target.profile, it)) }
        }
    }

    // A link to a chat from another app opens like a tapped notification.
    LaunchedEffect(viewModel) {
        viewModel.sessionLinks.link.filterNotNull().collect {
            val link = viewModel.sessionLinks.take() ?: return@collect
            when (val opening = viewModel.prepare(link)) {
                LinkOpening.Unavailable ->
                    Toast.makeText(context, R.string.app_link_unavailable, Toast.LENGTH_LONG).show()

                LinkOpening.Gone -> Toast.makeText(context, R.string.app_link_gone, Toast.LENGTH_LONG).show()

                is LinkOpening.Open -> {
                    navController.navigateToSessions {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                    navController.navigateToChat(
                        ChatRoute(opening.profile, opening.sessionId, title = opening.title, botChat = opening.botChat),
                    )
                }
            }
        }
    }

    // A share from another app opens its chat with the share in the composer.
    LaunchedEffect(viewModel) {
        viewModel.sharedOpens.shared.filterNotNull().collect {
            val shared = viewModel.sharedOpens.take() ?: return@collect
            val target = HermesNotification.Target(DashboardId(shared.dashboard), shared.profile, shared.sessionId)
            if (!viewModel.prepare(target)) {
                Toast.makeText(context, R.string.app_link_unavailable, Toast.LENGTH_LONG).show()
                return@collect
            }
            val runtimeId = if (shared.sessionId == null) {
                viewModel.startChat(target.dashboardId, shared.profile) ?: run {
                    Toast.makeText(context, R.string.app_share_not_started, Toast.LENGTH_LONG).show()
                    return@collect
                }
            } else {
                null
            }
            navController.navigateToSessions {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
            navController.navigateToChat(
                ChatRoute(shared.profile, shared.sessionId, runtimeId, title = shared.title, shared = shared.draftId),
            )
        }
    }

    // The widget, a launcher shortcut or the tile: open a chat, or start one (talking, for Talk).
    LaunchedEffect(viewModel) {
        viewModel.launches.action.filterNotNull().collect {
            when (val action = viewModel.launches.take() ?: return@collect) {
                LaunchAction.Open -> Unit

                is LaunchAction.Resume ->
                    viewModel.sessionLinks.offer(ChatShortcuts.link(action.dashboard, action.profile, action.sessionId))

                is LaunchAction.NewChat, is LaunchAction.Talk -> {
                    val talk = action is LaunchAction.Talk
                    val started = when (action) {
                        is LaunchAction.Talk -> viewModel.startNewChat(action.dashboard, action.profile)
                        is LaunchAction.NewChat -> viewModel.startNewChat(action.dashboard, action.profile)
                    } ?: run {
                        Toast.makeText(context, R.string.app_launch_not_started, Toast.LENGTH_LONG).show()
                        return@collect
                    }
                    navController.navigateToSessions {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                    navController.navigateToChat(ChatRoute(started.first, runtimeId = started.second, talk = talk))
                }
            }
        }
    }

    val link by viewModel.linkInbox.link.collectAsStateWithLifecycle()
    LaunchedEffect(link) {
        if (link != null) {
            navController.navigateToConnect(ConnectGraph(fromLink = true)) {
                popUpTo<ConnectGraph> { inclusive = true }
            }
        }
    }

    // Hermes' calendar and alarm changes wait across the top of the app, as on iOS.
    PhoneChangeOverlay(hiltViewModel())

    // A dashboard set to open with the phone's unlock waits for it over everything, once per start.
    val locked by viewModel.locked.collectAsStateWithLifecycle()
    locked?.let { id ->
        UnlockScreen(
            dashboard = id,
            onUnlocked = { viewModel.unlocked(id) },
            onSignOut = {
                scope.launch {
                    val dashboard = viewModel.signOutLocked(id) ?: return@launch
                    navController.navigateToConnect(ConnectGraph(dashboard.url.toString(), dashboard.username))
                }
            },
        )
    }
}

/** The chat a confirmation came from: the one on screen takes the draft itself; any other opens over what shows. */
private fun NavController.openForEdit(route: ChatRoute) {
    val shown = currentBackStackEntry?.takeIf { it.destination.hasRoute<ChatRoute>() }?.toRoute<ChatRoute>()
    if (shown?.profile == route.profile && shown.sessionId == route.sessionId) return
    navigateToOtherChat(route)
}

/** The chat [ChatTarget] names, as the chat screen opens it. */
private fun ChatTarget.toRoute() = ChatRoute(
    profile = profile,
    sessionId = sessionId,
    runtimeId = runtimeId,
    title = title,
    reservedId = reservedId,
    newChatCwd = newChatCwd,
)
