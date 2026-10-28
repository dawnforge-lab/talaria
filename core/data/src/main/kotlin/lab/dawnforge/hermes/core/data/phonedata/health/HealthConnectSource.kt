package lab.dawnforge.hermes.core.data.phonedata.health

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign
import kotlin.reflect.KClass

/** A metric's min, average and max aggregates, and how a value becomes the host's unit. */
private class Stats<T : Any>(
    val min: AggregateMetric<T>,
    val avg: AggregateMetric<T>,
    val max: AggregateMetric<T>,
    val value: (T) -> Double,
)

/** A metric's total aggregate, and how a value becomes the host's unit. */
private class Total<T : Any>(val metric: AggregateMetric<T>, val value: (T) -> Double)

/**
 * Reads the phone's health data from Health Connect, read-only, as iOS's `HealthKitSource`:
 * totals through Health Connect's own aggregates (so a phone and a watch merge the way the user
 * set their priority), computed buckets for the few readings it has no aggregates for, and
 * workouts, sleep and raw readings through its changes, which also report deletions.
 *
 * A metric whose permission the user didn't give reads as empty (they chose that). Any other
 * refusal (a read in the background without that permission, Health Connect gone) stops the pass:
 * an empty window would replace what the host has.
 */
class HealthConnectSource(private val context: Context) : HealthSource {
    private val client by lazy { HealthConnectClient.getOrCreate(context) }
    private val labels = mutableMapOf<String, String>()

    @Volatile
    private var granted: Set<String>? = null

    override suspend fun prepare() {
        refreshGranted()
    }

    /** The permissions given, read again at the start of each pass. */
    suspend fun refreshGranted(): Set<String> {
        val now = guard { client.permissionController.getGrantedPermissions() }
        granted = now
        return now
    }

    private suspend fun allowed(type: KClass<out Record>): Boolean =
        HealthPermission.getReadPermission(type) in (granted ?: refreshGranted())

    // Buckets

    override suspend fun buckets(
        metric: HealthMetric,
        grain: HealthGrain,
        from: Instant,
        to: Instant,
        zone: ZoneId,
    ): List<HealthBucket> {
        if (!allowed(recordType(metric))) return emptyList()
        val range = Range(grain, from, to, zone)
        return when (metric) {
            HealthMetric.Steps -> sums(STEPS, range)

            HealthMetric.ActiveEnergy -> sums(ENERGY, range)

            HealthMetric.FlightsClimbed -> sums(FLOORS, range)

            HealthMetric.DistanceWalkingRunning, HealthMetric.DistanceCycling -> distances(metric, range)

            HealthMetric.HeartRate -> stats(HEART_RATE, range)

            HealthMetric.RestingHeartRate -> stats(RESTING_HEART_RATE, range)

            HealthMetric.BloodPressureSystolic -> stats(SYSTOLIC, range)

            HealthMetric.BloodPressureDiastolic -> stats(DIASTOLIC, range)

            HealthMetric.BodyMass -> stats(WEIGHT, range)

            HealthMetric.Height -> stats(HEIGHT, range)

            HealthMetric.OxygenSaturation, HealthMetric.RespiratoryRate,
            HealthMetric.BodyFatPercentage, HealthMetric.Vo2Max,
            -> computed(metric, range)
        }
    }

    /** What a bucket query covers. */
    private class Range(val grain: HealthGrain, val from: Instant, val to: Instant, val zone: ZoneId)

    /** Totals per bucket from Health Connect's own aggregate; empty buckets are left out. */
    private suspend fun <T : Any> sums(total: Total<T>, range: Range): List<HealthBucket> =
        grouped(setOf(total.metric), range).mapNotNull { (start, end, result) ->
            result[total.metric]?.let { HealthBucket(start, end, sum = total.value(it)) }
        }

    private suspend fun <T : Any> stats(stats: Stats<T>, range: Range): List<HealthBucket> =
        grouped(setOf(stats.min, stats.avg, stats.max), range).mapNotNull { (start, end, result) ->
            val average = result[stats.avg] ?: return@mapNotNull null
            HealthBucket(
                start,
                end,
                min = result[stats.min]?.let(stats.value),
                avg = stats.value(average),
                max = result[stats.max]?.let(stats.value),
            )
        }

    /** One aggregate per hour, or per local day (which keeps a day whole across a clock change). */
    private suspend fun grouped(
        metrics: Set<AggregateMetric<*>>,
        range: Range,
    ): List<Triple<Instant, Instant, AggregationResult>> = guard {
        if (range.grain == HealthGrain.Hour) {
            val request = AggregateGroupByDurationRequest(
                metrics,
                TimeRangeFilter.between(range.from, range.to),
                Duration.ofHours(1),
            )
            client.aggregateGroupByDuration(request).map { Triple(it.startTime, it.endTime, it.result) }
        } else {
            val request = AggregateGroupByPeriodRequest(
                metrics,
                TimeRangeFilter.between(
                    range.from.atZone(range.zone).toLocalDateTime(),
                    range.to.atZone(range.zone).toLocalDateTime(),
                ),
                Period.ofDays(1),
            )
            client.aggregateGroupByPeriod(request).map {
                Triple(
                    it.startTime.atZone(range.zone).toInstant(),
                    it.endTime.atZone(range.zone).toInstant(),
                    it.result,
                )
            }
        }
    }

    /**
     * Health Connect keeps one distance, so it's split as Apple Health splits it: what was covered in
     * a cycling workout is cycling, the rest walking and running.
     */
    private suspend fun distances(metric: HealthMetric, range: Range): List<HealthBucket> {
        val totals = grouped(setOf(DistanceRecord.DISTANCE_TOTAL), range)
        val rides = if (allowed(ExerciseSessionRecord::class)) {
            readAll(ExerciseSessionRecord::class, range.from, range.to).filter { it.exerciseType in CYCLING }
        } else {
            emptyList()
        }
        return totals.mapNotNull { (start, end, result) ->
            val total = result[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: return@mapNotNull null
            var cycling = 0.0
            for (ride in rides) {
                val overlapStart = maxOf(start, ride.startTime)
                val overlapEnd = minOf(end, ride.endTime)
                if (overlapStart < overlapEnd) cycling += distanceBetween(overlapStart, overlapEnd)
            }
            cycling = cycling.coerceAtMost(total)
            val value = if (metric == HealthMetric.DistanceCycling) cycling else total - cycling
            if (value > 0) HealthBucket(start, end, sum = value) else null
        }
    }

    private suspend fun distanceBetween(from: Instant, to: Instant): Double = guard {
        val request = AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), TimeRangeFilter.between(from, to))
        client.aggregate(request)[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
    }

    /** Readings Health Connect has no aggregate for: min, average and max of the readings in each bucket. */
    private suspend fun computed(metric: HealthMetric, range: Range): List<HealthBucket> {
        val readings: List<Pair<Instant, Double>> = when (metric) {
            HealthMetric.OxygenSaturation ->
                readAll(OxygenSaturationRecord::class, range.from, range.to).map { it.time to it.percentage.value }

            HealthMetric.RespiratoryRate ->
                readAll(RespiratoryRateRecord::class, range.from, range.to).map { it.time to it.rate }

            HealthMetric.BodyFatPercentage ->
                readAll(BodyFatRecord::class, range.from, range.to).map { it.time to it.percentage.value }

            HealthMetric.Vo2Max ->
                readAll(Vo2MaxRecord::class, range.from, range.to).map { it.time to it.vo2MillilitersPerMinuteKilogram }

            else -> emptyList()
        }
        val byBucket = readings.groupBy { (time, _) -> bucketStart(time, range) }.toSortedMap()
        return byBucket.map { (start, values) ->
            val numbers = values.map { it.second }
            val end = if (range.grain == HealthGrain.Hour) {
                start.plus(Duration.ofHours(1))
            } else {
                start.atZone(range.zone).plusDays(1).toInstant()
            }
            HealthBucket(start, end, min = numbers.min(), avg = numbers.average(), max = numbers.max())
        }
    }

    private fun bucketStart(time: Instant, range: Range): Instant = if (range.grain == HealthGrain.Hour) {
        time.truncatedTo(ChronoUnit.HOURS)
    } else {
        time.atZone(range.zone).toLocalDate().atStartOfDay(range.zone).toInstant()
    }

    // Sleep

    override suspend fun sleepSegments(from: Instant, to: Instant): List<HealthSleepSegment> {
        if (!allowed(SleepSessionRecord::class)) return emptyList()
        return readAll(SleepSessionRecord::class, from, to).flatMap(::segments)
    }

    /** A night's stages as segments, with ids `<session>:<index>`; a night without stages is all asleep. */
    private fun segments(session: SleepSessionRecord): List<HealthSleepSegment> {
        val source = source(session.metadata)
        val watch = session.metadata.device?.type == Device.TYPE_WATCH
        val id = session.metadata.id
        val stages = session.stages.sortedBy { it.startTime }.mapNotNull { stage ->
            stageOf(stage.stage)?.let { Triple(it, stage.startTime, stage.endTime) }
        }
        if (stages.isEmpty()) {
            return listOf(
                HealthSleepSegment("$id:0", HealthSleepStage.Asleep, session.startTime, session.endTime, source, watch),
            )
        }
        return stages.mapIndexed { index, (stage, start, end) ->
            HealthSleepSegment("$id:$index", stage, start, end, source, watch)
        }
    }

    private fun stageOf(type: Int): HealthSleepStage? = when (type) {
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> HealthSleepStage.Asleep

        SleepSessionRecord.STAGE_TYPE_LIGHT -> HealthSleepStage.Core

        SleepSessionRecord.STAGE_TYPE_DEEP -> HealthSleepStage.Deep

        SleepSessionRecord.STAGE_TYPE_REM -> HealthSleepStage.Rem

        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> HealthSleepStage.Awake

        // Out of bed, or unknown: not part of the night.
        else -> null
    }

    // Events

    override suspend fun changes(stream: HealthStream, since: Instant, position: String?, limit: Int): HealthChanges {
        val type = streamType(stream)
        if (!allowed(type)) return HealthChanges(emptyList(), emptyList(), position, more = false)
        val parts = position?.split('|')
        return when (parts?.firstOrNull()) {
            // Following Health Connect's changes since the token.
            FOLLOWING -> follow(stream, since, parts[1])

            // Reading what's there, page by page, before following changes from the token taken first.
            READING -> read(stream, since, parts[1], parts.getOrNull(2), limit)

            else -> {
                val token = guard { client.getChangesToken(ChangesTokenRequest(setOf(type))) }
                read(stream, since, token, null, limit)
            }
        }
    }

    private suspend fun read(
        stream: HealthStream,
        since: Instant,
        token: String,
        page: String?,
        limit: Int,
    ): HealthChanges {
        val request =
            ReadRecordsRequest(streamType(stream), TimeRangeFilter.after(since), pageSize = limit, pageToken = page)
        val response = guard { client.readRecords(request) }
        val events = response.records.map { eventsOf(stream, it) }
        val next = response.pageToken?.takeIf(String::isNotEmpty)
        return HealthChanges(
            added = events.flatMap { it.events },
            deleted = emptyList(),
            position = if (next != null) "$READING|$token|$next" else "$FOLLOWING|$token",
            more = next != null,
            series = events.mapNotNull { it.series }.toMap(),
        )
    }

    private suspend fun follow(stream: HealthStream, since: Instant, token: String): HealthChanges {
        val response = guard { client.getChanges(token) }
        // A token Health Connect forgot (after about 30 days): read everything again.
        if (response.changesTokenExpired) return HealthChanges(emptyList(), emptyList(), position = null, more = true)
        val added = mutableListOf<HealthEvent>()
        val deleted = mutableListOf<String>()
        val series = mutableMapOf<String, Int?>()
        for (change in response.changes) {
            when (change) {
                is UpsertionChange -> {
                    if (startOf(change.record) < since) continue
                    val events = eventsOf(stream, change.record)
                    added += events.events
                    events.series?.let { series[it.first] = it.second }
                }

                is DeletionChange -> when {
                    stream.hasSeries -> series[change.recordId] = null
                    stream == SYSTOLIC_SAMPLES -> deleted += "${change.recordId}:systolic"
                    stream == DIASTOLIC_SAMPLES -> deleted += "${change.recordId}:diastolic"
                    else -> deleted += change.recordId
                }
            }
        }
        return HealthChanges(added, deleted, "$FOLLOWING|${response.nextChangesToken}", response.hasMore, series)
    }

    /** A record's events, and for a record of several (`<id>:<index>`) how many it has now. */
    private class RecordEvents(val events: List<HealthEvent>, val series: Pair<String, Int>? = null)

    private suspend fun eventsOf(stream: HealthStream, record: Record): RecordEvents = when (record) {
        is ExerciseSessionRecord -> RecordEvents(listOf(workout(record)))

        is SleepSessionRecord -> {
            val segments = segments(record)
            RecordEvents(segments.map(HealthSleepSegment::event), record.metadata.id to segments.size)
        }

        is HeartRateRecord -> {
            val source = source(record.metadata)
            val id = record.metadata.id
            val events = record.samples.mapIndexed { index, sample ->
                sample(
                    "$id:$index",
                    HealthMetric.HeartRate,
                    sample.time,
                    sample.time,
                    sample.beatsPerMinute.toDouble(),
                    source,
                )
            }
            RecordEvents(events, id to events.size)
        }

        else -> RecordEvents(listOfNotNull(sampleOf((stream as? HealthStream.Samples)?.metric, record)))
    }

    private fun sampleOf(metric: HealthMetric?, record: Record): HealthEvent? {
        val source = source(record.metadata)
        val id = record.metadata.id
        fun at(time: Instant, metric: HealthMetric, value: Double) = sample(id, metric, time, time, value, source)
        return when (record) {
            is StepsRecord -> sample(
                id,
                HealthMetric.Steps,
                record.startTime,
                record.endTime,
                record.count.toDouble(),
                source,
            )

            is DistanceRecord -> {
                val which = metric ?: HealthMetric.DistanceWalkingRunning
                sample(id, which, record.startTime, record.endTime, record.distance.inMeters, source)
            }

            is ActiveCaloriesBurnedRecord ->
                sample(
                    id,
                    HealthMetric.ActiveEnergy,
                    record.startTime,
                    record.endTime,
                    record.energy.inKilocalories,
                    source,
                )

            is FloorsClimbedRecord -> sample(
                id,
                HealthMetric.FlightsClimbed,
                record.startTime,
                record.endTime,
                record.floors,
                source,
            )

            is RestingHeartRateRecord -> at(
                record.time,
                HealthMetric.RestingHeartRate,
                record.beatsPerMinute.toDouble(),
            )

            is OxygenSaturationRecord -> at(record.time, HealthMetric.OxygenSaturation, record.percentage.value)

            is RespiratoryRateRecord -> at(record.time, HealthMetric.RespiratoryRate, record.rate)

            is BloodPressureRecord -> if (metric == HealthMetric.BloodPressureDiastolic) {
                val value = record.diastolic.inMillimetersOfMercury
                sample("$id:diastolic", HealthMetric.BloodPressureDiastolic, record.time, record.time, value, source)
            } else {
                val value = record.systolic.inMillimetersOfMercury
                sample("$id:systolic", HealthMetric.BloodPressureSystolic, record.time, record.time, value, source)
            }

            is WeightRecord -> at(record.time, HealthMetric.BodyMass, record.weight.inKilograms)

            is BodyFatRecord -> at(record.time, HealthMetric.BodyFatPercentage, record.percentage.value)

            is HeightRecord -> at(record.time, HealthMetric.Height, record.height.inMeters)

            is Vo2MaxRecord -> at(record.time, HealthMetric.Vo2Max, record.vo2MillilitersPerMinuteKilogram)

            else -> null
        }
    }

    private fun sample(id: String, metric: HealthMetric, start: Instant, end: Instant, value: Double, source: String) =
        HealthEvent(
            id,
            HealthEventKind.Sample,
            metric.wire,
            start,
            end,
            value = value,
            unit = metric.unit,
            source = source,
        )

    /** A workout with its energy, distance and heart rate over its time, as iOS's. */
    private suspend fun workout(session: ExerciseSessionRecord): HealthEvent {
        val minutes = Duration.between(session.startTime, session.endTime).toMillis() / MINUTE_MS
        val data = mutableMapOf<String, HealthValue>(
            "activity" to HealthValue.Text(activityName(session.exerciseType)),
            "duration_min" to HealthValue.Number(rounded(minutes * TENTHS) / TENTHS),
        )
        val request = AggregateRequest(
            setOf(
                ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                DistanceRecord.DISTANCE_TOTAL,
                HeartRateRecord.BPM_AVG,
                HeartRateRecord.BPM_MAX,
            ),
            TimeRangeFilter.between(session.startTime, session.endTime),
        )
        val totals = guard { client.aggregate(request) }
        totals[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.let {
            data["energy_kcal"] = HealthValue.Number(rounded(it.inKilocalories))
        }
        totals[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.takeIf { it > 0 }?.let {
            data["distance_m"] = HealthValue.Number(rounded(it))
        }
        totals[HeartRateRecord.BPM_AVG]?.let { data["avg_heart_rate"] = HealthValue.Number(it.toDouble()) }
        totals[HeartRateRecord.BPM_MAX]?.let { data["max_heart_rate"] = HealthValue.Number(it.toDouble()) }
        return HealthEvent(
            session.metadata.id,
            HealthEventKind.Workout,
            "workout",
            session.startTime,
            session.endTime,
            data = data,
            source = source(session.metadata),
        )
    }

    // Reading

    private suspend fun <T : Record> readAll(type: KClass<T>, from: Instant, to: Instant): List<T> {
        val records = mutableListOf<T>()
        var page: String? = null
        do {
            val request =
                ReadRecordsRequest(type, TimeRangeFilter.between(from, to), pageSize = READ_PAGE, pageToken = page)
            val response = guard { client.readRecords(request) }
            records += response.records
            page = response.pageToken?.takeIf(String::isNotEmpty)
        } while (page != null)
        return records
    }

    /** The app that recorded it, by name where the phone can say it. */
    private fun source(metadata: Metadata): String {
        val packageName = metadata.dataOrigin.packageName
        return labels.getOrPut(packageName) {
            runCatching {
                val manager = context.packageManager
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    manager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    manager.getApplicationInfo(packageName, 0)
                }
                manager.getApplicationLabel(info).toString()
            }.getOrDefault(packageName)
        }.take(HealthBatch.MAX_SOURCE)
    }

    /** A refusal (no access, Health Connect gone) stops the pass instead of reading as empty. */
    private suspend fun <T> guard(read: suspend () -> T): T = try {
        read()
    } catch (refused: SecurityException) {
        throw HealthUnavailable(refused.message ?: "Health Connect refused the read")
    } catch (gone: IllegalStateException) {
        throw HealthUnavailable(gone.message ?: "Health Connect isn't available")
    }

    companion object {
        private const val READING = "r"
        private const val FOLLOWING = "c"
        private const val READ_PAGE = 1_000
        private const val MINUTE_MS = 60_000.0
        private const val TENTHS = 10.0
        private const val HALF = 0.5

        private val CYCLING = setOf(
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
        )
        private val SYSTOLIC_SAMPLES = HealthStream.Samples(HealthMetric.BloodPressureSystolic)
        private val DIASTOLIC_SAMPLES = HealthStream.Samples(HealthMetric.BloodPressureDiastolic)

        /** Streams whose records carry several events (`<record>:<index>`). */
        private val HealthStream.hasSeries: Boolean
            get() = this == HealthStream.Sleep || this == HealthStream.Samples(HealthMetric.HeartRate)

        private val STEPS = Total(StepsRecord.COUNT_TOTAL) { it.toDouble() }
        private val ENERGY = Total(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL) { it.inKilocalories }
        private val FLOORS = Total(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL) { it }
        private val HEART_RATE = Stats(HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX) {
            it.toDouble()
        }
        private val RESTING_HEART_RATE = Stats(
            RestingHeartRateRecord.BPM_MIN,
            RestingHeartRateRecord.BPM_AVG,
            RestingHeartRateRecord.BPM_MAX,
        ) { it.toDouble() }
        private val SYSTOLIC = Stats(
            BloodPressureRecord.SYSTOLIC_MIN,
            BloodPressureRecord.SYSTOLIC_AVG,
            BloodPressureRecord.SYSTOLIC_MAX,
        ) { it.inMillimetersOfMercury }
        private val DIASTOLIC = Stats(
            BloodPressureRecord.DIASTOLIC_MIN,
            BloodPressureRecord.DIASTOLIC_AVG,
            BloodPressureRecord.DIASTOLIC_MAX,
        ) { it.inMillimetersOfMercury }
        private val WEIGHT =
            Stats(WeightRecord.WEIGHT_MIN, WeightRecord.WEIGHT_AVG, WeightRecord.WEIGHT_MAX) { it.inKilograms }
        private val HEIGHT =
            Stats(HeightRecord.HEIGHT_MIN, HeightRecord.HEIGHT_AVG, HeightRecord.HEIGHT_MAX) { it.inMeters }

        /** The record type each metric is read from. */
        fun recordType(metric: HealthMetric): KClass<out Record> = when (metric) {
            HealthMetric.Steps -> StepsRecord::class
            HealthMetric.DistanceWalkingRunning, HealthMetric.DistanceCycling -> DistanceRecord::class
            HealthMetric.ActiveEnergy -> ActiveCaloriesBurnedRecord::class
            HealthMetric.FlightsClimbed -> FloorsClimbedRecord::class
            HealthMetric.HeartRate -> HeartRateRecord::class
            HealthMetric.RestingHeartRate -> RestingHeartRateRecord::class
            HealthMetric.OxygenSaturation -> OxygenSaturationRecord::class
            HealthMetric.RespiratoryRate -> RespiratoryRateRecord::class
            HealthMetric.BloodPressureSystolic, HealthMetric.BloodPressureDiastolic -> BloodPressureRecord::class
            HealthMetric.BodyMass -> WeightRecord::class
            HealthMetric.BodyFatPercentage -> BodyFatRecord::class
            HealthMetric.Height -> HeightRecord::class
            HealthMetric.Vo2Max -> Vo2MaxRecord::class
        }

        fun streamType(stream: HealthStream): KClass<out Record> = when (stream) {
            HealthStream.Workouts -> ExerciseSessionRecord::class
            HealthStream.Sleep -> SleepSessionRecord::class
            is HealthStream.Samples -> recordType(stream.metric)
        }

        /** The read permissions each category needs (workouts read their energy, distance and heart rate too). */
        fun readPermissions(categories: Set<HealthCategory>): Set<String> = categories.flatMap { category ->
            when (category) {
                HealthCategory.Sleep -> listOf(SleepSessionRecord::class)

                HealthCategory.Workouts -> listOf(
                    ExerciseSessionRecord::class,
                    ActiveCaloriesBurnedRecord::class,
                    DistanceRecord::class,
                    HeartRateRecord::class,
                )

                // The distance split needs the cycling workouts.
                HealthCategory.Activity -> category.metrics.map(::recordType) + ExerciseSessionRecord::class

                else -> category.metrics.map(::recordType)
            }.map { HealthPermission.getReadPermission(it) }
        }.toSet()

        private fun startOf(record: Record): Instant = when (record) {
            is ExerciseSessionRecord -> record.startTime
            is SleepSessionRecord -> record.startTime
            is StepsRecord -> record.startTime
            is DistanceRecord -> record.startTime
            is ActiveCaloriesBurnedRecord -> record.startTime
            is FloorsClimbedRecord -> record.startTime
            is HeartRateRecord -> record.startTime
            is RestingHeartRateRecord -> record.time
            is OxygenSaturationRecord -> record.time
            is RespiratoryRateRecord -> record.time
            is BloodPressureRecord -> record.time
            is WeightRecord -> record.time
            is BodyFatRecord -> record.time
            is HeightRecord -> record.time
            is Vo2MaxRecord -> record.time
            else -> Instant.MAX
        }

        /** Rounded as iOS rounds: halves away from zero. */
        private fun rounded(value: Double): Double = sign(value) * floor(abs(value) + HALF)

        /** Health Connect's exercise types under iOS's activity names. */
        fun activityName(type: Int): String = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
            -> "running"

            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"

            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
            -> "cycling"

            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
            -> "swimming"

            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hiking"

            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "yoga"

            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
            -> "strength_training"

            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "functional_strength_training"

            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "hiit"

            ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "elliptical"

            ExerciseSessionRecord.EXERCISE_TYPE_ROWING,
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
            -> "rowing"

            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING,
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE,
            -> "stair_climbing"

            ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "dance"

            ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "pilates"

            ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "soccer"

            ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "basketball"

            ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "tennis"

            ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> "golf"

            ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP,
            ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS,
            -> "cross_training"

            ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING -> "mind_and_body"

            ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "flexibility"

            ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> "climbing"

            ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> "martial_arts"

            ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "boxing"

            ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "skiing"

            ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> "snowboarding"

            ExerciseSessionRecord.EXERCISE_TYPE_PADDLING -> "paddling"

            ExerciseSessionRecord.EXERCISE_TYPE_SURFING -> "surfing"

            ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR -> "wheelchair"

            else -> "other_$type"
        }
    }
}
