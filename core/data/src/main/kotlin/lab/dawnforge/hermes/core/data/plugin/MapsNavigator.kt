package lab.dawnforge.hermes.core.data.plugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import lab.dawnforge.hermes.core.data.R
import lab.dawnforge.hermes.core.data.phonedata.location.SavedPlace

/**
 * Opens Maps with directions for a Go confirmation (`talaria_maps navigate`), from wherever the user
 * is, as iOS's MapsNavigator: on the phone when the app is in front, and otherwise behind a
 * notification the user taps, as Android starts another app only from the foreground. The user's
 * saved places are read here only; nothing goes back to Hermes.
 */
class MapsNavigator(
    private val context: Context,
    private val foreground: StateFlow<Boolean>,
    private val places: suspend () -> List<SavedPlace>,
) {
    sealed interface Outcome {
        data class Opened(val name: String) : Outcome

        /** The app wasn't in front: a notification opens Maps on a tap. */
        data class Notified(val name: String) : Outcome

        data object Failed : Outcome
    }

    suspend fun open(target: MapsTarget): Outcome {
        val destination = MapsDirections.destination(target.to, places())
        val link = destination?.let { MapsDirections.link(it, target.mode) }
        val name = destination?.let(MapsDirections::label)
        if (link == null || name == null) return failed()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return withContext(Dispatchers.Main) {
            if (foreground.value) {
                try {
                    context.startActivity(intent)
                    Outcome.Opened(name)
                } catch (_: ActivityNotFoundException) {
                    failed()
                }
            } else if (notify(name, intent)) {
                Outcome.Notified(name)
            } else {
                Outcome.Failed
            }
        }
    }

    /** Says so when the user can see it. */
    private suspend fun failed(): Outcome = withContext(Dispatchers.Main) {
        if (foreground.value) Toast.makeText(context, R.string.core_data_maps_failed, Toast.LENGTH_LONG).show()
        Outcome.Failed
    }

    /** Whether the notification was posted (notifications may be off). */
    private fun notify(name: String, intent: Intent): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.core_data_maps_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val tap = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(context.getString(R.string.core_data_maps_notification_title, name))
            .setContentText(context.getString(R.string.core_data_maps_notification_body))
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        return try {
            // One at a time: a newer destination replaces the last.
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private companion object {
        const val CHANNEL = "maps_directions"
        const val NOTIFICATION_ID = 0x3A95
    }
}
