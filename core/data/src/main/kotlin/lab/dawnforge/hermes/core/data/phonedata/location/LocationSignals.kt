package lab.dawnforge.hermes.core.data.phonedata.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The low-power signals behind "in the background", as iOS's `LocationBackgroundMonitor`: saved
 * places as geofences (arrive and leave), and longer moves (only above Places precision). Both wake
 * [LocationReceiver], even with the app closed, and are set up again at launch and after a restart.
 * Android has no visits like iOS's: the places and the moves cover them. Also the moves while a
 * voice conversation runs.
 */
class LocationSignals(private val context: Context, private val fixes: LocationFixes) {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val geofencing by lazy { LocationServices.getGeofencingClient(context) }
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private var callUpdates: LocationCallback? = null

    /** Starts or stops each signal for what the user chose. */
    @SuppressLint("MissingPermission")
    suspend fun configure(settings: LocationSettings, places: List<SavedPlace>, bound: Boolean) {
        val access = fixes.access
        val on = bound && settings.background && access.always
        val moves = on && settings.backgroundPrecision != LocationPrecision.Places
        runCatching {
            if (moves) {
                client.requestLocationUpdates(movesRequest(), intent(ACTION_MOVED)).await()
            } else {
                client.removeLocationUpdates(intent(ACTION_MOVED)).await()
            }
        }
        // Geofences need precise location.
        syncPlaces(if (on && !access.approximateOnly) places.take(SavedPlace.MAX_COUNT) else emptyList())
    }

    @SuppressLint("MissingPermission")
    private suspend fun syncPlaces(places: List<SavedPlace>) {
        // Only a hash is kept here: the places' coordinates stay with the rest of the location choices.
        val digest = digest(places)
        if (prefs.getString(DIGEST, null) == digest) return
        runCatching {
            geofencing.removeGeofences(intent(ACTION_PLACE)).await()
            if (places.isNotEmpty()) {
                val request = GeofencingRequest.Builder()
                    // The first answer for each place is where the phone is now, not a move.
                    .setInitialTrigger(
                        GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT,
                    )
                    .addGeofences(
                        places.map { place ->
                            Geofence.Builder()
                                .setRequestId(place.id)
                                .setCircularRegion(place.latitude, place.longitude, place.radius.toFloat())
                                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                                .setTransitionTypes(
                                    Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT,
                                )
                                .build()
                        },
                    )
                    .build()
                geofencing.addGeofences(request, intent(ACTION_PLACE)).await()
            }
            prefs.edit().apply { if (places.isEmpty()) remove(DIGEST) else putString(DIGEST, digest) }.apply()
        }
    }

    /** Geofences and updates are dropped by a restart or Play services: set up again from scratch. */
    fun forgetRegistered() {
        prefs.edit().remove(DIGEST).apply()
    }

    /** Watches for moves of more than 200 m while a voice conversation runs. */
    @SuppressLint("MissingPermission")
    fun startCallUpdates(moved: (RawFix) -> Unit) {
        if (callUpdates != null || !fixes.access.whileInUse) return
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { moved(it.toRaw()) }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CALL_INTERVAL_MS)
            .setMinUpdateDistanceMeters(CALL_MOVE_M.toFloat())
            .build()
        runCatching { client.requestLocationUpdates(request, callback, Looper.getMainLooper()) }
            .onSuccess { callUpdates = callback }
    }

    fun stopCallUpdates() {
        callUpdates?.let { client.removeLocationUpdates(it) }
        callUpdates = null
    }

    private fun movesRequest() = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, MOVES_INTERVAL_MS)
        .setMinUpdateDistanceMeters(MOVE_M.toFloat())
        .setMinUpdateIntervalMillis(MOVES_FASTEST_MS)
        .build()

    private fun intent(action: String): PendingIntent {
        // Mutable: Play services adds the event or the location to it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, LocationReceiver::class.java).setAction(action),
            flags,
        )
    }

    companion object {
        const val ACTION_PLACE = "lab.dawnforge.hermes.location.PLACE"
        const val ACTION_MOVED = "lab.dawnforge.hermes.location.MOVED"
        const val CALL_MOVE_M = 200.0
        private const val MOVE_M = 500.0
        private const val MOVES_INTERVAL_MS = 15 * 60_000L
        private const val MOVES_FASTEST_MS = 5 * 60_000L
        private const val CALL_INTERVAL_MS = 30_000L
        private const val PREFS = "location_signals"
        private const val DIGEST = "places.digest"

        fun digest(places: List<SavedPlace>): String = MessageDigest.getInstance("SHA-256")
            .digest(places.joinToString(";") { "${it.id}@${it.latitude},${it.longitude},${it.radius}" }.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocationEntryPoint {
    fun locationSharing(): LocationSharing
}

/** A saved place's arrive or leave, a longer move, or the phone restarting (signals set up again). */
class LocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sharing = EntryPointAccessors.fromApplication(context.applicationContext, LocationEntryPoint::class.java)
            .locationSharing()
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    LocationSignals.ACTION_PLACE -> GeofencingEvent.fromIntent(intent)?.takeUnless {
                        it.hasError()
                    }?.let { event ->
                        val inside = when (event.geofenceTransition) {
                            Geofence.GEOFENCE_TRANSITION_ENTER -> true
                            Geofence.GEOFENCE_TRANSITION_EXIT -> false
                            else -> null
                        } ?: return@let
                        val at = event.triggeringLocation?.time ?: System.currentTimeMillis()
                        event.triggeringGeofences.orEmpty().forEach {
                            sharing.placeStateChanged(it.requestId, inside, at)
                        }
                    }

                    LocationSignals.ACTION_MOVED -> LocationResult.extractResult(intent)?.lastLocation?.let {
                        sharing.movedSignificantly(it.toRaw())
                    }

                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> sharing.signalsLost()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
