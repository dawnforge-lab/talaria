package lab.dawnforge.hermes.core.push.live

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.push.HermesNotification
import lab.dawnforge.hermes.core.push.PushLink
import lab.dawnforge.hermes.core.push.R

/**
 * A live turn's notification, as iOS's Live Activity: the chat and profile, what the agent is
 * doing (iOS's wording), the time it has run, the tools it used and the context it fills. While it
 * runs it is ongoing (a Live Update where Android offers them); a finished turn stays five minutes.
 */
class LiveTurnNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun show(turn: LiveTurn, update: LiveTurnUpdate) {
        if (!manager.areNotificationsEnabled()) return
        channel()
        val waiting = update.phase == "approval" || update.phase == "input"
        val details = listOfNotNull(
            update.tools.takeIf { it > 0 }?.let {
                context.resources.getQuantityString(R.plurals.core_push_live_tools, it, it)
            },
            update.context?.let { context.getString(R.string.core_push_live_context, it) },
        ).joinToString(" · ")
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.core_push_notification_icon)
            .setContentTitle(
                turn.title?.takeIf(String::isNotBlank) ?: context.getString(R.string.core_push_live_untitled),
            )
            .setContentText(status(update))
            .setSubText(turn.profile)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(!waiting)
            .setSilent(!waiting)
            .setContentIntent(openIntent(turn))
            .setWhen(turn.startedAtMs)
            .setShowWhen(true)
        if (details.isNotEmpty()) {
            builder.setStyle(
                NotificationCompat.BigTextStyle().bigText("${status(update)}\n$details"),
            )
        }
        if (update.ended) {
            // The clock stops at the end, which stays five minutes as on iOS.
            builder.setUsesChronometer(false)
                .setWhen(update.endedAtSeconds?.let { it * MILLIS } ?: System.currentTimeMillis())
                .setOngoing(false)
                .setAutoCancel(true)
                .setTimeoutAfter(DISMISS_AFTER_END_MS)
        } else {
            builder.setUsesChronometer(true)
                .setOngoing(true)
                .setRequestPromotedOngoing(true)
                .setShortCriticalText(
                    context.getString(
                        if (waiting) R.string.core_push_live_short_waiting else R.string.core_push_live_short,
                    ),
                )
        }
        runCatching { manager.notify(TAG, turn.activity.hashCode(), builder.build()) }
    }

    fun cancel(turn: LiveTurn) = manager.cancel(TAG, turn.activity.hashCode())

    private fun status(update: LiveTurnUpdate): String = context.getString(
        when (update.phase) {
            "thinking" -> R.string.core_push_live_thinking
            "writing" -> R.string.core_push_live_writing
            "approval" -> R.string.core_push_live_approval
            "input" -> R.string.core_push_live_input
            "done" -> R.string.core_push_live_done
            "failed" -> R.string.core_push_live_failed
            "stopped" -> R.string.core_push_live_stopped
            else -> toolStatus(update.tool)
        },
    )

    private fun toolStatus(category: String?): Int = when (category) {
        "terminal" -> R.string.core_push_live_tool_terminal
        "code" -> R.string.core_push_live_tool_code
        "files" -> R.string.core_push_live_tool_files
        "web" -> R.string.core_push_live_tool_web
        "browser" -> R.string.core_push_live_tool_browser
        "agents" -> R.string.core_push_live_tool_agents
        "memory" -> R.string.core_push_live_tool_memory
        "images" -> R.string.core_push_live_tool_images
        "audio" -> R.string.core_push_live_tool_audio
        "planning" -> R.string.core_push_live_tool_planning
        "schedule" -> R.string.core_push_live_tool_schedule
        "messages" -> R.string.core_push_live_tool_messages
        "skills" -> R.string.core_push_live_tool_skills
        "connector" -> R.string.core_push_live_tool_connector
        "phone" -> R.string.core_push_live_tool_phone
        "home" -> R.string.core_push_live_tool_home
        else -> R.string.core_push_live_tool
    }

    private fun openIntent(turn: LiveTurn): PendingIntent? {
        val activity = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component ?: return null
        val open = Intent(PushLink.ACTION_OPEN)
            .setComponent(activity)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        PushLink.put(open, HermesNotification.Target(DashboardId(turn.dashboard), turn.profile, turn.sessionId))
        return PendingIntent.getActivity(
            context,
            turn.activity.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.core_push_live_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
                .apply {
                    description = context.getString(R.string.core_push_live_channel_description)
                    setSound(null, null)
                },
        )
    }

    private companion object {
        const val CHANNEL = "hermes.live-turns"
        const val TAG = "live-turn"
        const val MILLIS = 1000L
        const val DISMISS_AFTER_END_MS = 5 * 60_000L
    }
}
