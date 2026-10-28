package lab.dawnforge.hermes.feature.bots.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import lab.dawnforge.hermes.core.data.bots.BotChat
import lab.dawnforge.hermes.feature.bots.BotEditorScreen
import lab.dawnforge.hermes.feature.bots.BotsScreen
import lab.dawnforge.hermes.feature.bots.BotsViewModel
import lab.dawnforge.hermes.feature.bots.groups.GroupCreateScreen
import lab.dawnforge.hermes.feature.bots.groups.GroupRoomScreen
import lab.dawnforge.hermes.feature.bots.profiles.ProfilesScreen

/** The bots on the dashboard's Hermes. */
@Serializable
data object BotsRoute

/** Editing the bot [name], or making a new one (null). */
@Serializable
data class BotEditorRoute(val name: String? = null)

/** One hosted group chat; [name] shows until the room is read. */
@Serializable
data class GroupRoomRoute(val roomId: String, val name: String)

/** Making a group chat. */
@Serializable
data object GroupCreateRoute

/** The profile manager: pictures, order and the main profile's name. */
@Serializable
data object ProfilesRoute

fun NavController.navigateToBots() = navigate(BotsRoute) { launchSingleTop = true }

fun NavController.navigateToProfiles() = navigate(ProfilesRoute) { launchSingleTop = true }

fun NavGraphBuilder.profilesScreen(onBack: () -> Unit) {
    composable<ProfilesRoute> { ProfilesScreen(viewModel = hiltViewModel(), onBack = onBack) }
}

/**
 * [onOpenChat] opens a bot's one chat, titled with its name. [frame] lays the roster out: on its own,
 * or beside the open chat on a wide screen.
 */
fun NavGraphBuilder.botsScreens(
    navController: NavController,
    frame: @Composable (list: @Composable () -> Unit) -> Unit = { it() },
    onOpenChat: (BotChat, String) -> Unit,
) {
    composable<BotsRoute> { entry ->
        val viewModel: BotsViewModel = hiltViewModel(entry)
        val created by entry.savedStateHandle.getStateFlow<String?>(CREATED, null).collectAsStateWithLifecycle()
        LaunchedEffect(created) {
            created?.let { name ->
                entry.savedStateHandle[CREATED] = null
                viewModel.openCreated(name, onOpenChat)
            }
        }
        frame {
            BotsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenChat = onOpenChat,
                onNewBot = { navController.navigate(BotEditorRoute()) },
                onEditBot = { name -> navController.navigate(BotEditorRoute(name)) },
                onOpenRoom = { room -> navController.navigate(GroupRoomRoute(room.roomId, room.name)) },
                onNewGroup = { navController.navigate(GroupCreateRoute) },
            )
        }
    }
    composable<GroupRoomRoute> {
        GroupRoomScreen(viewModel = hiltViewModel(), onBack = { navController.popBackStack() })
    }
    composable<GroupCreateRoute> {
        GroupCreateScreen(
            viewModel = hiltViewModel(),
            onClose = { navController.popBackStack() },
            // The new room opens in place of the form.
            onCreated = { room ->
                navController.navigate(GroupRoomRoute(room.roomId, room.name)) {
                    popUpTo<GroupCreateRoute> { inclusive = true }
                }
            },
        )
    }
    composable<BotEditorRoute> {
        BotEditorScreen(
            viewModel = hiltViewModel(),
            onClose = { navController.popBackStack() },
            // A new bot's chat opens straight away, as on iOS.
            onCreated = { name ->
                navController.popBackStack()
                val roster = navController.currentBackStackEntry
                roster?.savedStateHandle?.set(CREATED, name)
            },
        )
    }
}

/** The bot just made, handed back to the roster to open its chat. */
const val CREATED = "lab.dawnforge.hermes.bots.CREATED"
