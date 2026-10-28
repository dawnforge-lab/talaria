package lab.dawnforge.hermes.core.data.phonedata.health

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class HealthGrain(val wire: String) { Hour("hour"), Day("day"), Night("night") }

/** A number or a text in a night's or an event's `data`. */
sealed interface HealthValue {
    data class Number(val value: Double) : HealthValue

    data class Text(val value: String) : HealthValue

    fun json(): JsonPrimitive = when (this) {
        is Number -> JsonPrimitive(value)
        is Text -> JsonPrimitive(value)
    }
}

/** One hourly, daily or nightly value: sums carry `sum`, the rest `min`/`avg`/`max`, a night `data`. */
data class HealthBucket(
    val start: Instant,
    val end: Instant,
    val sum: Double? = null,
    val min: Double? = null,
    val avg: Double? = null,
    val max: Double? = null,
    /** A night: the morning it ended (local date). */
    val date: String? = null,
    val data: Map<String, HealthValue> = emptyMap(),
    val source: String? = null,
)

/** A whole range the phone recomputed for one metric and grain: the host replaces [from, to) with it. */
data class HealthWindow(
    val metric: String,
    val grain: HealthGrain,
    val unit: String,
    val from: Instant,
    val to: Instant,
    val buckets: List<HealthBucket>,
)

enum class HealthEventKind(val wire: String) { Workout("workout"), Sleep("sleep"), Sample("sample") }

/** A workout, a sleep stage segment or a raw reading, by its own id. */
data class HealthEvent(
    val id: String,
    val kind: HealthEventKind,
    val metric: String,
    val start: Instant,
    val end: Instant,
    val value: Double? = null,
    val unit: String? = null,
    val data: Map<String, HealthValue> = emptyMap(),
    val source: String? = null,
)

/** ISO 8601 with the local offset, so "the 13th" stays the user's 13th on the host. */
class HealthDates(val zone: ZoneId) {
    fun format(instant: Instant): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        instant.truncatedTo(ChronoUnit.SECONDS).atZone(zone).toOffsetDateTime(),
    )

    fun day(instant: Instant): String = instant.atZone(zone).toLocalDate().toString()

    fun startOfDay(instant: Instant): Instant = instant.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

    fun startOfDay(date: LocalDate): Instant = date.atStartOfDay(zone).toInstant()

    fun addDays(instant: Instant, days: Long): Instant = instant.atZone(zone).plusDays(days).toInstant()
}

/** One upload's plaintext, as iOS's `HealthBatch`. */
data class HealthBatch(
    val batchId: String = UUID.randomUUID().toString().replace("-", ""),
    val computedAt: Instant,
    val deviceName: String,
    val zone: ZoneId,
    val categories: List<HealthCategory>,
    val detailed: List<HealthCategory>,
    val windows: List<HealthWindow> = emptyList(),
    val events: List<HealthEvent> = emptyList(),
    val deleted: List<String> = emptyList(),
) {
    val itemCount: Int get() = windows.sumOf { it.buckets.size } + events.size + deleted.size
    val isEmpty: Boolean get() = windows.isEmpty() && events.isEmpty() && deleted.isEmpty()

    fun wire(): JsonObject {
        val dates = HealthDates(zone)
        return buildJsonObject {
            put("batch_id", batchId)
            put("computed_at", dates.format(computedAt))
            put("device", device())
            put("categories", JsonArray(categories.map { JsonPrimitive(it.wire) }.sortedBy { it.content }))
            put("detailed", JsonArray(detailed.map { JsonPrimitive(it.wire) }.sortedBy { it.content }))
            put("windows", JsonArray(windows.map { window(it, dates) }))
            put("events", JsonArray(events.map { event(it, dates) }))
            put("deleted", JsonArray(deleted.map(::JsonPrimitive)))
        }
    }

    private fun device() = buildJsonObject {
        put("name", deviceName)
        put("time_zone", zone.id)
    }

    private fun window(window: HealthWindow, dates: HealthDates) = buildJsonObject {
        put("metric", window.metric)
        put("grain", window.grain.wire)
        put("unit", window.unit)
        put("from", dates.format(window.from))
        put("to", dates.format(window.to))
        put(
            "buckets",
            JsonArray(
                window.buckets.map { bucket ->
                    buildJsonObject {
                        put("start", dates.format(bucket.start))
                        put("end", dates.format(bucket.end))
                        bucket.sum?.let { put("sum", it) }
                        bucket.min?.let { put("min", it) }
                        bucket.avg?.let { put("avg", it) }
                        bucket.max?.let { put("max", it) }
                        bucket.date?.let { put("date", it) }
                        bucket.source?.let { put("source", it.take(MAX_SOURCE)) }
                        if (bucket.data.isNotEmpty()) put("data", JsonObject(bucket.data.mapValues { it.value.json() }))
                    }
                },
            ),
        )
    }

    private fun event(event: HealthEvent, dates: HealthDates) = buildJsonObject {
        put("id", event.id)
        put("kind", event.kind.wire)
        put("metric", event.metric)
        put("start", dates.format(event.start))
        put("end", dates.format(event.end))
        event.value?.let { put("value", it) }
        event.unit?.let { put("unit", it) }
        event.source?.let { put("source", it.take(MAX_SOURCE)) }
        if (event.data.isNotEmpty()) put("data", JsonObject(event.data.mapValues { it.value.json() }))
    }

    /**
     * Each window's place on the host (metric, grain and range) and a hash of what it carries, in
     * `windows`' order. What the batch says around the windows (device, time zone, what's shared) is
     * in every hash, so a change there sends every window again.
     */
    fun windowPrints(): List<Pair<String, String>> {
        val dates = HealthDates(zone)
        val scope = buildJsonObject {
            put("device", device())
            put("categories", JsonArray(categories.map { JsonPrimitive(it.wire) }.sortedBy { it.content }))
            put("detailed", JsonArray(detailed.map { JsonPrimitive(it.wire) }.sortedBy { it.content }))
        }.toString()
        return windows.map { window ->
            val key = "${window.metric}|${window.grain.wire}|${dates.format(window.from)}|${dates.format(window.to)}"
            key to hash(scope + window(window, dates).toString())
        }
    }

    /**
     * Splits into batches the host takes, keeping each window whole (a window replaces its range on
     * the host). A window bigger than the limit goes alone.
     */
    fun split(limit: Int = HealthLimits.MAX_ITEMS_PER_BATCH): List<HealthBatch> {
        val parts = mutableListOf<HealthBatch>()
        var windows = mutableListOf<HealthWindow>()
        var events = mutableListOf<HealthEvent>()
        var deleted = mutableListOf<String>()
        var count = 0
        fun flush() {
            if (windows.isNotEmpty() || events.isNotEmpty() || deleted.isNotEmpty()) {
                parts += empty().copy(windows = windows, events = events, deleted = deleted)
            }
            windows = mutableListOf()
            events = mutableListOf()
            deleted = mutableListOf()
            count = 0
        }
        for (window in this.windows) {
            if (count + window.buckets.size > limit) flush()
            windows += window
            count += window.buckets.size
        }
        for (event in this.events) {
            if (count + 1 > limit) flush()
            events += event
            count++
        }
        for (id in this.deleted) {
            if (count + 1 > limit) flush()
            deleted += id
            count++
        }
        flush()
        return parts
    }

    /** The same batch with nothing in it, under a new id. */
    fun empty(): HealthBatch = HealthBatch(
        computedAt = computedAt,
        deviceName = deviceName,
        zone = zone,
        categories = categories,
        detailed = detailed,
    )

    companion object {
        const val MAX_SOURCE = 120

        private fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .take(HASH_BYTES).joinToString("") { "%02x".format(it) }

        private const val HASH_BYTES = 12
    }
}

// Sleep

enum class HealthSleepStage(val wire: String) {
    InBed("in_bed"),
    Asleep("asleep"),
    Core("core"),
    Deep("deep"),
    Rem("rem"),
    Awake("awake"),
    ;

    val isAsleep: Boolean get() = this == Asleep || this == Core || this == Deep || this == Rem
}

/** One stretch of a night as the phone recorded it. */
data class HealthSleepSegment(
    val id: String,
    val stage: HealthSleepStage,
    val start: Instant,
    val end: Instant,
    /** The app that recorded it. */
    val source: String,
    /** Recorded by a watch. */
    val fromWatch: Boolean,
) {
    val minutes: Double get() = (end.toEpochMilli() - start.toEpochMilli()) / MINUTE_MS

    fun event(): HealthEvent = HealthEvent(
        id = id,
        kind = HealthEventKind.Sleep,
        metric = "sleep",
        start = start,
        end = end,
        data = mapOf("stage" to HealthValue.Text(stage.wire)),
        source = source,
    )

    private companion object {
        const val MINUTE_MS = 60_000.0
    }
}

/**
 * The nightly summary rule, as iOS's `HealthSleepNights`: a night runs noon to noon and is named by
 * the morning it ends. Apps overlap, so one source is chosen per night: a watch first, otherwise the
 * one with the most time asleep (then the most time at all, then its name). Deterministic.
 */
object HealthSleepNights {
    private const val NOON = 12L

    fun nightEnding(instant: Instant, zone: ZoneId): LocalDate {
        val local = instant.atZone(zone)
        return if (local.hour < NOON) local.toLocalDate() else local.toLocalDate().plusDays(1)
    }

    /** The window for the nights whose mornings fall in [firstMorning, endMorning): noon before each. */
    fun window(firstMorning: LocalDate, endMorning: LocalDate, zone: ZoneId): Pair<Instant, Instant> =
        firstMorning.atStartOfDay(zone).minusHours(NOON).toInstant() to
            endMorning.atStartOfDay(zone).minusHours(NOON).toInstant()

    fun summaries(segments: List<HealthSleepSegment>, zone: ZoneId): List<HealthBucket> {
        val dates = HealthDates(zone)
        return segments.groupBy { nightEnding(it.start, zone) }.toSortedMap().mapNotNull { (morning, all) ->
            val chosen = chosenSource(all) ?: return@mapNotNull null
            val night = chosen.second.sortedBy { it.start }
            val first = night.firstOrNull() ?: return@mapNotNull null
            val last = night.maxBy { it.end }
            fun minutes(stages: Set<HealthSleepStage>) =
                rounded(night.filter { it.stage in stages }.sumOf { it.minutes })
            val asleep =
                minutes(
                    setOf(HealthSleepStage.Asleep, HealthSleepStage.Core, HealthSleepStage.Deep, HealthSleepStage.Rem),
                )
            val explicitInBed = minutes(setOf(HealthSleepStage.InBed))
            val span = rounded((last.end.toEpochMilli() - first.start.toEpochMilli()) / MINUTE_MS)
            val data = mutableMapOf<String, HealthValue>(
                "asleep" to HealthValue.Number(asleep),
                "in_bed" to HealthValue.Number(if (explicitInBed > 0) explicitInBed else span),
                "awake" to HealthValue.Number(minutes(setOf(HealthSleepStage.Awake))),
                "wake_count" to HealthValue.Number(night.count { it.stage == HealthSleepStage.Awake }.toDouble()),
                "bedtime" to HealthValue.Text(dates.format(first.start)),
                "wake_time" to HealthValue.Text(dates.format(last.end)),
            )
            if (night.any {
                    it.stage == HealthSleepStage.Core || it.stage == HealthSleepStage.Deep ||
                        it.stage == HealthSleepStage.Rem
                }
            ) {
                data["core"] = HealthValue.Number(minutes(setOf(HealthSleepStage.Core)))
                data["deep"] = HealthValue.Number(minutes(setOf(HealthSleepStage.Deep)))
                data["rem"] = HealthValue.Number(minutes(setOf(HealthSleepStage.Rem)))
            }
            val unspecified = minutes(setOf(HealthSleepStage.Asleep))
            if (unspecified > 0) data["unspecified"] = HealthValue.Number(unspecified)
            HealthBucket(
                start = first.start,
                end = last.end,
                date = morning.toString(),
                data = data,
                source = chosen.first,
            )
        }
    }

    private fun chosenSource(segments: List<HealthSleepSegment>): Pair<String, List<HealthSleepSegment>>? =
        segments.groupBy { it.source }.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<HealthSleepSegment>>> { entry ->
                entry.value.any { it.fromWatch }
            }
                .thenByDescending { entry -> entry.value.filter { it.stage.isAsleep }.sumOf { it.minutes } }
                .thenByDescending { entry -> entry.value.sumOf { it.minutes } }
                // Ties fall back to the name, so the same data always picks the same source.
                .thenBy { it.key },
        ).firstOrNull()?.let { it.key to it.value }

    /** Rounded as iOS rounds: halves away from zero. */
    private fun rounded(value: Double): Double = sign(value) * floor(abs(value) + HALF)

    private const val HALF = 0.5
    private const val MINUTE_MS = 60_000.0
}
