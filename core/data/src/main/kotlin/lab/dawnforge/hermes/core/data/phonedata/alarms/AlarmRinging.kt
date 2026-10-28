package lab.dawnforge.hermes.core.data.phonedata.alarms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.R
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneSync

/**
 * How an alarm rings: the alarm sound, over and over, through Do Not Disturb (which lets alarms
 * through), with the alarm's screen over the lock screen and Stop, as iOS's AlarmKit alert.
 */
object AlarmRinging {
    private const val CHANNEL = "alarms"

    /** The screen an alarm shows while it rings (the app's alarm activity). */
    fun screen(context: Context, id: String): Intent = Intent()
        .setClassName(context.packageName, "lab.dawnforge.hermes.alarm.AlarmActivity")
        .putExtra(AlarmReceiver.EXTRA_ID, id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

    fun show(context: Context, alarm: PhoneAlarm) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        channel(context, manager)
        val title = alarm.label ?: context.getString(
            if (alarm.kind == "timer") R.string.core_data_alarm_timer else R.string.core_data_alarm_alarm,
        )
        val screen = PendingIntent.getActivity(
            context,
            alarm.id.hashCode(),
            screen(context, alarm.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode(),
            Intent(
                context,
                AlarmReceiver::class.java,
            ).setAction(AlarmReceiver.ACTION_STOP).putExtra(AlarmReceiver.EXTRA_ID, alarm.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.core_data_alarm_from_hermes))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .addAction(0, context.getString(R.string.core_data_alarm_stop), stop)
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }
        manager.notify(notificationId(alarm.id), notification)
    }

    fun stop(context: Context, id: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId(id))
    }

    private fun channel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL) != null) return
        val sound = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.core_data_alarm_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.core_data_alarm_channel_detail)
                setSound(
                    sound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun notificationId(id: String) = NOTIFICATION_BASE + (id.hashCode() and 0xFFFF)

    private const val NOTIFICATION_BASE = 8_000_000
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AlarmEntryPoint {
    fun book(): AlarmBook

    fun sync(): PhoneSync
}

/**
 * An alarm ringing or stopped, and the times Android forgets alarms (a restart, a new app version,
 * a clock or time-zone change, exact alarms allowed again): they're all set again.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, AlarmEntryPoint::class.java)
        val pending = goAsync()
        scope.launch {
            try {
                val book = entry.book()
                val id = intent.getStringExtra(EXTRA_ID)
                when (intent.action) {
                    ACTION_RING -> id?.let { book.ringing(it) }?.let { AlarmRinging.show(context, it) }
                    ACTION_STOP -> id?.let { book.stop(it) }
                    else -> book.rescheduleAll()
                }
                // Hermes sees the alarm ringing, or gone.
                if (intent.action == ACTION_RING || intent.action == ACTION_STOP) entry.sync().now()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RING = "lab.dawnforge.hermes.alarm.RING"
        const val ACTION_STOP = "lab.dawnforge.hermes.alarm.STOP"
        const val EXTRA_ID = "alarm_id"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
