package lab.dawnforge.hermes.feature.cron.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import lab.dawnforge.hermes.feature.cron.CronBlueprintScreen
import lab.dawnforge.hermes.feature.cron.CronBlueprintsScreen
import lab.dawnforge.hermes.feature.cron.CronEditScreen
import lab.dawnforge.hermes.feature.cron.CronJobScreen
import lab.dawnforge.hermes.feature.cron.CronJobsScreen

/** The active profile's scheduled jobs. */
@Serializable
data object CronJobsRoute

/** One job: what it does, when it runs, and its runs. */
@Serializable
data class CronJobRoute(val profile: String, val jobId: String)

/** A new job, or [jobId]'s changes. */
@Serializable
data class CronEditRoute(val profile: String, val jobId: String? = null)

/** The ready-made jobs Hermes offers. */
@Serializable
data class CronBlueprintsRoute(val profile: String)

/** One blueprint's blanks. */
@Serializable
data class CronBlueprintRoute(val profile: String, val key: String)

fun NavController.navigateToCronJobs() = navigate(CronJobsRoute) { launchSingleTop = true }

/**
 * The scheduled-jobs screens. [onOpenRun] opens a run as the chat Hermes stored it under the
 * profile that ran it; [onShowRuns] shows the chat list, filtered to scheduled runs.
 */
fun NavGraphBuilder.cronScreens(
    navController: NavController,
    onShowRuns: () -> Unit,
    onOpenRun: (profile: String, sessionId: String, title: String) -> Unit,
) {
    composable<CronJobsRoute> {
        CronJobsScreen(
            viewModel = hiltViewModel(),
            onBack = { navController.popBackStack() },
            onOpenJob = { profile, jobId -> navController.navigate(CronJobRoute(profile, jobId)) },
            onNewJob = { profile -> navController.navigate(CronEditRoute(profile)) },
            onEditJob = { profile, jobId -> navController.navigate(CronEditRoute(profile, jobId)) },
            onBlueprints = { profile -> navController.navigate(CronBlueprintsRoute(profile)) },
            onShowRuns = onShowRuns,
        )
    }
    composable<CronBlueprintsRoute> { entry ->
        val profile = entry.toRoute<CronBlueprintsRoute>().profile
        CronBlueprintsScreen(
            viewModel = hiltViewModel(),
            onBack = { navController.popBackStack() },
            onOpen = { key -> navController.navigate(CronBlueprintRoute(profile, key)) },
        )
    }
    composable<CronBlueprintRoute> {
        CronBlueprintScreen(
            viewModel = hiltViewModel(),
            onBack = { navController.popBackStack() },
            // The new job opens over the list; the gallery and the form close.
            onCreated = { profile, jobId ->
                navController.navigate(CronJobRoute(profile, jobId)) {
                    popUpTo<CronJobsRoute>()
                }
            },
        )
    }
    composable<CronJobRoute> {
        CronJobScreen(
            viewModel = hiltViewModel(),
            onBack = { navController.popBackStack() },
            onEdit = { profile, jobId -> navController.navigate(CronEditRoute(profile, jobId)) },
            onOpenRun = onOpenRun,
        )
    }
    composable<CronEditRoute> {
        CronEditScreen(viewModel = hiltViewModel(), onDone = { navController.popBackStack() })
    }
}
