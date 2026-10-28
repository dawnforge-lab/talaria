package lab.dawnforge.hermes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import lab.dawnforge.hermes.R
import lab.dawnforge.hermes.core.chat.SettingsLink
import lab.dawnforge.hermes.feature.chat.navigation.ChatRoute
import lab.dawnforge.hermes.feature.chat.navigation.chatScreen

/** No chat open beside the list yet. */
@Serializable
data object NoChatRoute

/** Whether the window is wide enough for the list and a chat side by side (iOS splits from 806 pt). */
@Composable
internal fun isTwoPane(): Boolean = LocalConfiguration.current.screenWidthDp >= TWO_PANE_DP

/**
 * A list beside its open chat, as iOS's split view: the list keeps 375 dp and the chat takes the
 * rest, with its own back stack (Back closes the chat first). With no chat open, the right side
 * offers a new one.
 */
@Composable
internal fun ListBesideChat(
    detail: NavHostController,
    onNewChat: (() -> Unit)?,
    onOpenChat: (ChatRoute) -> Unit,
    onRepairConnection: () -> Unit,
    onOpenSettings: (SettingsLink) -> Unit,
    list: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        // Each side keeps only the window's insets on its own edge: the list none on its right, the
        // chat none on its left.
        Box(
            Modifier.width(LIST_DP.dp).fillMaxHeight()
                .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.End)),
        ) { list() }
        VerticalDivider()
        NavHost(
            navController = detail,
            startDestination = NoChatRoute,
            modifier = Modifier.weight(1f).consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)),
        ) {
            composable<NoChatRoute> { NoChat(onNewChat) }
            chatScreen(
                detail,
                onOpenChat = onOpenChat,
                onRepairConnection = onRepairConnection,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

@Composable
private fun NoChat(onNewChat: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            stringResource(R.string.app_no_chat_open),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        onNewChat?.let { FilledTonalButton(onClick = it) { Text(stringResource(R.string.app_start_conversation)) } }
    }
}

/** Opens [route] beside the list, in place of the chat open there. */
internal fun NavHostController.openBeside(route: ChatRoute) = navigate(route) {
    popUpTo<NoChatRoute> { inclusive = false }
}

private const val TWO_PANE_DP = 806
private const val LIST_DP = 375
