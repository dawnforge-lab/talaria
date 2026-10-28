package lab.dawnforge.hermes.core.data.phonedata.health

import java.time.Instant
import java.time.ZoneId

/** A stream of events with its own place in Health Connect's changes. */
sealed interface HealthStream {
    val key: String

    data object Workouts : HealthStream {
        override val key = "workouts"
    }

    data object Sleep : HealthStream {
        override val key = "sleep"
    }

    data class Samples(val metric: HealthMetric) : HealthStream {
        override val key = "samples.${metric.wire}"
    }
}

/**
 * One page of a stream's events: what was added or changed, what was deleted, where the stream is
 * now, and whether more waits. Sleep stages and a heart rate series' readings have no ids of their
 * own (`<record>:<index>`), so a page also says how many events each such record it touched has now
 * (null: the record was deleted); the engine takes back what a record no longer has.
 */
data class HealthChanges(
    val added: List<HealthEvent>,
    val deleted: List<String>,
    val position: String?,
    val more: Boolean,
    val series: Map<String, Int?> = emptyMap(),
)

/** What the phone's health data can't be read for now: no access, or Health Connect isn't there. */
class HealthUnavailable(message: String) : Exception(message)

/** What the sync engine reads from the phone's health data. Tests use a fake. */
interface HealthSource {
    /** Before a pass reads: what the user allows now. */
    suspend fun prepare() {}

    /** Hourly or daily buckets for [metric] in [from, to), by local time in [zone]. */
    suspend fun buckets(
        metric: HealthMetric,
        grain: HealthGrain,
        from: Instant,
        to: Instant,
        zone: ZoneId,
    ): List<HealthBucket>

    suspend fun sleepSegments(from: Instant, to: Instant): List<HealthSleepSegment>

    /**
     * New, changed and deleted events in [stream] after [position] (opaque, kept by the engine),
     * none earlier than [since]. A null position reads from [since].
     */
    suspend fun changes(stream: HealthStream, since: Instant, position: String?, limit: Int): HealthChanges
}
