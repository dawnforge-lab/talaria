package lab.dawnforge.hermes.core.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import lab.dawnforge.hermes.core.model.DashboardId

/**
 * An answer given on a notification, on its way from the button to Hermes: which notification it
 * came from (to update it), where it goes, and what was answered.
 */
data class NotificationAnswer(
    val tag: String,
    val id: Int,
    /** The notification's own title, kept on the updated one. */
    val title: String,
    val target: HermesNotification.Target,
    val kind: Kind,
    /** For an approval: the chat's stored id. For a question: the request id. */
    val subject: String,
    val gatewayId: String,
    val questionId: String,
    /** `once` or `deny`, the chosen label, or the typed text. */
    val answer: String,
) {
    enum class Kind { Approval, Question }

    /** For the notification's action intents: its text fields and its id, as [from] reads them back. */
    fun toBundle(): Bundle = Bundle().apply {
        texts().forEach { (key, value) -> putString(key, value) }
        putInt("id", id)
    }

    fun toData(): Data = Data.Builder().apply {
        texts().forEach { (key, value) -> putString(key, value) }
        putInt("id", id)
    }.build()

    private fun texts(): List<Pair<String, String?>> = listOf(
        "tag" to tag,
        "title" to title,
        "dashboard" to target.dashboardId.value,
        "profile" to target.profile,
        "session" to target.sessionId,
        "kind" to kind.name,
        "subject" to subject,
        "gateway" to gatewayId,
        "qid" to questionId,
        "answer" to answer,
    )

    companion object {
        fun from(bundle: Bundle): NotificationAnswer? = from(
            Data.Builder().apply {
                for (key in TEXT_KEYS) bundle.getString(key)?.let { putString(key, it) }
                putInt("id", bundle.getInt("id"))
            }.build(),
        )

        private val TEXT_KEYS =
            listOf("tag", "title", "dashboard", "profile", "session", "kind", "subject", "gateway", "qid", "answer")

        fun from(data: Data): NotificationAnswer? {
            val target = HermesNotification.Target(
                DashboardId(data.getString("dashboard") ?: return null),
                data.getString("profile") ?: return null,
                data.getString("session"),
            )
            return NotificationAnswer(
                tag = data.getString("tag") ?: return null,
                id = data.getInt("id", 0),
                title = data.getString("title").orEmpty(),
                target = target,
                kind =
                    data.getString("kind")?.let { name -> Kind.entries.firstOrNull { it.name == name } } ?: return null,
                subject = data.getString("subject") ?: return null,
                gatewayId = data.getString("gateway").orEmpty(),
                questionId = data.getString("qid").orEmpty(),
                answer = data.getString("answer") ?: return null,
            )
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PushActionsEntryPoint {
    fun answers(): PushAnswers

    fun notifier(): PushNotifier
}

/**
 * A tapped Allow, Deny or choice, or a typed reply. The answer is handed to [AnswerWorker] at
 * once, as a receiver has only seconds; the notification says it is on its way meanwhile.
 */
class PushActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val typed = RemoteInput.getResultsFromIntent(
            intent,
        )?.getCharSequence(PushNotifier.KEY_REPLY)?.toString()?.trim()
        if (typed != null && typed.isEmpty()) return
        val given = intent.getBundleExtra(PushNotifier.EXTRA_ANSWER)?.let(NotificationAnswer::from) ?: return
        // A reply's answer is what was typed; a button's, the choice it carries.
        val answer = if (typed != null) given.copy(answer = typed) else given
        val entry = EntryPointAccessors.fromApplication(context, PushActionsEntryPoint::class.java)
        entry.notifier().showSending(answer)
        AnswerWorker.enqueue(context, answer)
    }
}

/** Sends one answer, as expedited work so it goes out even with the app closed. */
class AnswerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val entry = EntryPointAccessors.fromApplication(context, PushActionsEntryPoint::class.java)

    override suspend fun doWork(): Result {
        val answer = NotificationAnswer.from(inputData) ?: return Result.failure()
        val answers = entry.answers()
        val outcome = when (answer.kind) {
            NotificationAnswer.Kind.Approval ->
                answers.approve(answer.target.dashboardId, answer.target.profile, answer.subject, answer.answer)

            NotificationAnswer.Kind.Question ->
                answers.answer(answer.gatewayId, answer.subject, answer.questionId, answer.answer)
        }
        if (outcome is AnswerOutcome.Failed && runAttemptCount < MAX_TRIES - 1) return Result.retry()
        entry.notifier().showOutcome(answer, outcome)
        return Result.success()
    }

    /** Expedited work runs as a foreground service before Android 12, which needs a notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(SENDING_ID, entry.notifier().sendingForeground())

    companion object {
        private const val MAX_TRIES = 3
        private const val SENDING_ID = 7_100

        fun enqueue(context: Context, answer: NotificationAnswer) {
            val request = OneTimeWorkRequestBuilder<AnswerWorker>()
                .setInputData(answer.toData())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            // One answer per notification: a second tap while the first is on its way is dropped.
            WorkManager.getInstance(
                context,
            ).enqueueUniqueWork("answer:${answer.tag}:${answer.id}", ExistingWorkPolicy.KEEP, request)
        }
    }
}
