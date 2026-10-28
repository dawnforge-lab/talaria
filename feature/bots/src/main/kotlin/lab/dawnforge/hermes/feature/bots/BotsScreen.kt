package lab.dawnforge.hermes.feature.bots

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lab.dawnforge.hermes.core.data.bots.BotChat
import lab.dawnforge.hermes.core.data.bots.BotColor
import lab.dawnforge.hermes.core.data.bots.BotProfile
import lab.dawnforge.hermes.core.data.bots.BotsState
import lab.dawnforge.hermes.core.data.groups.GroupRoom
import lab.dawnforge.hermes.core.data.groups.GroupsState
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.ui.BotAvatar
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.feature.bots.groups.GroupRoomIcon

/**
 * The Bots screen, as iOS's: each bot (a Hermes profile with its own look, personality and one
 * ongoing chat), opening its chat, and making, editing, pinning and deleting bots.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BotsScreen(
    viewModel: BotsViewModel,
    onBack: () -> Unit,
    onOpenChat: (BotChat, String) -> Unit,
    onNewBot: () -> Unit,
    onEditBot: (String) -> Unit,
    onOpenRoom: (GroupRoom) -> Unit,
    onNewGroup: () -> Unit,
) {
    val roster by viewModel.roster.collectAsStateWithLifecycle()
    val groupChats by viewModel.groupChats.collectAsStateWithLifecycle()
    val roomPictures by viewModel.roomPictures.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val avatars by viewModel.avatars.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.let { messageText(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.messageShown()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_bots_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_bots_back))
                    }
                },
                actions = {
                    IconButton(onClick = onNewBot, enabled = roster is BotsState.Available) {
                        Icon(Icons.Filled.Add, stringResource(R.string.feature_bots_new))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (val current = roster) {
                BotsState.Loading -> Centered { CircularProgressIndicator() }

                BotsState.Unsupported -> Centered {
                    Text(stringResource(R.string.feature_bots_unsupported))
                    OutlinedButton(onClick = viewModel::refresh) { Text(stringResource(R.string.feature_bots_retry)) }
                }

                is BotsState.Failed -> Centered {
                    Text(stringResource(R.string.feature_bots_failed))
                    Text(failureText(current.failure), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = viewModel::refresh) { Text(stringResource(R.string.feature_bots_retry)) }
                }

                is BotsState.Available -> {
                    val bots = current.roster.visible
                    val groups = GroupRows(groupChats, canCreate = bots.size >= 2)
                    if (bots.isEmpty() && !groups.any) {
                        Centered {
                            Text(
                                stringResource(R.string.feature_bots_empty),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.feature_bots_empty_help),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = onNewBot) { Text(stringResource(R.string.feature_bots_new)) }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(bots, key = { it.name }) { bot ->
                                BotRow(
                                    bot = bot,
                                    picture = avatars[bot.name],
                                    opening = state.opening == bot.name,
                                    canDelete = viewModel.canDelete(bot),
                                    onOpen = { viewModel.open(bot, onOpenChat) },
                                    onEdit = { onEditBot(bot.name) },
                                    onPin = { viewModel.setPinned(bot, !bot.pinned) },
                                    onDelete = { viewModel.askDelete(bot) },
                                )
                            }
                            groupSection(groups, roomPictures, onOpenRoom, onNewGroup)
                            item {
                                Text(
                                    stringResource(R.string.feature_bots_footer),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    state.deleting?.let { bot ->
        AlertDialog(
            onDismissRequest = { viewModel.askDelete(null) },
            title = { Text(stringResource(R.string.feature_bots_delete_title, bot.label)) },
            text = { Text(stringResource(R.string.feature_bots_delete_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(bot) }) {
                    Text(stringResource(R.string.feature_bots_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.askDelete(null)
                }) { Text(stringResource(R.string.feature_bots_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotRow(
    bot: BotProfile,
    picture: ByteArray?,
    opening: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val chatLabel = stringResource(R.string.feature_bots_chat)
    ListItem(
        leadingContent = { BotAvatar(bot.label, BotColor.of(bot), picture) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(bot.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (bot.pinned) {
                    Text(stringResource(R.string.feature_bots_pinned_mark), style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        supportingContent = {
            val preview = (bot.canonicalSession?.preview ?: bot.lastPreview)?.trim()?.takeIf(String::isNotEmpty)
            Text(
                preview ?: bot.description.trim().ifEmpty { chatLabel },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (opening) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp))
                } else {
                    bot.activity?.let { Text(relative(it), style = MaterialTheme.typography.labelSmall) }
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.feature_bots_actions, bot.label))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.feature_bots_edit)) }, onClick = {
                            menu = false
                            onEdit()
                        })
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (bot.pinned) R.string.feature_bots_unpin else R.string.feature_bots_pin,
                                    ),
                                )
                            },
                            onClick = {
                                menu = false
                                onPin()
                            },
                        )
                        if (canDelete) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.feature_bots_delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menu = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }
        },
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
    )
}

/** What the Bots screen lists under its group chats. */
private class GroupRows(state: GroupsState, canCreate: Boolean) {
    val rooms: List<GroupRoom> = (state as? GroupsState.Available)?.rooms.orEmpty()
    val failure: ConnectionFailure? = (state as? GroupsState.Failed)?.failure
    val canCreate: Boolean = state is GroupsState.Available && canCreate
    val any: Boolean get() = rooms.isNotEmpty() || failure != null
    val shown: Boolean get() = any || canCreate
}

/** Group chats below the bots: the rooms Hermes runs, and making one. */
private fun LazyListScope.groupSection(
    groups: GroupRows,
    pictures: Map<String, ByteArray>,
    onOpenRoom: (GroupRoom) -> Unit,
    onNew: () -> Unit,
) {
    if (!groups.shown) return
    item(key = "groups-header") {
        Text(
            stringResource(R.string.feature_bots_group_section),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    groups.failure?.let { failure ->
        item(key = "groups-failed") {
            Text(
                stringResource(R.string.feature_bots_group_list_failed, failureText(failure)),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
    items(groups.rooms, key = { "room-" + it.roomId }) { room ->
        ListItem(
            leadingContent = { GroupRoomIcon(pictures[room.roomId], room.members, 40.dp, room.name) },
            headlineContent = { Text(room.name, fontWeight = FontWeight.SemiBold, maxLines = 1) },
            supportingContent = {
                Text(
                    room.members.joinToString(", ") { it.label.orEmpty() },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier.clickable { onOpenRoom(room) },
        )
    }
    if (groups.canCreate) {
        item(key = "groups-new") {
            ListItem(
                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.feature_bots_group_new)) },
                modifier = Modifier.clickable(onClick = onNew),
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) { content() }
}

/** "5 min. ago": seconds, or milliseconds from a host that sends them. */
private fun relative(value: Double): String {
    val millis = if (value > MILLIS_THRESHOLD) value.toLong() else (value * MILLIS).toLong()
    return DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}

@Composable
private fun messageText(message: BotsMessage): String = when (message) {
    is BotsMessage.ChatUnchecked -> stringResource(R.string.feature_bots_chat_unchecked, message.label)
    is BotsMessage.ChatUnconfirmed -> stringResource(R.string.feature_bots_chat_unconfirmed, message.label)
    is BotsMessage.Failed -> failureText(message.failure)
    is BotsMessage.NotUpdated -> stringResource(R.string.feature_bots_not_updated, message.label)
    is BotsMessage.Deleted -> stringResource(R.string.feature_bots_deleted, message.label)
}

private const val MILLIS_THRESHOLD = 1e10
private const val MILLIS = 1000.0
