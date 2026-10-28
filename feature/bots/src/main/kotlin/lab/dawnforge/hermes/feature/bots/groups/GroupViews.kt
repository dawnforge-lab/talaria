package lab.dawnforge.hermes.feature.bots.groups

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import lab.dawnforge.hermes.core.data.bots.BotColor
import lab.dawnforge.hermes.core.data.groups.GroupApproval
import lab.dawnforge.hermes.core.data.groups.GroupEvent
import lab.dawnforge.hermes.core.data.groups.GroupMember
import lab.dawnforge.hermes.core.data.groups.GroupMentions
import lab.dawnforge.hermes.core.data.groups.GroupMessageParts
import lab.dawnforge.hermes.core.data.groups.GroupRoomTurns
import lab.dawnforge.hermes.core.ui.BotAvatar
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.feature.bots.R

/** A room's picture, else up to three of its members' faces overlapping. */
@Composable
internal fun GroupRoomIcon(picture: ByteArray?, members: List<GroupMember>, size: Dp, name: String = "") {
    if (picture != null || members.isEmpty()) {
        BotAvatar(name.ifBlank { "#" }, BotColor.fallback(name), picture, size)
        return
    }
    val shown = members.take(MAX_FACES)
    val face = size * FACE_SCALE
    Box(Modifier.size(size)) {
        shown.forEachIndexed { index, member ->
            val step = if (shown.size == 1) 0.dp else (size - face) / (shown.size - 1)
            Box(Modifier.offset(x = step * index, y = if (index % 2 == 0) 0.dp else size - face)) {
                MemberAvatar(member, member.label.orEmpty(), face)
            }
        }
    }
}

/** A member's face: its initial on its bot's colour. */
@Composable
internal fun MemberAvatar(member: GroupMember?, name: String, size: Dp) {
    val key = member?.profile ?: member?.identityKey ?: name
    BotAvatar(name.ifBlank { "?" }, BotColor.fallback(key), null, size)
}

/** The member's name for captions, never empty. */
@Composable
internal fun memberName(member: GroupMember?): String =
    member?.label ?: stringResource(R.string.feature_bots_group_a_member)

/**
 * [text] as iOS shows a room message: inline Markdown (bold, italics, code, links), with the room's
 * real @mentions in the accent colour. Unknown tags and e-mail addresses stay plain.
 */
@Composable
internal fun mentionText(text: String, members: List<GroupMember>, accent: Color): AnnotatedString {
    val code = MaterialTheme.colorScheme.onSurface.copy(alpha = CODE_WASH)
    val styled = inlineMarkdown(text, code)
    // The markup is gone from what shows, so mentions are found there.
    return buildAnnotatedString {
        append(styled)
        for (match in GroupMentions.SCAN.findAll(styled.text)) {
            if (GroupMentions.classify(match.groupValues[1], members) == null) continue
            addStyle(
                SpanStyle(color = accent, fontWeight = FontWeight.SemiBold),
                match.range.first,
                match.range.last + 1,
            )
        }
    }
}

/** Inline Markdown only: `**bold**`, `*italics*` or `_italics_`, `` `code` `` and `[links](url)`. */
internal fun inlineMarkdown(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val char = text[i]
        val doubled = text.startsWith("**", i) || text.startsWith("__", i)
        val end = when {
            char == '`' -> text.indexOf('`', i + 1).takeIf { it > i + 1 }

            doubled -> text.indexOf(text.substring(i, i + 2), i + 2).takeIf { it > i + 2 }

            (char == '*' || char == '_') && text.getOrNull(i + 1)?.isWhitespace() == false &&
                (char == '*' || i == 0 || !text[i - 1].isLetterOrDigit()) ->
                text.indexOf(char, i + 1).takeIf { it > i + 1 && !text[it - 1].isWhitespace() }

            char == '[' -> text.indexOf("](", i + 1).takeIf { it > i + 1 && text.indexOf(')', it + 2) > it + 2 }

            else -> null
        }
        if (end == null) {
            append(char)
            i++
            continue
        }
        when {
            char == '`' -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                append(text.substring(i + 1, end))
            }.also { i = end + 1 }

            doubled -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(inlineMarkdown(text.substring(i + 2, end), codeBackground))
            }.also { i = end + 2 }

            char == '[' -> {
                val close = text.indexOf(')', end + 2)
                val url = text.substring(end + 2, close).trim()
                val label = inlineMarkdown(text.substring(i + 1, end), codeBackground)
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    withLink(LinkAnnotation.Url(url)) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(label) }
                    }
                } else {
                    append(label)
                }
                i = close + 1
            }

            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(inlineMarkdown(text.substring(i + 1, end), codeBackground))
            }.also { i = end + 1 }
        }
    }
}

/** "14:05", in the phone's own format. */
@Composable
internal fun bubbleTime(seconds: Double): String =
    DateUtils.formatDateTime(LocalContext.current, (seconds * MILLIS).toLong(), DateUtils.FORMAT_SHOW_TIME)

@Composable
private fun FileChips(files: List<String>, color: Color) {
    files.forEach { file ->
        Text(
            stringResource(R.string.feature_bots_group_file, file),
            color = color,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

/** The user's message on the trailing side; [footer] carries an unsettled send's state. */
@Composable
internal fun UserBubble(
    text: String,
    members: List<GroupMember>,
    timestamp: Double?,
    footer: @Composable () -> Unit = {},
) {
    val parts = GroupMessageParts.of(text)
    val onBubble = MaterialTheme.colorScheme.onPrimaryContainer
    Column(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp)) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FileChips(parts.files, onBubble)
                if (parts.text.isNotEmpty()) {
                    SelectionContainer {
                        Text(mentionText(parts.text, members, MaterialTheme.colorScheme.primary), color = onBubble)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            footer()
            timestamp?.let { Caption(bubbleTime(it)) }
        }
    }
}

/** A member's message: its face and name (once per run of its messages), then its bubble. */
@Composable
internal fun MemberBubble(event: GroupEvent, members: List<GroupMember>, continuesRun: Boolean) {
    val member = GroupRoomTurns.member(event, members)
    val name = event.actor.label(members).ifEmpty { stringResource(R.string.feature_bots_group_member) }
    val parts = GroupMessageParts.of(event.text.orEmpty())
    Row(Modifier.fillMaxWidth().padding(end = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(AVATAR.dp)) { if (!continuesRun) MemberAvatar(member, name, AVATAR.dp) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!continuesRun) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Caption(bubbleTime(event.createdAt))
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp)) {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FileChips(parts.files, MaterialTheme.colorScheme.onSurfaceVariant)
                    if (parts.text.isNotEmpty()) {
                        SelectionContainer { Text(mentionText(parts.text, members, MaterialTheme.colorScheme.primary)) }
                    }
                }
            }
        }
    }
}

/** Two rows in a row from one member, or both from the user. */
internal fun sameSpeaker(previous: GroupEvent, next: GroupEvent): Boolean {
    if (previous.isUserMessage && next.isUserMessage) return true
    if (!previous.isMemberMessage || !next.isMemberMessage) return false
    val key = previous.actor.id.trim()
    return key.isNotEmpty() && key == next.actor.id.trim()
}

/** A gateway or system event, or one this app predates, as a small centred line. */
@Composable
internal fun SystemCaption(event: GroupEvent, members: List<GroupMember>) {
    val member = GroupRoomTurns.member(event, members)
    val name = member?.label
    val text = when (event.kind) {
        "room.renamed" -> stringResource(R.string.feature_bots_group_renamed)

        "room.members_changed" -> stringResource(R.string.feature_bots_group_members_changed)

        "room.disbanded" -> stringResource(R.string.feature_bots_group_disbanded)

        "room.stop_requested" -> stringResource(R.string.feature_bots_group_stop_requested)

        "room.activity" -> when ((event.payload["status"] as? JsonPrimitive)?.contentOrNull) {
            "settled" -> stringResource(R.string.feature_bots_group_settled)
            "bounded" -> stringResource(R.string.feature_bots_group_bounded)
            else -> stringResource(R.string.feature_bots_group_updated)
        }

        "turn.settled" -> named(name, R.string.feature_bots_group_passed, R.string.feature_bots_group_passed_someone)

        "turn.failed" -> named(name, R.string.feature_bots_group_failed, R.string.feature_bots_group_failed_someone)

        "turn.cancelled" -> named(
            name,
            R.string.feature_bots_group_cancelled,
            R.string.feature_bots_group_cancelled_someone,
        )

        "member.unavailable" -> named(
            name,
            R.string.feature_bots_group_unavailable,
            R.string.feature_bots_group_unavailable_someone,
        )

        else -> stringResource(R.string.feature_bots_group_updated)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

@Composable
private fun named(name: String?, withName: Int, without: Int): String =
    if (name.isNullOrEmpty()) stringResource(without) else stringResource(withName, name)

/** The member whose turn is running, as a placeholder bubble. */
@Composable
internal fun RespondingRow(member: GroupMember) {
    val name = memberName(member)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        MemberAvatar(member, name, AVATAR.dp)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp)) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.feature_bots_group_responding, name),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** A member's tool call the room holds for the user: Run (once) or Reject. */
@Composable
internal fun ApprovalCard(approval: GroupApproval, name: String, answer: ApprovalAnswer?, onAnswer: (String) -> Unit) {
    val sending = answer == ApprovalAnswer.Sending
    val failed = answer as? ApprovalAnswer.Failed
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            when {
                                sending -> R.string.feature_bots_group_approval_sending
                                failed != null -> R.string.feature_bots_group_approval_retry
                                else -> R.string.feature_bots_group_approval_needed
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (failed !=
                            null
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    )
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    if (approval.description.isNotEmpty()) {
                        Text(
                            approval.description,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = DESCRIPTION_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (approval.command.isNotEmpty()) {
                SelectionContainer {
                    Text(
                        approval.command,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = COMMAND_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            RoundedCornerShape(10.dp),
                        ).padding(10.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onAnswer(GroupApproval.ONCE) },
                    enabled = approval.allows(GroupApproval.ONCE) && !sending,
                ) { Text(stringResource(R.string.feature_bots_group_run)) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = { onAnswer(GroupApproval.DENY) },
                    enabled = approval.allows(GroupApproval.DENY) && !sending,
                ) { Text(stringResource(R.string.feature_bots_group_reject), color = MaterialTheme.colorScheme.error) }
            }
            failed?.let {
                Text(
                    it.failure?.let { failure -> failureText(failure) }
                        ?: stringResource(R.string.feature_bots_group_approval_gone),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
internal fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val AVATAR = 30
private const val MAX_FACES = 3
private const val FACE_SCALE = 0.62f
private const val MILLIS = 1000.0

// Short enough that Run and Reject stay in view.
private const val COMMAND_LINES = 4
private const val DESCRIPTION_LINES = 3
private const val CODE_WASH = 0.08f
