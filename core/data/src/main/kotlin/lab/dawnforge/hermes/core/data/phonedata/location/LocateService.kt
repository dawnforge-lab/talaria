package lab.dawnforge.hermes.core.data.phonedata.location

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
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.R
import lab.dawnforge.hermes.core.model.DashboardId

/**
 * Answers Hermes asking where the phone is now. Android doesn't give an app in the background a
 * fresh position, so the answer runs as a location foreground service for its few seconds, started
 * from the urgent push that asked (which Android allows), with a notification saying so: Android's
 * counterpart of the location arrow iOS shows.
 */
class LocateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), foregroundType())
        val dashboard = intent?.getStringExtra(EXTRA_DASHBOARD)
        val profile = intent?.getStringExtra(EXTRA_PROFILE)
        val locateId = intent?.getStringExtra(EXTRA_LOCATE)
        if (dashboard == null || profile == null || locateId == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val sharing = EntryPointAccessors.fromApplication(
            applicationContext,
            LocationEntryPoint::class.java,
        ).locationSharing()
        scope.launch {
            try {
                sharing.answerLocate(DashboardId(dashboard), profile, locateId)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.core_data_locate_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.core_data_locate_notification))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0

    companion object {
        private const val CHANNEL = "locate"
        private const val NOTIFICATION_ID = 0x10CA
        private const val EXTRA_DASHBOARD = "dashboard"
        private const val EXTRA_PROFILE = "profile"
        private const val EXTRA_LOCATE = "locate"

        /** Starts answering; false when Android won't start the service (the caller answers as it can). */
        fun start(context: Context, dashboard: DashboardId, profile: String, locateId: String): Boolean = runCatching {
            context.startForegroundService(
                Intent(context, LocateService::class.java)
                    .putExtra(EXTRA_DASHBOARD, dashboard.value)
                    .putExtra(EXTRA_PROFILE, profile)
                    .putExtra(EXTRA_LOCATE, locateId),
            )
        }.isSuccess
    }
}
