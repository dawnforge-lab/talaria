package lab.dawnforge.hermes.feature.notifications.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import lab.dawnforge.hermes.feature.notifications.NotificationsScreen

@Serializable
data object NotificationsRoute

fun NavController.navigateToNotifications(builder: NavOptionsBuilder.() -> Unit = {}) =
    navigate(NotificationsRoute, builder)

fun NavGraphBuilder.notificationsScreen(onBack: () -> Unit) {
    composable<NotificationsRoute> {
        NotificationsScreen(viewModel = hiltViewModel(), onBack = onBack)
    }
}
