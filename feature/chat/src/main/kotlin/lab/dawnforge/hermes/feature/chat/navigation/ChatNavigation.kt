package lab.dawnforge.hermes.feature.chat.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import lab.dawnforge.hermes.core.chat.SettingsLink
import lab.dawnforge.hermes.core.data.share.SharedDrafts
import lab.dawnforge.hermes.feature.chat.ArtifactsScreen
import lab.dawnforge.hermes.feature.chat.ChatScreen
import lab.dawnforge.hermes.feature.chat.WorkspaceScreen

/**
 * One chat. [sessionId] is the stored id of an existing chat; a chat just started has only its
 * live [runtimeId] until its first message is stored.
 */
@Serializable
data class ChatRoute(
    val profile: String,
    val sessionId: String? = null,
    val runtimeId: String? = null,
    val title: String? = null,
    /** A stored message to scroll to when the chat opens ("Show in chat"). */
    val revealRowId: Long? = null,
    /** A message ready to send in a chat just started ("Try a test call"). */
    val draft: String? = null,
    /** A bot's one ongoing chat: it never resets. */
    val botChat: Boolean = false,
    /** What another app shared (a [SharedDrafts] id), put in the composer to review and send. */
    val shared: String? = null,
    /** Talk from the widget, a shortcut or the tile: the voice conversation starts when the chat opens. */
    val talk: Boolean = false,
    /**
     * A new chat from the list's unsent chats ([runtimeId] its live id then): the stored id Hermes
     * gave it, which it is opened by in case Hermes stored it after all.
     */
    val reservedId: String? = null,
    /** That new chat's folder on the host, to start it again there if Hermes let it go. */
    val newChatCwd: String? = null,
)

/** A chat's working folder on the host, [root], browsed for [profile]. */
@Serializable
data class WorkspaceRoute(val profile: String, val root: String)

/** What chats produced: the profile's, or one chat's by its [sessionIds] (titled [title]). */
@Serializable
data class ArtifactsRoute(val profile: String, val sessionIds: List<String> = emptyList(), val title: String? = null)

fun NavController.navigateToArtifacts(profile: String) = navigate(ArtifactsRoute(profile)) { launchSingleTop = true }

fun NavController.navigateToChat(route: ChatRoute) = navigate(route) { launchSingleTop = true }

/**
 * Opens [route] over the chat showing: single-top would take any chat for the same screen and keep
 * the one already open.
 */
fun NavController.navigateToOtherChat(route: ChatRoute) = navigate(route)

/**
 * A chat, with its workspace and artifacts. [onOpenChat] opens another chat from this one: a new
 * one (`/new`), a branch (`/branch`), or the chat an artifact came from.
 */
fun NavGraphBuilder.chatScreen(
    navController: NavController,
    onOpenChat: (ChatRoute) -> Unit,
    onRepairConnection: (() -> Unit)? = null,
    /** A Settings page a reply linked to. */
    onOpenSettings: (SettingsLink) -> Unit = {},
) {
    composable<ChatRoute> { entry ->
        val profile = entry.toRoute<ChatRoute>().profile
        ChatScreen(
            onBack = { navController.popBackStack() },
            onOpenChat = onOpenChat,
            onOpenWorkspace = { root -> navController.navigate(WorkspaceRoute(profile, root)) },
            onOpenArtifacts = { ids, title -> navController.navigate(ArtifactsRoute(profile, ids, title)) },
            onRepairConnection = onRepairConnection,
            onOpenSettings = onOpenSettings,
        )
    }
    composable<WorkspaceRoute> {
        WorkspaceScreen(viewModel = hiltViewModel(), onBack = { navController.popBackStack() })
    }
    composable<ArtifactsRoute> { entry ->
        val profile = entry.toRoute<ArtifactsRoute>().profile
        ArtifactsScreen(
            viewModel = hiltViewModel(),
            onBack = { navController.popBackStack() },
            onShowInChat = { sessionId, messageId, title ->
                onOpenChat(ChatRoute(profile, sessionId = sessionId, title = title, revealRowId = messageId))
            },
        )
    }
}
