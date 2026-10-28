package lab.dawnforge.hermes.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import lab.dawnforge.hermes.core.chat.AgentStatus
import lab.dawnforge.hermes.core.chat.ContextBreakdown
import lab.dawnforge.hermes.core.chat.DelegateAgent
import lab.dawnforge.hermes.core.ui.failureText

/** What the sub-agents sheet can ask of its view model. */
internal class AgentActions(
    val onOpen: () -> Unit,
    val onClose: () -> Unit,
    val onFollow: (String?) -> Unit,
    /** Keeps the followed agent's output fresh while it shows. */
    val followOutput: suspend (String) -> Unit,
    val onAskSteer: (DelegateAgent?) -> Unit,
    val onSteer: (String) -> Unit,
    val onStop: (DelegateAgent) -> Unit,
)

/**
 * The sub-agents a chat's turns delegated to: what each is doing, and for one still working,
 * its live output, a note to steer it, or stopping it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentsSheet(sheet: AgentsSheet, agents: List<DelegateAgent>, actions: AgentActions) {
    sheet.following?.let { id -> LaunchedEffect(id) { actions.followOutput(id) } }
    ModalBottomSheet(onDismissRequest = actions.onClose) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            item {
                Text(
                    stringResource(R.string.feature_chat_agents_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            sheet.failure?.let { failure ->
                item {
                    Text(
                        failureText(failure),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
            }
            if (agents.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.feature_chat_agents_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(agents.asReversed(), key = { it.id }) { agent ->
                AgentCard(agent, following = sheet.following == agent.id, output = sheet.output, actions = actions)
            }
        }
    }
    sheet.steering?.let { agent ->
        SteerDialog(agent, onSend = actions.onSteer, onCancel = { actions.onAskSteer(null) })
    }
}

@Composable
private fun AgentCard(agent: DelegateAgent, following: Boolean, output: String?, actions: AgentActions) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (agent.isActive) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    agentStatusText(agent.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (agent.status == AgentStatus.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                val details = listOfNotNull(
                    agent.model,
                    agent.toolCount.takeIf { it > 0 }?.let { stringResource(R.string.feature_chat_agents_tools, it) },
                )
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(agent.goal.ifBlank { agent.id }, style = MaterialTheme.typography.bodyMedium)
            (agent.summary ?: agent.lastLine)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (agent.isActive && agent.subagentId != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val outputLabel = if (following) {
                        R.string.feature_chat_agents_hide_output
                    } else {
                        R.string.feature_chat_agents_output
                    }
                    TextButton(onClick = { actions.onFollow(if (following) null else agent.id) }) {
                        Text(stringResource(outputLabel))
                    }
                    TextButton(onClick = { actions.onAskSteer(agent) }) {
                        Text(stringResource(R.string.feature_chat_agents_steer))
                    }
                    TextButton(onClick = { actions.onStop(agent) }) {
                        Text(stringResource(R.string.feature_chat_agents_stop), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (following) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                ) {
                    when {
                        output == null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)

                        output.isBlank() -> Text(
                            stringResource(R.string.feature_chat_agents_no_output),
                            style = MaterialTheme.typography.bodySmall,
                        )

                        else -> SelectionContainer {
                            // The newest lines: the output can be long.
                            Text(
                                output.lines().takeLast(OUTPUT_LINES).joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SteerDialog(agent: DelegateAgent, onSend: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember(agent.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.feature_chat_agents_steer_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    agent.goal,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.feature_chat_agents_steer_label)) },
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.feature_chat_agents_steer_send))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.feature_chat_model_cancel)) } },
    )
}

@Composable
private fun agentStatusText(status: AgentStatus): String = stringResource(
    when (status) {
        AgentStatus.Queued -> R.string.feature_chat_agents_queued
        AgentStatus.Running -> R.string.feature_chat_agents_running
        AgentStatus.Completed -> R.string.feature_chat_agents_completed
        AgentStatus.Failed -> R.string.feature_chat_agents_failed
        AgentStatus.Interrupted -> R.string.feature_chat_agents_interrupted
    },
)

/** How the chat's context window is used: the total, and what takes it up. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextSheet(sheet: ContextSheet, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.feature_chat_context_title), style = MaterialTheme.typography.titleMedium)
            val breakdown = sheet.breakdown
            when {
                sheet.failure != null -> Text(failureText(sheet.failure), color = MaterialTheme.colorScheme.error)

                breakdown == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                else -> ContextDetails(breakdown)
            }
        }
    }
}

@Composable
private fun ContextDetails(breakdown: ContextBreakdown) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Text(
        if (breakdown.max > 0) {
            stringResource(
                R.string.feature_chat_context_used,
                numbers.format(breakdown.used),
                numbers.format(breakdown.max),
                breakdown.percent,
            )
        } else {
            stringResource(R.string.feature_chat_context_used_unknown, numbers.format(breakdown.used))
        },
        style = MaterialTheme.typography.bodyLarge,
    )
    if (breakdown.max > 0) {
        LinearProgressIndicator(
            progress = { (breakdown.used.toFloat() / breakdown.max).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (breakdown.estimated) {
        Text(
            stringResource(R.string.feature_chat_context_estimated),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (breakdown.categories.isNotEmpty()) HorizontalDivider()
    for (category in breakdown.categories) {
        Row(Modifier.fillMaxWidth()) {
            Text(category.label, modifier = Modifier.weight(1f))
            Text(numbers.format(category.tokens), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val OUTPUT_LINES = 40
