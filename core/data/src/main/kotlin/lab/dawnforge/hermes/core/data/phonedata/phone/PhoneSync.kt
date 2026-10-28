package lab.dawnforge.hermes.core.data.phonedata.phone

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * When the calendar and phone go to Hermes, as iOS times it: coming back to the app (at least 30
 * seconds apart), a calendar change (after 5 quiet seconds), the user asking, and every hour in
 * the background while it's shared.
 */
class PhoneSync(
    private val context: Context,
    private val engine: PhoneSyncEngine,
    private val foreground: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastPass = 0L
    private var calendarWait: Job? = null
    private var observer: ContentObserver? = null

    fun start() {
        scope.launch {
            foreground.filter { it }.collect { if (clock() - lastPass >= FOREGROUND_SPACING_MS) now() }
        }
        scope.launch {
            engine.sharing.map { it.isOn to (it.calendar == CalendarAccess.Full) }.distinctUntilChanged()
                .collectLatest { (on, readsCalendar) ->
                    if (on) schedule() else WorkManager.getInstance(context).cancelUniqueWork(WORK)
                    watchCalendar(on && readsCalendar)
                }
        }
    }

    /** A pass now; one already running is waited for. */
    fun now() {
        scope.launch {
            lastPass = clock()
            engine.sync()
        }
    }

    private fun watchCalendar(on: Boolean) {
        observer?.let(context.contentResolver::unregisterContentObserver)
        observer = null
        if (!on) return
        val watching = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                calendarWait?.cancel()
                calendarWait = scope.launch {
                    delay(CALENDAR_DEBOUNCE_MS)
                    now()
                }
            }
        }
        // Without calendar access there's nothing to watch; sharing turned on again asks for it.
        runCatching {
            context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, watching)
            observer = watching
        }
    }

    private fun schedule() {
        val request = PeriodicWorkRequestBuilder<PhoneSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val WORK = "phone-sync"
        const val PERIOD_HOURS = 1L
        const val FOREGROUND_SPACING_MS = 30_000L
        const val CALENDAR_DEBOUNCE_MS = 5_000L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PhoneSyncEntryPoint {
    fun engine(): PhoneSyncEngine
}

/** The hourly pass in the background, so Hermes' view of the phone stays fresh with the app closed. */
class PhoneSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val engine = EntryPointAccessors.fromApplication(applicationContext, PhoneSyncEntryPoint::class.java).engine()
        return when (engine.sync()) {
            is PhoneSyncOutcome.Failed -> Result.retry()
            else -> Result.success()
        }
    }
}
