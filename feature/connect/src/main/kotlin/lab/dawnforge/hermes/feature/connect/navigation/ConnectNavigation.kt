package lab.dawnforge.hermes.feature.connect.navigation

import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import kotlinx.serialization.Serializable
import lab.dawnforge.hermes.feature.connect.AddressScreen
import lab.dawnforge.hermes.feature.connect.ConnectViewModel
import lab.dawnforge.hermes.feature.connect.SignInScreen
import lab.dawnforge.hermes.feature.connect.dashboards.DashboardsScreen
import lab.dawnforge.hermes.feature.connect.scan.ScanScreen
import lab.dawnforge.hermes.feature.connect.setup.RepairFocus
import lab.dawnforge.hermes.feature.connect.setup.RepairWizardViewModel
import lab.dawnforge.hermes.feature.connect.setup.SetupStart
import lab.dawnforge.hermes.feature.connect.setup.SetupWizardScreen
import lab.dawnforge.hermes.feature.connect.setup.SetupWizardViewModel
import lab.dawnforge.hermes.feature.connect.web.WebSignInScreen

/**
 * The add-a-dashboard flow. [address] and [username] pre-fill it when signing in again; secrets
 * never travel in routes.
 */
@Serializable
data class ConnectGraph(
    val address: String? = null,
    val username: String? = null,
    /** True when started from a `talaria://connect` link waiting in the inbox. */
    val fromLink: Boolean = false,
)

@Serializable
internal data object AddressStep

@Serializable
internal data object SignInStep

@Serializable
internal data object ScanStep

@Serializable
internal data object WebSignInStep

/** The connection-setup wizard, opened at [start] with [address] as the current one (never a secret). */
@Serializable
data class SetupRoute(val start: SetupStart = SetupStart.Dashboard, val address: String? = null)

/**
 * Repair connection for a saved [dashboard], opened at [focus]; with [edit], Connection setup for a
 * working one, which holds nothing.
 */
@Serializable
data class RepairRoute(val dashboard: String, val focus: RepairFocus = RepairFocus.Test, val edit: Boolean = false)

@Serializable
data object DashboardsRoute

fun NavController.navigateToConnect(route: ConnectGraph = ConnectGraph(), builder: NavOptionsBuilder.() -> Unit = {}) =
    navigate(route, builder)

fun NavController.navigateToDashboards(builder: NavOptionsBuilder.() -> Unit = {}) = navigate(DashboardsRoute, builder)

/**
 * Adds the connect flow. [onSignedIn] runs once a dashboard is signed in and saved; [onCancel]
 * is offered only when the dashboards screen is underneath to go back to.
 */
fun NavGraphBuilder.connectGraph(navController: NavController, onSignedIn: () -> Unit, onCancel: () -> Unit) {
    navigation<ConnectGraph>(startDestination = AddressStep) {
        composable<AddressStep> { entry ->
            val parent = remember(entry) { navController.getBackStackEntry<ConnectGraph>() }
            val canCancel = remember(entry) {
                runCatching { navController.getBackStackEntry<DashboardsRoute>() }.isSuccess
            }
            AddressScreen(
                viewModel = hiltViewModel<ConnectViewModel>(parent),
                onContinueToSignIn = { navController.navigate(SignInStep) { launchSingleTop = true } },
                onScan = { navController.navigate(ScanStep) { launchSingleTop = true } },
                onSetup = { start, address -> navController.navigate(SetupRoute(start, address)) },
                onSignedIn = onSignedIn,
                onCancel = onCancel.takeIf { canCancel },
            )
        }
        composable<SignInStep> { entry ->
            val parent = remember(entry) { navController.getBackStackEntry<ConnectGraph>() }
            SignInScreen(
                viewModel = hiltViewModel<ConnectViewModel>(parent),
                onSignedIn = onSignedIn,
                onBack = { navController.popBackStack() },
                onTroubleshoot = { start, address -> navController.navigate(SetupRoute(start, address)) },
                onWebSignIn = { navController.navigate(WebSignInStep) { launchSingleTop = true } },
            )
        }
        composable<WebSignInStep> { entry ->
            val parent = remember(entry) { navController.getBackStackEntry<ConnectGraph>() }
            WebSignInScreen(
                viewModel = hiltViewModel<ConnectViewModel>(parent),
                onSignedIn = onSignedIn,
                onCancel = { navController.popBackStack() },
            )
        }
        composable<SetupRoute> { entry ->
            val parent = remember(entry) { navController.getBackStackEntry<ConnectGraph>() }
            val connect = hiltViewModel<ConnectViewModel>(parent)
            val wizard = hiltViewModel<SetupWizardViewModel>()
            wizard.typedCloudflareToken = connect::cloudflareToken
            SetupWizardScreen(
                viewModel = wizard,
                onClose = { navController.popBackStack() },
                onUse = { address, username, password ->
                    // Back to the address step, which goes on to the sign-in once the address is checked.
                    navController.popBackStack<AddressStep>(inclusive = false)
                    connect.useSetup(address, username, password)
                },
            )
        }
        composable<ScanStep> { entry ->
            val parent = remember(entry) { navController.getBackStackEntry<ConnectGraph>() }
            ScanScreen(
                viewModel = hiltViewModel<ConnectViewModel>(parent),
                onDone = { navController.popBackStack() },
            )
        }
    }
}

/**
 * Repair connection (and Connection setup for a working dashboard). [onSignIn] opens the sign-in
 * screen for an address, for a repair that signs in through the browser.
 */
fun NavGraphBuilder.repairScreen(onClose: () -> Unit, onSignIn: (address: String, username: String?) -> Unit) {
    composable<RepairRoute> {
        SetupWizardScreen(
            viewModel = hiltViewModel<RepairWizardViewModel>(),
            onClose = onClose,
            onSignIn = onSignIn,
        )
    }
}

fun NavGraphBuilder.dashboardsScreen(
    onBack: () -> Unit,
    onAddDashboard: () -> Unit,
    onSignInAgain: (address: String, username: String?) -> Unit,
    onNoDashboards: () -> Unit,
    onOpenRepair: (RepairRoute) -> Unit,
) {
    composable<DashboardsRoute> {
        DashboardsScreen(
            viewModel = hiltViewModel(),
            onBack = onBack,
            onAddDashboard = onAddDashboard,
            onSignInAgain = onSignInAgain,
            onNoDashboards = onNoDashboards,
            onOpenRepair = onOpenRepair,
        )
    }
}
