package lab.dawnforge.hermes.core.data.phonedata.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the app may do with location, from Android's permissions. */
data class LocationAccess(
    /** Precise or approximate, while the app is in use. */
    val whileInUse: Boolean,
    /** Only approximate location was allowed. */
    val approximateOnly: Boolean,
    /** "Allow all the time": the background layer's signals need it. */
    val always: Boolean,
)

/**
 * One position when sharing needs one, as iOS's `LocationFixProvider`: a recent enough fix the
 * phone already has, else a single current-location request, never continuous updates. Also turns a
 * position into an area name with Android's own geocoder, on the phone, so the host never calls one.
 */
class LocationFixes(private val context: Context) {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    @Volatile
    private var cachedArea: Pair<RawFix, LocationArea>? = null

    val access: LocationAccess
        get() {
            val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
            val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return LocationAccess(
                whileInUse = fine || coarse,
                approximateOnly = coarse && !fine,
                always =
                    (fine || coarse) && background,
            )
        }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** The last position the phone has, however old: nothing is asked of it. */
    @SuppressLint("MissingPermission")
    suspend fun lastKnown(): RawFix? {
        if (!access.whileInUse) return null
        return runCatching { client.lastLocation.await() }.getOrNull()?.toRaw()
    }

    /** A position at about [precision], no older than [maxAgeMs], or null when none comes within [timeoutMs]. */
    @SuppressLint("MissingPermission")
    suspend fun current(precision: LocationPrecision, maxAgeMs: Long, timeoutMs: Long): RawFix? {
        if (!access.whileInUse) return null
        val request = CurrentLocationRequest.Builder()
            .setPriority(
                if (precision ==
                    LocationPrecision.Precise
                ) {
                    Priority.PRIORITY_HIGH_ACCURACY
                } else {
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY
                },
            )
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            .setMaxUpdateAgeMillis(maxAgeMs)
            .setDurationMillis(timeoutMs)
            .build()
        val cancel = CancellationTokenSource()
        return try {
            withTimeoutOrNull(timeoutMs + GRACE_MS) {
                client.getCurrentLocation(request, cancel.token).await()
            }?.toRaw()
        } catch (_: Exception) {
            null
        } finally {
            cancel.cancel()
        }
    }

    /**
     * Neighbourhood, city, region and country from the phone's geocoder; null when it doesn't answer
     * in time. Reused while the phone stays close.
     */
    suspend fun area(fix: RawFix, timeoutMs: Long = AREA_TIMEOUT_MS): LocationArea? {
        cachedArea?.let { (at, area) ->
            val near = LocationGeo.distance(at.latitude, at.longitude, fix.latitude, fix.longitude) < AREA_REUSE_M
            if (near && abs(at.timeMs - fix.timeMs) < AREA_REUSE_MS) return area
        }
        if (!Geocoder.isPresent()) return null
        val found = withTimeoutOrNull(timeoutMs) { address(fix) }?.let(::area)
        if (found != null && !found.isEmpty) cachedArea = fix to found
        return found
    }

    /** Places matching [query], from the phone's geocoder: the place editor's search. */
    suspend fun search(query: String, limit: Int = SEARCH_LIMIT): List<FoundPlace> {
        if (!Geocoder.isPresent() || query.isBlank()) return emptyList()
        val geocoder = Geocoder(context, Locale.getDefault())
        val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocationName(
                        query,
                        limit,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (continuation.isActive) continuation.resume(addresses.toList())
                            }

                            override fun onError(errorMessage: String?) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        },
                    )
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocationName(query, limit) }.getOrNull()
            }
        } ?: throw LocationSearchFailed()
        return found.filter { it.hasLatitude() && it.hasLongitude() }.map { address ->
            val lines = (0..address.maxAddressLineIndex).mapNotNull(address::getAddressLine)
            FoundPlace(
                name =
                    address.featureName?.takeUnless { it == address.subThoroughfare } ?: lines.firstOrNull().orEmpty(),
                detail = lines.joinToString(", "),
                latitude = address.latitude,
                longitude = address.longitude,
            )
        }
    }

    private suspend fun address(fix: RawFix): Address? {
        val geocoder = Geocoder(context, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(
                    fix.latitude,
                    fix.longitude,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(fix.latitude, fix.longitude, 1)?.firstOrNull() }.getOrNull()
            }
        }
    }

    companion object {
        private const val GRACE_MS = 1_000L
        private const val AREA_TIMEOUT_MS = 1_500L
        private const val AREA_REUSE_M = 300.0
        private const val AREA_REUSE_MS = 30 * 60_000L
        private const val SEARCH_LIMIT = 8
        private const val SEARCH_TIMEOUT_MS = 10_000L

        fun area(address: Address) = LocationArea(
            name = address.subLocality ?: address.locality,
            locality = address.locality,
            region = address.adminArea,
            country = address.countryName,
            countryCode = address.countryCode?.uppercase(Locale.ROOT),
        )

        /** How rough a fix the phone already has may be for [precision]: twice the ask, and at least 65 m. */
        fun acceptableAccuracy(precision: LocationPrecision): Double {
            val wanted = if (precision == LocationPrecision.Precise) PRECISE_WANTED else COARSE_WANTED
            return min(max(wanted, WIFI_ACCURACY), 2 * wanted)
        }

        private const val PRECISE_WANTED = 10.0
        private const val COARSE_WANTED = 100.0
        private const val WIFI_ACCURACY = 65.0
    }
}

/** A place the geocoder found for a search. */
data class FoundPlace(val name: String, val detail: String, val latitude: Double, val longitude: Double)

/** The geocoder couldn't be reached (no network, or none on the phone). */
class LocationSearchFailed : Exception()

/** A measurement as the rules take it. */
fun Location.toRaw() = RawFix(
    latitude = latitude,
    longitude = longitude,
    // A fix with no accuracy can't claim to be sharp: it counts as rough.
    accuracy = if (hasAccuracy()) accuracy.toDouble().coerceAtLeast(0.0) else LocationRules.AREA_MIN_ACCURACY,
    timeMs = time,
    speed = if (hasSpeed()) speed.toDouble() else null,
    course = if (hasBearing()) bearing.toDouble() else null,
)
