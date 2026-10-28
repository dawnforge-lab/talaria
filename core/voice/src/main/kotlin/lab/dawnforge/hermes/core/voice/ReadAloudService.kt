package lab.dawnforge.hermes.core.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Read aloud on the lock screen and in the notification shade, as iOS's now playing: the reply's
 * first line, and play, pause and stop only. It keeps the reading going while the app is out of
 * sight, and stops itself when the reading ends.
 */
class ReadAloudService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val reader = ReadAloud.active
        if (reader == null) {
            finish()
            return
        }
        val session = MediaSession(this, "read-aloud").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = reader.resume()

                    override fun onPause() = reader.pause()

                    override fun onStop() = reader.stop()
                },
            )
            isActive = true
        }
        this.session = session
        scope.launch {
            reader.state.collect { state ->
                if (state == null) {
                    finish()
                    return@collect
                }
                session.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.core_voice_read_aloud))
                        .build(),
                )
                val playing = state.phase != ReadAloudState.Phase.Paused
                session.setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(
                            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP,
                        )
                        .setState(
                            if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                            if (playing) state.speed else 0f,
                        )
                        .build(),
                )
                show(notification(session, state, playing))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> ReadAloud.active?.pause()
            ACTION_PLAY -> ReadAloud.active?.resume()
            ACTION_STOP -> ReadAloud.active?.stop()
        }
        return START_NOT_STICKY
    }

    private fun show(notification: Notification) {
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        }
        if (started.isFailure) finish() else foreground = true
    }

    private fun notification(session: MediaSession, state: ReadAloudState, playing: Boolean): Notification {
        channel()
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val toggle = if (playing) {
            action(android.R.drawable.ic_media_pause, R.string.core_voice_read_aloud_pause, ACTION_PAUSE, REQUEST_PAUSE)
        } else {
            action(android.R.drawable.ic_media_play, R.string.core_voice_read_aloud_play, ACTION_PLAY, REQUEST_PLAY)
        }
        val stop =
            action(
                android.R.drawable.ic_menu_close_clear_cancel,
                R.string.core_voice_read_aloud_stop,
                ACTION_STOP,
                REQUEST_STOP,
            )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.core_voice_ic_notification)
            .setContentTitle(state.title)
            .setContentText(getString(R.string.core_voice_read_aloud))
            .setContentIntent(open)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(toggle)
            .addAction(stop)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
    }

    private fun action(icon: Int, label: Int, action: String, request: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, icon),
            getString(label),
            PendingIntent.getService(
                this,
                request,
                Intent(this, ReadAloudService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()

    /** Stops; a service started in the foreground's name must show itself first, however briefly. */
    private fun finish() {
        if (!foreground) {
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    placeholder(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun placeholder(): Notification {
        channel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setSmallIcon(R.drawable.core_voice_ic_notification)
            .setContentTitle(getString(R.string.core_voice_read_aloud))
            .build()
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.core_voice_read_aloud), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.core_voice_read_aloud_channel_description) },
        )
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "hermes.read-aloud"
        private const val NOTIFICATION_ID = 7302
        private const val ACTION_PAUSE = "lab.dawnforge.hermes.readaloud.PAUSE"
        private const val ACTION_PLAY = "lab.dawnforge.hermes.readaloud.PLAY"
        private const val ACTION_STOP = "lab.dawnforge.hermes.readaloud.STOP"
        private const val REQUEST_PAUSE = 1
        private const val REQUEST_PLAY = 2
        private const val REQUEST_STOP = 3

        /** Starts while the app is on screen, as reading aloud does. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java)) }
        }
    }
}
