package lab.dawnforge.hermes.core.data.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.R

/**
 * Finishes what was on its way when the app left the screen ([WorkOnItsWay]): Android cuts a
 * backgrounded app's network within seconds, which would leave a message unsent or a reply unread
 * until the app is opened again. A short foreground service (Android's `shortService`, at most
 * about three minutes) keeps the network and the live connection for that, with a quiet
 * notification saying so. It stops when the work is done, when the app is back in front, or when
 * Android's limit comes; a reply still running then arrives as a push notification.
 */
class FinishWorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var release: (() -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), foregroundType())
        if (release == null) {
            val parts = EntryPointAccessors.fromApplication(applicationContext, FinishWorkEntryPoint::class.java)
            release = parts.connectionHolds().hold()
            scope.launch {
                // Done, or the app is in front again (where the connection is kept anyway).
                combine(parts.workOnItsWay().held, parts.appForeground().isForeground) { work, inFront ->
                    work && !inFront
                }.first { needed -> !needed }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Android's limit for a short service: what is left arrives as a push notification. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    /** The same on Android 14, which calls this form. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        release?.invoke()
        release = null
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.core_data_finish_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.core_data_finish_notification))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun foregroundType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
    } else {
        0
    }

    companion object {
        private const val TAG = "HermesFinishWork"
        private const val CHANNEL = "finishing"
        private const val NOTIFICATION_ID = 0x5161

        /**
         * Starts it, as the app leaves the screen. Android refuses a start it doesn't allow (from
         * the background, after that moment): the work then goes on when the app is opened again.
         */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, FinishWorkService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (Android 12+) is one of these.
                Log.i(TAG, "finishing in the background was not allowed: ${e.javaClass.simpleName}")
            }
        }
    }
}

/** What [FinishWorkService] needs from the app's graph. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FinishWorkEntryPoint {
    fun connectionHolds(): ConnectionHolds

    fun workOnItsWay(): WorkOnItsWay

    fun appForeground(): AppForeground
}
