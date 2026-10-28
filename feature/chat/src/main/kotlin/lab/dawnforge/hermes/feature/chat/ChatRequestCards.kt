package lab.dawnforge.hermes.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import lab.dawnforge.hermes.core.chat.ApprovalChoice
import lab.dawnforge.hermes.core.chat.ChatRequest

/** How the cards answer; the screen passes the view model's methods. */
internal class RequestActions(
    val onApprove: (ChatRequest.Approval, ApprovalChoice) -> Unit,
    val onAnswerQuestions: (ChatRequest.Clarify, Map<String, List<String>>) -> Unit,
    val onSkipQuestions: (ChatRequest.Clarify) -> Unit,
    val onAnswerValue: (ChatRequest, String) -> Unit,
)

/** What Hermes is waiting on the user for, as a card each. */
@Composable
internal fun RequestCard(request: ChatRequest, actions: RequestActions, modifier: Modifier = Modifier) {
    // On the screen's own surface, so every button keeps its usual contrast; the border marks it out.
    OutlinedCard(
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (request) {
                is ChatRequest.Approval -> ApprovalCard(request, actions)

                is ChatRequest.Clarify -> ClarifyCard(request, actions)

                is ChatRequest.Sudo -> ValueCard(
                    request,
                    title = stringResource(R.string.feature_chat_sudo_title),
                    detail = request.command,
                    label = stringResource(R.string.feature_chat_sudo_label),
                    actions = actions,
                )

                is ChatRequest.Secret -> ValueCard(
                    request,
                    title = request.envVar?.let { stringResource(R.string.feature_chat_secret_title_named, it) }
                        ?: stringResource(R.string.feature_chat_secret_title),
                    detail = request.prompt,
                    label = stringResource(R.string.feature_chat_secret_label),
                    actions = actions,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApprovalCard(request: ChatRequest.Approval, actions: RequestActions) {
    Text(
        stringResource(R.string.feature_chat_approval_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    request.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    if (request.command.isNotBlank()) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
            Text(
                request.command,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp),
            )
        }
    }
    if (request.smartDenied) {
        Text(
            stringResource(R.string.feature_chat_approval_smart_denied),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        request.choices.forEach { choice ->
            val label = stringResource(
                when (choice) {
                    ApprovalChoice.Once -> R.string.feature_chat_approval_once
                    ApprovalChoice.Session -> R.string.feature_chat_approval_session
                    ApprovalChoice.Always -> R.string.feature_chat_approval_always
                    ApprovalChoice.Deny -> R.string.feature_chat_approval_deny
                },
            )
            when (choice) {
                ApprovalChoice.Once -> Button(onClick = { actions.onApprove(request, choice) }) { Text(label) }
                ApprovalChoice.Deny -> TextButton(onClick = { actions.onApprove(request, choice) }) { Text(label) }
                else -> OutlinedButton(onClick = { actions.onApprove(request, choice) }) { Text(label) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClarifyCard(request: ChatRequest.Clarify, actions: RequestActions) {
    // Chosen answers and typed ones, by question id.
    val chosen = remember(request.id) { mutableStateMapOf<String, List<String>>() }
    val typed = remember(request.id) { mutableStateMapOf<String, String>() }
    Text(
        stringResource(
            if (request.questions.size ==
                1
            ) {
                R.string.feature_chat_clarify_title
            } else {
                R.string.feature_chat_clarify_title_many
            },
        ),
        style = MaterialTheme.typography.titleSmall,
    )
    request.questions.forEach { question ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(question.text, style = MaterialTheme.typography.bodyMedium)
            val answered = question.answered
            if (answered != null) {
                Text(
                    stringResource(R.string.feature_chat_clarify_answered, answered),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                question.choices.forEach { choice ->
                    val selected = chosen[question.qid].orEmpty().contains(choice)
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val current = chosen[question.qid].orEmpty()
                            chosen[question.qid] = when {
                                question.multiSelect && selected -> current - choice
                                question.multiSelect -> current + choice
                                selected -> emptyList()
                                else -> listOf(choice)
                            }
                            if (!question.multiSelect) typed.remove(question.qid)
                        },
                        label = { Text(choice) },
                    )
                }
            }
            OutlinedTextField(
                value = typed[question.qid].orEmpty(),
                onValueChange = {
                    typed[question.qid] = it
                    if (!question.multiSelect && it.isNotBlank()) chosen.remove(question.qid)
                },
                placeholder = { Text(stringResource(R.string.feature_chat_clarify_other)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val answers = request.questions.associate { question ->
                val own = typed[question.qid]?.trim().orEmpty()
                question.qid to (chosen[question.qid].orEmpty() + listOfNotNull(own.takeIf(String::isNotEmpty)))
            }
            actions.onAnswerQuestions(request, answers)
        }) { Text(stringResource(R.string.feature_chat_clarify_send)) }
        TextButton(onClick = { actions.onSkipQuestions(request) }) {
            Text(stringResource(R.string.feature_chat_clarify_skip))
        }
    }
}

/** A password or a secret: typed hidden, sent once, never kept (not even in saved screen state). */
@Composable
private fun ValueCard(request: ChatRequest, title: String, detail: String?, label: String, actions: RequestActions) {
    var value by remember(request.id) { mutableStateOf("") }
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    detail?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 6)
    }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { actions.onAnswerValue(request, value) }, enabled = value.isNotEmpty()) {
            Text(stringResource(R.string.feature_chat_value_send))
        }
        TextButton(onClick = { actions.onAnswerValue(request, "") }) {
            Text(stringResource(R.string.feature_chat_value_skip))
        }
    }
}
