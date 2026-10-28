package lab.dawnforge.hermes.core.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a voice conversation going while the app is out of sight: the microphone and the reply's
 * speech run in a foreground service, with a notification that opens the app or ends it.
 */
class VoiceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            VoiceSessions.current?.end()
            stopSelf()
            return START_NOT_STICKY
        }
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), types)
        }
        // Android refuses a microphone service started from the background: there's no conversation then.
        if (started.isFailure) {
            VoiceSessions.current?.end()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.core_voice_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = getString(R.string.core_voice_channel_description) },
            )
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val end = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.core_voice_ic_notification)
            .setContentTitle(getString(R.string.core_voice_notification_title))
            .setContentText(getString(R.string.core_voice_notification_text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .addAction(0, getString(R.string.core_voice_end), end)
            .build()
    }

    companion object {
        private const val CHANNEL = "hermes.voice"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_END = "lab.dawnforge.hermes.voice.END"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, VoiceService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }
}
