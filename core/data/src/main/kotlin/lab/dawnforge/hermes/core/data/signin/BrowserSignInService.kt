package lab.dawnforge.hermes.core.data.signin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import lab.dawnforge.hermes.core.data.R

/**
 * Keeps the app's process running while a sign-in in the browser comes back to it. With the
 * Custom Tab in front, Android counts the app as cached and freezes it within seconds; a frozen
 * process cannot answer the browser's redirect to the loopback listener, so the sign-in would
 * never arrive. A short foreground service (Android's `shortService`, at most about three minutes)
 * holds it awake for exactly that wait, with a notification saying so.
 */
class BrowserSignInService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.core_data_sign_in_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(getString(R.string.core_data_sign_in_notification))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setSilent(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType())
        return START_NOT_STICKY
    }

    /** Android ends a short service after about three minutes; the sign-in has given up by then. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    /** The same on Android 14, which calls this form. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    private fun foregroundType(): Int = if (Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    ) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
    } else {
        0
    }

    companion object {
        private const val CHANNEL = "sign_in"
        private const val NOTIFICATION_ID = 0x5160

        /** How long the browser may take: within a short service's limit, with room to stop. */
        const val WAIT_MS = 170_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BrowserSignInService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BrowserSignInService::class.java))
        }
    }
}

/**
 * Runs a browser sign-in with the app held awake ([BrowserSignInService]). On JVM tests and where
 * no context is given, [whileWaiting] just runs the block.
 */
class BrowserSignInKeepAlive(private val context: Context?) {
    suspend fun <T> whileWaiting(block: suspend () -> T): T {
        val app = context ?: return block()
        BrowserSignInService.start(app)
        return try {
            block()
        } finally {
            BrowserSignInService.stop(app)
        }
    }
}
