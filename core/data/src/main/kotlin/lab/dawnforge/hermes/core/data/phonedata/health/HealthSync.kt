package lab.dawnforge.hermes.core.data.phonedata.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.phonedata.location.DeviceConditions

/** Whether Health Connect is on the phone, as the Health page shows it. */
enum class HealthAvailability { Available, NeedsUpdate, Unavailable }

/** What the app may read from Health Connect, and what it would ask for. */
class HealthAccess(private val context: Context) {
    val availability: HealthAvailability
        get() = when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.NeedsUpdate
            else -> HealthAvailability.Unavailable
        }

    private val client get() = HealthConnectClient.getOrCreate(context)

    suspend fun granted(): Set<String> = if (availability == HealthAvailability.Available) {
        runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
    } else {
        emptySet()
    }

    private fun feature(feature: Int): Boolean = availability == HealthAvailability.Available &&
        client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    /** Reading while the app is in the background is its own permission, where Health Connect has it. */
    val backgroundAvailable: Boolean get() = feature(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)

    /** Reading more than 30 days before the first grant is its own permission, where Health Connect has it. */
    val historyAvailable: Boolean get() = feature(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)

    /** What sharing [preferences] asks Health Connect for. */
    fun permissionsFor(preferences: HealthPreferences): Set<String> = buildSet {
        addAll(HealthConnectSource.readPermissions(preferences.categories))
        if (backgroundAvailable) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        if (preferences.history != HealthHistory.Days30 &&
            historyAvailable
        ) {
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        }
    }

    /** Whether a pass may run with the app in the background. */
    suspend fun readsInBackground(): Boolean =
        !backgroundAvailable || HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted()
}

/**
 * When health data goes to Hermes, as iOS times it: coming back to the app (3 minutes apart,
 * longer with the savers on), every hour in the background (Health Connect has no wake-ups like
 * HealthKit's), when the user asks, and when sharing is turned on or its choices change.
 */
class HealthSync(
    private val context: Context,
    private val engine: HealthSyncEngine,
    private val access: HealthAccess,
    private val foreground: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val conditions: () -> DeviceConditions = { DeviceConditions() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var lastForegroundPass = 0L

    fun start() {
        scope.launch {
            foreground.filter { it }.collect {
                val spacing = (FOREGROUND_SPACING_MS * conditions().stretch).toLong()
                if (clock() - lastForegroundPass >= spacing) {
                    lastForegroundPass = clock()
                    pass(FOREGROUND_DEADLINE_MS)
                }
            }
        }
        scope.launch {
            engine.preferences.map { it.isOn }.distinctUntilChanged().collect { on ->
                val work = WorkManager.getInstance(context)
                if (on) {
                    val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(1, TimeUnit.HOURS)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build()
                    work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
                } else {
                    work.cancelUniqueWork(WORK)
                }
            }
        }
    }

    /** The user asked (Sync now, sharing turned on or changed): every recent window goes again. */
    fun now() {
        scope.launch { pass(FOREGROUND_DEADLINE_MS, resendRecent = true) }
    }

    /** A pass in the background, from the hourly work. */
    suspend fun background() {
        if (!engine.preferences.first().isOn || !access.readsInBackground()) return
        pass(BACKGROUND_DEADLINE_MS)
    }

    private suspend fun pass(deadlineMs: Long, resendRecent: Boolean = false): HealthOutcome {
        val lowData = conditions().dataSaver && !resendRecent
        return engine.sync(clock() + deadlineMs, HealthPassOptions(resendRecent = resendRecent, lowData = lowData))
    }

    companion object {
        private const val WORK = "health-sync"
        private const val FOREGROUND_SPACING_MS = 180_000L
        private const val FOREGROUND_DEADLINE_MS = 120_000L
        private const val BACKGROUND_DEADLINE_MS = 60_000L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface HealthEntryPoint {
    fun healthSync(): HealthSync
}

/** The hourly background pass. */
class HealthSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, HealthEntryPoint::class.java).healthSync().background()
        return Result.success()
    }
}
