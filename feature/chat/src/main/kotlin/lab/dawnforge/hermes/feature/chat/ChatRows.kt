package lab.dawnforge.hermes.feature.chat

import android.content.ClipData
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.chat.REACTIONS
import lab.dawnforge.hermes.core.chat.ReplyText
import lab.dawnforge.hermes.core.chat.TODO_TOOLS
import lab.dawnforge.hermes.core.chat.Todo
import lab.dawnforge.hermes.core.chat.TodoStatus
import lab.dawnforge.hermes.core.chat.parseTodos
import lab.dawnforge.hermes.core.chat.toolResultFiles
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.model.Delivery
import lab.dawnforge.hermes.core.model.NoteKind
import lab.dawnforge.hermes.core.model.NoticeKind
import lab.dawnforge.hermes.core.model.Reaction
import lab.dawnforge.hermes.core.model.ToolStatus
import lab.dawnforge.hermes.core.voice.ReadAloudState

/**
 * What rows can do: the user's own while not with Hermes yet (try again, take back), and a reply
 * (react, copy, reply to, branch from).
 */
internal class RowActions(
    val onRetry: (String) -> Unit,
    val onTakeBack: (String) -> Unit,
    val canReact: (ChatItem.Assistant) -> Boolean,
    val onReact: (ChatItem.Assistant, String) -> Unit,
    val onReply: (ChatItem.Assistant) -> Unit,
    /** Branching needs a stored reply and no turn running. */
    val canBranch: (ChatItem.Assistant) -> Boolean,
    val onBranch: (ChatItem.Assistant) -> Unit,
    val onCopied: () -> Unit,
    /** How a reply is being read aloud, by its key; null when it isn't. */
    val reading: (String) -> ReadAloudState.Phase?,
    val onReadAloud: (ChatItem.Assistant) -> Unit,
)

/**
 * One transcript row. [superseded] names its live cards a later card replaced; [attachments] are
 * the host files a reply comes with (its turn's voice messages).
 */
@Composable
internal fun ChatRow(
    item: ChatItem,
    actions: RowActions,
    modifier: Modifier = Modifier,
    superseded: Set<String> = emptySet(),
    attachments: List<String> = emptyList(),
) {
    when (item) {
        is ChatItem.User -> UserMessage(item, actions, modifier)

        is ChatItem.Assistant -> AssistantMessage(item, attachments, actions, modifier)

        is ChatItem.Reasoning -> ReasoningBlock(item, modifier)

        is ChatItem.Tool -> if (item.cards.isNotEmpty()) {
            RichCardsView(
                item.cards,
                superseded,
                modifier,
            )
        } else {
            ToolCard(item, modifier)
        }

        is ChatItem.Notice -> NoticeLine(item, modifier)

        is ChatItem.Note -> NoteCard(item, modifier)
    }
}

/** A note only this phone shows: a command's output, or a side question's answer. */
@Composable
private fun NoteCard(item: ChatItem.Note, modifier: Modifier) {
    OutlinedCard(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when (item.kind) {
                    NoteKind.Command -> item.title ?: stringResource(R.string.feature_chat_note_command)
                    NoteKind.SideQuestion -> stringResource(R.string.feature_chat_note_btw, item.title.orEmpty())
                    NoteKind.Background -> stringResource(R.string.feature_chat_note_background)
                    NoteKind.Review -> stringResource(R.string.feature_chat_note_review)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            SelectionContainer {
                // A command prints plain text laid out for a terminal; the agents' answers are Markdown.
                if (item.kind == NoteKind.Command) {
                    Text(item.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                } else {
                    ChatMarkdown(item.text, streaming = false)
                }
            }
        }
    }
}

@Composable
private fun UserMessage(item: ChatItem.User, actions: RowActions, modifier: Modifier) {
    val pending = item.delivery == Delivery.Sending || item.delivery == Delivery.Queued ||
        item.delivery == Delivery.Waiting
    Box(modifier.fillMaxWidth().alpha(if (pending) PENDING_ALPHA else 1f), contentAlignment = Alignment.CenterEnd) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (item.isSteer) {
                Text(
                    stringResource(R.string.feature_chat_steer_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val reply = remember(item.text) { ReplyText.parse(item.text) }
            reply.quoted?.let { QuotedReply(it, Modifier.widthIn(max = 320.dp)) }
            if (reply.text.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    SelectionContainer {
                        Text(
                            reply.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            // Only the agent reacts to the user's messages.
            if (item.reactions.isNotEmpty()) ReactionChips(item.reactions, onClick = null)
            // Pictures stored with the message show as pictures; a message still on its way has none yet.
            val pictures = item.attachments.filter { it.isImage && it.path.isNotEmpty() }
            pictures.forEachIndexed { index, picture ->
                HostImage(picture.path, rowId = item.rowId, index = index, label = picture.name)
            }
            (item.attachments - pictures.toSet()).forEach { attachment ->
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
            item.delivery?.let { DeliveryLine(item.key, it, actions) }
        }
    }
}

@Composable
private fun DeliveryLine(key: String, delivery: Delivery, actions: RowActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val (text, color) = when (delivery) {
            Delivery.Sending -> stringResource(R.string.feature_chat_sending) to
                MaterialTheme.colorScheme.onSurfaceVariant

            Delivery.Queued -> stringResource(R.string.feature_chat_queued) to
                MaterialTheme.colorScheme.onSurfaceVariant

            Delivery.Failed -> stringResource(R.string.feature_chat_not_sent) to MaterialTheme.colorScheme.error

            Delivery.Waiting -> stringResource(R.string.feature_chat_waiting) to
                MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
        if (delivery == Delivery.Failed) {
            TextButton(onClick = { actions.onRetry(key) }) { Text(stringResource(R.string.feature_chat_retry_send)) }
        }
        if (delivery != Delivery.Sending) {
            TextButton(onClick = { actions.onTakeBack(key) }) { Text(stringResource(R.string.feature_chat_take_back)) }
        }
    }
}

@Composable
private fun AssistantMessage(
    item: ChatItem.Assistant,
    attachments: List<String>,
    actions: RowActions,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SelectionContainer(Modifier.fillMaxWidth()) {
            ReplyBody(item.text, streaming = item.streaming)
        }
        if (!item.streaming && attachments.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                attachments.forEach { HostFile(it, label = null) }
            }
        }
        if (!item.streaming) ReplyActions(item, actions)
    }
}

/** A reply's reactions, and its menu: react, copy, reply to it, branch from it. */
@Composable
private fun ReplyActions(item: ChatItem.Assistant, actions: RowActions) {
    var open by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val canReact = actions.canReact(item)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (item.reactions.isNotEmpty()) {
            ReactionChips(item.reactions, onClick = if (canReact) ({ open = true }) else null)
        }
        ReadAloudButton(actions.reading(item.key)) { actions.onReadAloud(item) }
        Box {
            IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.feature_chat_reply_actions),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (canReact) {
                    val mine = item.reactions.firstOrNull { !it.byAgent }?.emoji
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        REACTIONS.forEach { emoji ->
                            val label = stringResource(
                                if (emoji == mine) R.string.feature_chat_react_remove else R.string.feature_chat_react,
                                emoji,
                            )
                            TextButton(
                                onClick = {
                                    open = false
                                    actions.onReact(item, emoji)
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = label
                                    selected = emoji == mine
                                },
                            ) {
                                Text(emoji, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    HorizontalDivider()
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.feature_chat_copy)) },
                    onClick = {
                        open = false
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("reply", item.text)))
                            actions.onCopied()
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.feature_chat_reply)) },
                    onClick = {
                        open = false
                        actions.onReply(item)
                    },
                )
                if (actions.canBranch(item)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.feature_chat_branch_here)) },
                        onClick = {
                            open = false
                            actions.onBranch(item)
                        },
                    )
                }
            }
        }
    }
}

/** One chip per emoji, with how many reacted when more than one did; the user's own is outlined. */
@Composable
private fun ReactionChips(reactions: List<Reaction>, onClick: (() -> Unit)?) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(end = 4.dp)) {
        reactions.groupBy(Reaction::emoji).forEach { (emoji, by) ->
            val mine = by.any { !it.byAgent }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = if (mine) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                onClick = onClick ?: {},
                enabled = onClick != null,
            ) {
                Text(
                    if (by.size > 1) "$emoji ${by.size}" else emoji,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** The reply a message of the user's answers, above it. */
@Composable
private fun QuotedReply(text: String, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.feature_chat_replying_to),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReasoningBlock(item: ChatItem.Reasoning, modifier: Modifier) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val label = stringResource(
        if (item.streaming) R.string.feature_chat_reasoning_streaming else R.string.feature_chat_reasoning,
    )
    Column(modifier.fillMaxWidth().animateContentSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(vertical = 4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            SelectionContainer {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolCard(item: ChatItem.Tool, modifier: Modifier) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val statusText = stringResource(
        when (item.status) {
            ToolStatus.Running -> R.string.feature_chat_tool_running
            ToolStatus.Complete -> R.string.feature_chat_tool_done
            ToolStatus.Failed -> R.string.feature_chat_tool_failed
            ToolStatus.Stopped -> R.string.feature_chat_tool_stopped
        },
    )
    OutlinedCard(modifier.fillMaxWidth().animateContentSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            ToolStatusIcon(item.status, statusText)
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.summary?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item.durationMs?.let {
                Text(
                    stringResource(R.string.feature_chat_tool_duration, it / 1000.0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            ) {
                // The files a finished call made (a voice message's audio), drawn as in a reply.
                val files = if (item.status == ToolStatus.Complete) {
                    remember(item.name, item.output) { toolResultFiles(item.name, item.output) }
                } else {
                    null
                }
                files?.forEach { HostFile(it, label = null) }
                item.input?.let { ToolText(stringResource(R.string.feature_chat_tool_input), it) }
                val plan = if (item.name in TODO_TOOLS) remember(item.output) { parseTodos(item.output) } else null
                if (plan != null) {
                    TodoList(plan)
                } else {
                    item.output?.let { ToolText(stringResource(R.string.feature_chat_tool_output), it) }
                }
                if (item.input == null && item.output == null) {
                    Text(
                        stringResource(R.string.feature_chat_tool_nothing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolStatusIcon(status: ToolStatus, description: String) {
    val semantics = Modifier.semantics { contentDescription = description }
    when (status) {
        ToolStatus.Running -> CircularProgressIndicator(semantics.size(18.dp), strokeWidth = 2.dp)

        ToolStatus.Complete -> Icon(Icons.Filled.Check, null, semantics.size(18.dp), MaterialTheme.colorScheme.primary)

        ToolStatus.Failed -> Icon(Icons.Filled.Warning, null, semantics.size(18.dp), MaterialTheme.colorScheme.error)

        ToolStatus.Stopped ->
            Icon(Icons.Filled.Close, null, semantics.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The agent's plan: each step, done or not, subtasks indented. */
@Composable
private fun TodoList(plan: List<Todo>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        plan.forEach { todo ->
            val done = todo.status == TodoStatus.Completed || todo.status == TodoStatus.Cancelled
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(start = (TODO_INDENT * todo.depth).dp)) {
                Text(
                    when (todo.status) {
                        TodoStatus.Pending -> "○"
                        TodoStatus.InProgress -> "◐"
                        TodoStatus.Completed -> "✓"
                        TodoStatus.Cancelled -> "✕"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (todo.status == TodoStatus.InProgress) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    todo.content,
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDecoration = if (todo.status == TodoStatus.Cancelled) TextDecoration.LineThrough else null,
                    ),
                    color = if (done) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun ToolText(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = TOOL_TEXT_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun NoticeLine(item: ChatItem.Notice, modifier: Modifier) {
    val text = noticeText(item)
    val color = if (item.kind == NoticeKind.Failed) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.kind == NoticeKind.Failed) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        } else {
            Icon(Icons.Filled.Build, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun noticeText(item: ChatItem.Notice): String {
    val own = item.text
    return when (item.kind) {
        NoticeKind.Info -> own.orEmpty()

        NoticeKind.ModelSwitched -> own ?: stringResource(R.string.feature_chat_notice_model)

        NoticeKind.PersonalitySwitched -> own ?: stringResource(R.string.feature_chat_notice_personality)

        NoticeKind.Resumed -> own ?: stringResource(R.string.feature_chat_notice_resumed)

        NoticeKind.BackgroundAgentsFinished -> own ?: stringResource(R.string.feature_chat_notice_agents)

        NoticeKind.BackgroundProcessFinished -> own ?: stringResource(R.string.feature_chat_notice_process)

        NoticeKind.Stopped -> stringResource(R.string.feature_chat_notice_stopped)

        NoticeKind.Failed -> own?.let { stringResource(R.string.feature_chat_notice_failed_because, it) }
            ?: stringResource(R.string.feature_chat_notice_failed)
    }
}

/** A message of the user's own that Hermes does not have yet reads faded. */
private const val PENDING_ALPHA = 0.6f

/** Tool text is cut on screen after this many lines: results can run to thousands. */
private const val TOOL_TEXT_MAX_LINES = 40

private const val TODO_INDENT = 16

/** Reads the reply aloud; while it's read, stops it, with a spinner while the voice gets ready. */
@Composable
private fun ReadAloudButton(phase: ReadAloudState.Phase?, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        when (phase) {
            null -> Icon(
                painterResource(R.drawable.feature_chat_ic_speaker),
                contentDescription = stringResource(R.string.feature_chat_read_aloud),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )

            ReadAloudState.Phase.Preparing -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)

            else -> {
                val stop = stringResource(R.string.feature_chat_read_aloud_stop)
                Box(
                    Modifier
                        .size(12.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                        .semantics { contentDescription = stop },
                )
            }
        }
    }
}
