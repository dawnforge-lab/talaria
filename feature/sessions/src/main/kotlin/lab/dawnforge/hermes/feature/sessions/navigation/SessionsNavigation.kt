package lab.dawnforge.hermes.feature.sessions.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import lab.dawnforge.hermes.feature.sessions.ChatTarget
import lab.dawnforge.hermes.feature.sessions.ProjectScreen
import lab.dawnforge.hermes.feature.sessions.ProjectsScreen
import lab.dawnforge.hermes.feature.sessions.SessionsScreen

/** The active profile's chats: the app's home once a dashboard is added. */
@Serializable
data object SessionsRoute

fun NavController.navigateToSessions(builder: NavOptionsBuilder.() -> Unit = {}) = navigate(SessionsRoute, builder)

/** The profile's projects. */
@Serializable
data class ProjectsRoute(val profile: String)

/** One project: its chats by folder. */
@Serializable
data class ProjectRoute(val profile: String, val projectId: String, val title: String)

/** The projects and a project's page; [onOpenChat] opens one of their chats. */
fun NavGraphBuilder.projectsScreens(navController: NavController, onOpenChat: (ChatTarget) -> Unit) {
    composable<ProjectsRoute> { entry ->
        val profile = entry.toRoute<ProjectsRoute>().profile
        ProjectsScreen(
            viewModel = hiltViewModel(),
            onBack = { navController.popBackStack() },
            onOpenProject = { project -> navController.navigate(ProjectRoute(profile, project.id, project.title)) },
        )
    }
    composable<ProjectRoute> { entry ->
        ProjectScreen(
            viewModel = hiltViewModel(),
            title = entry.toRoute<ProjectRoute>().title,
            onBack = { navController.popBackStack() },
            onOpenChat = onOpenChat,
        )
    }
}

fun NavGraphBuilder.sessionsScreen(
    onOpenChat: (ChatTarget) -> Unit,
    onOpenDashboards: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenCron: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenBots: () -> Unit,
    onOpenArtifacts: (profile: String) -> Unit,
    onOpenProjects: (profile: String) -> Unit,
    onOpenPlugin: () -> Unit,
    /** Repair connection, for the live connection that failed. */
    onRepairConnection: () -> Unit,
    /** The profile manager. */
    onOpenProfiles: () -> Unit,
    /** Lays the list out: on its own, or beside the open chat on a wide screen. */
    frame: @Composable (list: @Composable () -> Unit) -> Unit = { it() },
) {
    composable<SessionsRoute> {
        frame {
            SessionsScreen(
                viewModel = hiltViewModel(),
                onOpenChat = onOpenChat,
                onOpenDashboards = onOpenDashboards,
                onOpenNotifications = onOpenNotifications,
                onOpenCron = onOpenCron,
                onOpenSettings = onOpenSettings,
                onOpenTasks = onOpenTasks,
                onOpenBots = onOpenBots,
                onOpenArtifacts = onOpenArtifacts,
                onOpenProjects = onOpenProjects,
                onOpenPlugin = onOpenPlugin,
                onRepairConnection = onRepairConnection,
                onOpenProfiles = onOpenProfiles,
            )
        }
    }
}
