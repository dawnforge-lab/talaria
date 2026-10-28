package lab.dawnforge.hermes.core.push

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import lab.dawnforge.hermes.core.data.plugin.AnsweredCall

/**
 * Shows Hermes notifications in four channels the user can tune in Android's settings. One chat's
 * notification of a kind replaces the older one; on the lock screen only the generic copy shows.
 */
class PushNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val channels = NotificationKind.entries.map { kind ->
            val (name, description, importance) = when (kind) {
                NotificationKind.NeedsYou -> Triple(
                    R.string.core_push_channel_needs_you,
                    R.string.core_push_channel_needs_you_description,
                    NotificationManager.IMPORTANCE_HIGH,
                )

                NotificationKind.Reply -> Triple(
                    R.string.core_push_channel_replies,
                    R.string.core_push_channel_replies_description,
                    NotificationManager.IMPORTANCE_DEFAULT,
                )

                NotificationKind.Failure -> Triple(
                    R.string.core_push_channel_failures,
                    R.string.core_push_channel_failures_description,
                    NotificationManager.IMPORTANCE_DEFAULT,
                )

                NotificationKind.BackgroundJob -> Triple(
                    R.string.core_push_channel_background,
                    R.string.core_push_channel_background_description,
                    NotificationManager.IMPORTANCE_LOW,
                )

                NotificationKind.Call -> Triple(
                    R.string.core_push_channel_calls,
                    R.string.core_push_channel_calls_description,
                    NotificationManager.IMPORTANCE_HIGH,
                )
            }
            NotificationChannel(channelId(kind), context.getString(name), importance).apply {
                this.description = context.getString(description)
                // A call rings with the phone's ringtone, as a call does.
                if (kind == NotificationKind.Call) {
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableVibration(true)
                }
            }
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(channels)
    }

    /** False when the user turned the app's notifications off, or never allowed them (Android 13+). */
    fun canPost(): Boolean = manager.areNotificationsEnabled()

    // Checked through canPost(); a refused permission makes notify() a no-op, never a crash.
    @SuppressLint("MissingPermission")
    fun show(notification: HermesNotification) {
        if (!canPost()) return
        val (genericTitle, genericBody) = genericCopy(notification.type)
        val title = notification.title ?: genericTitle
        val body = notification.body ?: genericBody
        val public = builder(notification.kind)
            .setContentTitle(genericTitle)
            .setContentText(genericBody)
            .build()
        val id = notification.kind.ordinal + 1
        val shown = builder(notification.kind)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(openIntent(notification.target, notification.thread))
            .setAutoCancel(true)
        val target = notification.target
        if (target != null) {
            val posted = Posted(notification.thread, id, title, target)
            when (val action = notification.action) {
                is NotificationAction.Approval -> addApprovalActions(shown, action, posted)
                is NotificationAction.Question -> addQuestionActions(shown, action, posted)
                null -> Unit
            }
        }
        manager.notify(notification.thread, id, shown.build())
    }

    /** The answer is on its way: the buttons go, so it can't be sent twice. */
    @SuppressLint("MissingPermission")
    fun showSending(answer: NotificationAnswer) {
        if (!canPost()) return
        val sending = builder(NotificationKind.NeedsYou)
            .setContentTitle(answer.title)
            .setContentText(context.getString(R.string.core_push_sending))
            .setProgress(0, 0, true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openIntent(answer.target, answer.tag))
            .build()
        manager.notify(answer.tag, answer.id, sending)
    }

    /**
     * How the answer went. A sent one stays a minute and goes; one that didn't reach Hermes stays,
     * and leads to the chat to answer there.
     */
    @SuppressLint("MissingPermission")
    fun showOutcome(answer: NotificationAnswer, outcome: AnswerOutcome) {
        if (!canPost()) return
        val text = when (outcome) {
            AnswerOutcome.Answered -> when {
                answer.kind == NotificationAnswer.Kind.Question ->
                    context.getString(R.string.core_push_answered, answer.answer)

                answer.answer == "deny" -> context.getString(R.string.core_push_denied)

                else -> context.getString(R.string.core_push_allowed)
            }

            AnswerOutcome.AlreadySettled -> context.getString(R.string.core_push_settled)

            is AnswerOutcome.Failed -> context.getString(R.string.core_push_not_sent)
        }
        val result = builder(NotificationKind.NeedsYou)
            .setContentTitle(answer.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openIntent(answer.target, answer.tag))
            .setAutoCancel(true)
            .apply { if (outcome !is AnswerOutcome.Failed) setTimeoutAfter(OUTCOME_SHOWN_MS) }
            .build()
        manager.notify(answer.tag, answer.id, result)
    }

    // ---- Calls from Hermes -----------------------------------------------------------------------

    /**
     * A ringing call, as the phone shows its own: the caller, what it's about, Answer and Decline,
     * and the full-screen call screen when the phone is locked. It rings until it's answered, turned
     * down or rings out.
     */
    fun ringing(
        caller: String,
        summary: String,
        answer: PendingIntent,
        decline: PendingIntent,
        fullScreen: PendingIntent,
    ): Notification {
        val person = Person.Builder().setName(caller).setImportant(true).build()
        return builder(NotificationKind.Call)
            .setContentTitle(caller)
            .setContentText(summary)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer))
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }
    }

    /**
     * A call from Hermes as a notification, where it can't ring (a voice conversation is running,
     * or ringing is off): Talk answers it, the notification itself opens the chat.
     */
    @SuppressLint("MissingPermission")
    fun showTalk(notification: HermesNotification, summary: String, call: AnsweredCall) {
        if (!canPost()) return
        val target = notification.target ?: return show(notification)
        val (genericTitle, genericBody) = genericCopy(notification.type)
        val shown = builder(NotificationKind.NeedsYou)
            .setContentTitle(genericTitle)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                builder(NotificationKind.NeedsYou).setContentTitle(genericTitle).setContentText(genericBody).build(),
            )
            .setContentIntent(openIntent(target, notification.thread))
            .addAction(
                0,
                context.getString(R.string.core_push_call_talk),
                talkIntent(target, call, notification.thread),
            )
            .setAutoCancel(true)
            .build()
        manager.notify(notification.thread, CALL_NOTIFICATION, shown)
    }

    /** A call that rang out, was turned down, or came too late to ring. */
    @SuppressLint("MissingPermission")
    fun showMissed(notification: HermesNotification, title: String, summary: String, call: AnsweredCall) {
        if (!canPost()) return
        val target = notification.target ?: return
        val shown = builder(NotificationKind.NeedsYou)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(builder(NotificationKind.NeedsYou).setContentTitle(title).build())
            .setContentIntent(openIntent(target, notification.thread))
            .addAction(
                0,
                context.getString(R.string.core_push_call_talk),
                talkIntent(target, call, notification.thread),
            )
            .setAutoCancel(true)
            .build()
        manager.notify(notification.thread, CALL_NOTIFICATION, shown)
    }

    /** The app at the call's chat, talking: what Answer and Talk lead to. */
    fun talkIntent(target: HermesNotification.Target, call: AnsweredCall, thread: String): PendingIntent? {
        val activity = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component ?: return null
        val talk = Intent(PushLink.ACTION_TALK)
            .setComponent(activity)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        PushLink.put(talk, target)
        PushLink.putCall(talk, call)
        return PendingIntent.getActivity(
            context,
            "talk:$thread".hashCode(),
            talk,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** What shows while an answer is sent as a foreground job (before Android 12). */
    fun sendingForeground(): Notification = builder(NotificationKind.NeedsYou)
        .setContentTitle(context.getString(R.string.core_push_sending_foreground))
        .setSilent(true)
        .build()

    /** A notification as posted: where an answer from it updates it, and what it leads to. */
    private class Posted(val tag: String, val id: Int, val title: String, val target: HermesNotification.Target) {
        fun answer(
            kind: NotificationAnswer.Kind,
            subject: String,
            text: String,
            gatewayId: String = "",
            questionId: String = "",
        ) = NotificationAnswer(tag, id, title, target, kind, subject, gatewayId, questionId, text)
    }

    private fun addApprovalActions(
        builder: NotificationCompat.Builder,
        approval: NotificationAction.Approval,
        posted: Posted,
    ) {
        fun answer(choice: String) = posted.answer(NotificationAnswer.Kind.Approval, approval.sessionKey, choice)
        if ("once" in approval.choices) {
            builder.addAction(0, context.getString(R.string.core_push_action_allow), answerIntent(answer("once"), 0))
        }
        builder.addAction(0, context.getString(R.string.core_push_action_deny), answerIntent(answer("deny"), 1))
    }

    /**
     * A question's choices as buttons when two or fewer fit beside the reply field; otherwise only
     * the field, where any answer can be typed.
     */
    private fun addQuestionActions(
        builder: NotificationCompat.Builder,
        question: NotificationAction.Question,
        posted: Posted,
    ) {
        fun answer(text: String) = posted.answer(
            NotificationAnswer.Kind.Question,
            question.requestId,
            text,
            question.gatewayId,
            question.questionId,
        )
        if (question.choices.size <= MAX_CHOICE_BUTTONS) {
            question.choices.forEachIndexed { index, choice ->
                builder.addAction(0, choice, answerIntent(answer(choice), index))
            }
        }
        val label = question.question.ifEmpty { context.getString(R.string.core_push_action_reply) }
        val reply = RemoteInput.Builder(KEY_REPLY).setLabel(label).build()
        val replyAction = NotificationCompat.Action.Builder(
            0,
            context.getString(R.string.core_push_action_reply),
            answerIntent(answer(""), MAX_CHOICE_BUTTONS, mutable = true),
        )
            .addRemoteInput(reply)
            .setAllowGeneratedReplies(false)
            .build()
        builder.addAction(replyAction)
    }

    /**
     * The receiver that sends [answer]. A reply's intent is mutable, as Android fills in the typed
     * text; a button's is not.
     */
    private fun answerIntent(answer: NotificationAnswer, index: Int, mutable: Boolean = false): PendingIntent {
        val intent = Intent(context, PushActionReceiver::class.java)
            .setAction(ACTION_ANSWER)
            .putExtra(EXTRA_ANSWER, answer.toBundle())
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, "${answer.tag}:${answer.id}:$index".hashCode(), intent, flags)
    }

    private fun builder(kind: NotificationKind): NotificationCompat.Builder {
        val needsYou = kind == NotificationKind.NeedsYou
        return NotificationCompat.Builder(context, channelId(kind))
            .setSmallIcon(R.drawable.core_push_notification_icon)
            .setCategory(
                when {
                    kind == NotificationKind.Call -> NotificationCompat.CATEGORY_CALL
                    needsYou -> NotificationCompat.CATEGORY_REMINDER
                    else -> NotificationCompat.CATEGORY_MESSAGE
                },
            )
            .setPriority(
                when (kind) {
                    NotificationKind.Call -> NotificationCompat.PRIORITY_MAX
                    NotificationKind.NeedsYou -> NotificationCompat.PRIORITY_HIGH
                    else -> NotificationCompat.PRIORITY_DEFAULT
                },
            )
    }

    /**
     * The app, at the notification's chat when the push verified. Its own action, not the
     * launcher's: an intent equal to the task's root only brings the task forward, and the
     * activity never sees the chat to open.
     */
    private fun openIntent(target: HermesNotification.Target?, thread: String): PendingIntent? {
        val activity = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component ?: return null
        val open = Intent(PushLink.ACTION_OPEN)
            .setComponent(activity)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        target?.let { PushLink.put(open, it) }
        return PendingIntent.getActivity(
            context,
            thread.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun genericCopy(type: String): Pair<String, String> {
        val (title, body) = when (type) {
            "approval.needed" -> R.string.core_push_approval_title to R.string.core_push_approval_body
            "input.needed" -> R.string.core_push_input_title to R.string.core_push_input_body
            "phone.changes" -> R.string.core_push_confirm_title to R.string.core_push_confirm_body
            "turn.failed" -> R.string.core_push_failed_title to R.string.core_push_failed_body
            "background_task.finished" -> R.string.core_push_background_title to R.string.core_push_background_body
            "call.requested" -> R.string.core_push_call_title to R.string.core_push_call_body
            else -> R.string.core_push_reply_title to R.string.core_push_reply_body
        }
        return context.getString(title) to context.getString(body)
    }

    companion object {
        /** The reply field's result key. */
        const val KEY_REPLY = "lab.dawnforge.hermes.push.REPLY"
        const val EXTRA_ANSWER = "lab.dawnforge.hermes.push.ANSWER"
        private const val ACTION_ANSWER = "lab.dawnforge.hermes.push.ANSWER"

        /** Android shows three actions; two choices and the reply field fit. */
        private const val MAX_CHOICE_BUTTONS = 2
        private const val OUTCOME_SHOWN_MS = 60_000L

        /** A call's notifications (Talk, missed) replace each other in their chat. */
        private const val CALL_NOTIFICATION = 100
    }

    private fun channelId(kind: NotificationKind) = when (kind) {
        NotificationKind.NeedsYou -> "hermes.needs_you"
        NotificationKind.Reply -> "hermes.replies"
        NotificationKind.Failure -> "hermes.failures"
        NotificationKind.BackgroundJob -> "hermes.background"
        NotificationKind.Call -> "hermes.calls"
    }
}
