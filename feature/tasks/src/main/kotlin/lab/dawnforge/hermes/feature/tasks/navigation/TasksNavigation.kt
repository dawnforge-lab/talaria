package lab.dawnforge.hermes.feature.tasks.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import lab.dawnforge.hermes.feature.tasks.BoardScreen
import lab.dawnforge.hermes.feature.tasks.NewTaskScreen
import lab.dawnforge.hermes.feature.tasks.RoutingScreen
import lab.dawnforge.hermes.feature.tasks.TaskScreen
import lab.dawnforge.hermes.feature.tasks.WorkerLogScreen

/** Hermes' task board, shared by every profile. */
@Serializable
data object TasksRoute

/** One task of [board]. */
@Serializable
data class TaskRoute(val board: String, val taskId: String)

/** A task's worker log. */
@Serializable
data class WorkerLogRoute(val board: String, val taskId: String)

/** How Hermes routes tasks, and what each profile is for. */
@Serializable
data object RoutingRoute

/** A new task for [board], landing in [lane] (a lane key). */
@Serializable
data class NewTaskRoute(val board: String, val lane: String)

fun NavController.navigateToTasks() = navigate(TasksRoute) { launchSingleTop = true }

fun NavGraphBuilder.tasksScreens(navController: NavController) {
    composable<TasksRoute> {
        BoardScreen(
            viewModel = hiltViewModel(),
            onBack = { navController.popBackStack() },
            onOpenTask = { board, id -> navController.navigate(TaskRoute(board, id)) },
            onNewTask = { board, lane -> navController.navigate(NewTaskRoute(board, lane.key)) },
            onRouting = { navController.navigate(RoutingRoute) },
        )
    }
    composable<TaskRoute> {
        TaskScreen(
            viewModel = hiltViewModel(),
            onBack = { navController.popBackStack() },
            onOpenTask = { board, id -> navController.navigate(TaskRoute(board, id)) },
            onOpenLog = { board, id -> navController.navigate(WorkerLogRoute(board, id)) },
        )
    }
    composable<RoutingRoute> {
        RoutingScreen(viewModel = hiltViewModel(), onBack = { navController.popBackStack() })
    }
    composable<WorkerLogRoute> {
        WorkerLogScreen(viewModel = hiltViewModel(), onBack = { navController.popBackStack() })
    }
    composable<NewTaskRoute> { entry ->
        val board = entry.toRoute<NewTaskRoute>().board
        NewTaskScreen(
            viewModel = hiltViewModel(),
            onClose = { navController.popBackStack() },
            onCreated = { id ->
                navController.popBackStack()
                navController.navigate(TaskRoute(board, id))
            },
        )
    }
}
