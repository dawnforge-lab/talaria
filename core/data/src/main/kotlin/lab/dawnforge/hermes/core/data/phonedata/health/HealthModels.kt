package lab.dawnforge.hermes.core.data.phonedata.health

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneDataDestination

/**
 * A group of metrics the user shares or not, as one switch, as iOS's `HealthCategory`. What
 * leaves the phone by default is what Health Connect itself computes (hourly and daily totals, the
 * phone and a watch merged by the user's app priority), a summary per night of sleep, its stage
 * segments and workouts; raw readings only where the user turns on detailed readings.
 */
@Serializable
enum class HealthCategory(val wire: String) {
    @SerialName("activity")
    Activity("activity"),

    @SerialName("heart")
    Heart("heart"),

    @SerialName("sleep")
    Sleep("sleep"),

    @SerialName("workouts")
    Workouts("workouts"),

    @SerialName("body")
    Body("body"),

    @SerialName("vitals")
    Vitals("vitals"),

    @SerialName("fitness")
    Fitness("fitness"),
    ;

    /** Raw readings exist to share; sleep and workouts always go as their own events. */
    val hasDetailedReadings: Boolean get() = this != Sleep && this != Workouts

    val metrics: List<HealthMetric> get() = HealthMetric.entries.filter { it.category == this }
}

/**
 * A metric sent as hourly and daily buckets, with the host's name and unit (the plugin's
 * `health_store.py` METRICS, as iOS sends them). Only metrics Health Connect measures the same way
 * as Apple Health: it has no stand time, walking heart rate average, six-minute walk or exercise
 * minutes, and its heart rate variability (RMSSD) and skin temperature (a change from a baseline)
 * aren't Apple's, so none of those go (decided 2026-10-24).
 */
enum class HealthMetric(val wire: String, val unit: String, val category: HealthCategory, val hourly: Boolean = false) {
    Steps("steps", "count", HealthCategory.Activity, hourly = true),
    DistanceWalkingRunning("distance_walking_running", "m", HealthCategory.Activity, hourly = true),
    DistanceCycling("distance_cycling", "m", HealthCategory.Activity, hourly = true),
    ActiveEnergy("active_energy", "kcal", HealthCategory.Activity, hourly = true),
    FlightsClimbed("flights_climbed", "count", HealthCategory.Activity, hourly = true),
    HeartRate("heart_rate", "count/min", HealthCategory.Heart, hourly = true),
    RestingHeartRate("resting_heart_rate", "count/min", HealthCategory.Heart),
    OxygenSaturation("oxygen_saturation", "%", HealthCategory.Vitals),
    RespiratoryRate("respiratory_rate", "count/min", HealthCategory.Vitals),
    BloodPressureSystolic("blood_pressure_systolic", "mmHg", HealthCategory.Vitals),
    BloodPressureDiastolic("blood_pressure_diastolic", "mmHg", HealthCategory.Vitals),
    BodyMass("body_mass", "kg", HealthCategory.Body),
    BodyFatPercentage("body_fat_percentage", "%", HealthCategory.Body),
    Height("height", "m", HealthCategory.Body),
    Vo2Max("vo2_max", "mL/(kg*min)", HealthCategory.Fitness),
    ;

    /** Activity is summed; the rest is min, average and max. */
    val sums: Boolean get() = category == HealthCategory.Activity
}

/** How far back the first sync reaches: 30 days by default, as on iOS. */
@Serializable
enum class HealthHistory(val days: Int?) {
    @SerialName("days30")
    Days30(30),

    @SerialName("days90")
    Days90(90),

    @SerialName("year")
    Year(365),

    /** Everything Health Connect lets the app read. */
    @SerialName("all")
    All(null),
}

object HealthLimits {
    /** Hourly buckets reach back this far at most; older history is daily. */
    const val HOURLY_HISTORY_DAYS = 30

    /** Recent days are recomputed on every pass: a watch often syncs late. */
    const val RECOMPUTED_DAYS = 3

    /** Items per sealed batch: well under the host's 6,000. */
    const val MAX_ITEMS_PER_BATCH = 2_000

    /** Days of history per backfill step. */
    const val BACKFILL_CHUNK_DAYS = 30

    /** Records per page when reading events. */
    const val EVENTS_PER_PAGE = 1_000

    /** "All history" as far as Health Connect keeps anything. */
    const val ALL_HISTORY_DAYS = 10 * 365
}

/** The user's choices (no health data in them). */
@Serializable
data class HealthPreferences(
    val destination: PhoneDataDestination? = null,
    val categories: Set<HealthCategory> = emptySet(),
    val detailed: Set<HealthCategory> = emptySet(),
    val history: HealthHistory = HealthHistory.Days30,
) {
    val isOn: Boolean get() = destination != null && categories.isNotEmpty()

    companion object {
        /** What the switches start with, as on iOS. */
        val DEFAULT_CATEGORIES =
            setOf(HealthCategory.Activity, HealthCategory.Heart, HealthCategory.Sleep, HealthCategory.Workouts)
    }
}

/** Why sharing is paused, as the Health page says it. */
@Serializable
enum class HealthPause {
    SignInRequired,
    PluginMissing,
    Refused,

    /** Health Connect access was taken away; sharing waits for it. */
    NoAccess,
}

/**
 * What a pass remembers between runs, as iOS's `HealthSyncState`: no health values, only where
 * each stream is up to, the history cursor and what the host last took.
 */
@Serializable
data class HealthSyncState(
    /** Where this state belongs; another destination starts over. */
    val destination: PhoneDataDestination? = null,
    /** How far back sharing reaches (epoch ms of its first day), fixed per choice. */
    val historyStart: Long? = null,
    val history: HealthHistory? = null,
    /** The oldest day already sent (epoch ms); history before it is still to go. */
    val backfillCursor: Long? = null,
    /** The categories the history walk covers. */
    val backfilledCategories: Set<HealthCategory>? = null,
    /** Health Connect's change tokens, per stream (HealthStream.key). */
    val tokens: Map<String, String> = emptyMap(),
    /** How many events each sleep session or heart rate series sent, so a changed or deleted one can take them back. */
    val seriesCounts: Map<String, Int> = emptyMap(),
    /** Each recent window the host last took, by metric, grain and range, as a hash of what it carried. */
    val recentWindows: Map<String, String>? = null,
    val recentWholeAt: Long? = null,
    /** When the host last took an upload: only an upload moves its `last_seen`. */
    val lastIngest: Long? = null,
    val lastSync: Long? = null,
    val lastAttempt: Long? = null,
    val pause: HealthPause? = null,
    val refusal: String? = null,
    val lastError: String? = null,
) {
    /** The host has nothing from this phone (a new key or store): everything goes again. */
    fun restarted(): HealthSyncState = copy(
        historyStart = null,
        history = null,
        backfillCursor = null,
        backfilledCategories = null,
        tokens = emptyMap(),
        seriesCounts = emptyMap(),
        recentWindows = null,
        recentWholeAt = null,
    )
}
