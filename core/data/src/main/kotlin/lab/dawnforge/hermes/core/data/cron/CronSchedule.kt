package lab.dawnforge.hermes.core.data.cron

import java.time.DayOfWeek
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import lab.dawnforge.hermes.core.model.CronJob

/** An interval's unit, as Hermes writes it ("every 2h"). */
enum class IntervalUnit(val suffix: String, val minutes: Long) {
    Minutes("m", 1),
    Hours("h", 60),
    Days("d", 24 * 60),
}

/**
 * A schedule as the editor builds it, as on iOS: at a time on chosen days, every so often, once,
 * or Hermes' own words. [toHermes] is the text Hermes reads; null while it isn't one yet.
 */
sealed interface ScheduleChoice {
    fun toHermes(): String?

    /** At [hour]:[minute] in the profile's time zone, on [days]. */
    data class AtTime(
        val hour: Int = DEFAULT_HOUR,
        val minute: Int = 0,
        val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    ) : ScheduleChoice {
        override fun toHermes(): String? {
            if (days.isEmpty() || hour !in 0..LAST_HOUR || minute !in 0..LAST_MINUTE) return null
            val dow = when (days) {
                DayOfWeek.entries.toSet() -> "*"
                WEEKDAYS -> "1-5"
                else -> days.map { it.cronDay() }.sorted().joinToString(",")
            }
            return "$minute $hour * * $dow"
        }
    }

    data class Every(val count: Int = 1, val unit: IntervalUnit = IntervalUnit.Hours) : ScheduleChoice {
        override fun toHermes(): String? = if (count in 1..MAX_COUNT) "every $count${unit.suffix}" else null
    }

    /** Once, at [at]: sent with the phone's offset, so it is the moment the user picked. */
    data class Once(val at: OffsetDateTime) : ScheduleChoice {
        override fun toHermes(): String = at.truncatedTo(ChronoUnit.MINUTES).format(ONCE_FORMAT)
    }

    /** A cron expression, or a phrase Hermes reads ("weekdays at 9am"). */
    data class Custom(val text: String) : ScheduleChoice {
        override fun toHermes(): String? = text.trim().takeIf(String::isNotEmpty)
    }

    companion object {
        const val DEFAULT_HOUR = 8
        const val MAX_COUNT = 999
        private const val LAST_HOUR = 23
        private const val LAST_MINUTE = 59
        val WEEKDAYS = setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
        )
        private val ONCE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

        /**
         * How the editor opens [job]: an interval as Every (in the largest unit that divides it),
         * a one-off as Once, a plain daily or weekly time as At a time, and anything else as
         * Custom with Hermes' expression, so saving it unchanged leaves it alone.
         */
        fun of(job: CronJob, zone: ZoneId = ZoneId.systemDefault()): ScheduleChoice {
            val input = job.scheduleInput
            return when (job.scheduleKind) {
                "interval" -> intervalMinutes(input)?.let(::every)

                "once" -> parseInstant(input)?.let { Once(OffsetDateTime.ofInstant(it, zone)) }

                "cron" -> CronFields.of(input)?.takeIf { it.dayOfMonth == "*" && it.month == "*" }?.let { fields ->
                    val hour = fields.hour.toIntOrNull()
                    val minute = fields.minute.toIntOrNull()
                    val days = cronDays(fields.dayOfWeek)
                    if (hour == null || minute == null || days == null) null else AtTime(hour, minute, days)
                }

                else -> null
            } ?: Custom(input)
        }

        private fun every(minutes: Long): Every? {
            val unit = IntervalUnit.entries.last { minutes % it.minutes == 0L }
            val count = minutes / unit.minutes
            return if (count in 1..MAX_COUNT) Every(count.toInt(), unit) else null
        }
    }
}

/** A schedule as words can say it, for the list and the editor's preview. */
sealed interface ScheduleWords {
    data class Every(val count: Long, val unit: IntervalUnit) : ScheduleWords

    data class HourlyAt(val minute: Int) : ScheduleWords

    /** At [hour]:[minute] on [days]: every day, weekdays, weekends, or the days named. */
    data class At(val hour: Int, val minute: Int, val days: Set<DayOfWeek>) : ScheduleWords

    data class MonthlyAt(val day: Int, val hour: Int, val minute: Int) : ScheduleWords

    data class Once(val epochMs: Long) : ScheduleWords

    /** Hermes' own wording, for anything else. */
    data class AsHermesSays(val text: String) : ScheduleWords

    /** Whether the time is a cron time, read in the profile's time zone. */
    val inProfileZone: Boolean get() = this is HourlyAt || this is At || this is MonthlyAt

    companion object {
        fun of(job: CronJob): ScheduleWords = of(job.scheduleKind, job.scheduleInput, job.schedule)

        /** [kind] is Hermes' (`cron`, `interval`, `once`); [display] is its wording, used when nothing fits. */
        fun of(kind: String?, input: String, display: String = input): ScheduleWords {
            val words = when (kind) {
                "interval" -> intervalMinutes(input)?.let { minutes ->
                    val unit = IntervalUnit.entries.last { minutes % it.minutes == 0L }
                    Every(minutes / unit.minutes, unit)
                }

                "once" -> parseInstant(input)?.let { Once(it.toEpochMilli()) }

                else -> CronFields.of(input)?.let(::cronWords)
            }
            return words ?: AsHermesSays(display.ifBlank { input })
        }

        /** What the editor's choice reads as, or null while it isn't a schedule yet. */
        fun of(choice: ScheduleChoice): ScheduleWords? = when (choice) {
            is ScheduleChoice.AtTime -> choice.toHermes()?.let { At(choice.hour, choice.minute, choice.days) }
            is ScheduleChoice.Every -> choice.toHermes()?.let { Every(choice.count.toLong(), choice.unit) }
            is ScheduleChoice.Once -> Once(choice.at.toInstant().toEpochMilli())
            is ScheduleChoice.Custom -> choice.toHermes()?.let { of("cron", it) }
        }

        private fun cronWords(fields: CronFields): ScheduleWords? {
            val minute = fields.minute.toIntOrNull() ?: return null
            if (fields.month != "*") return null
            if (fields.hour == "*" && fields.dayOfMonth == "*" && fields.dayOfWeek == "*") return HourlyAt(minute)
            val hour = fields.hour.toIntOrNull() ?: return null
            if (fields.dayOfMonth == "*") return cronDays(fields.dayOfWeek)?.let { At(hour, minute, it) }
            val day = fields.dayOfMonth.toIntOrNull() ?: return null
            return if (fields.dayOfWeek == "*") MonthlyAt(day, hour, minute) else null
        }
    }
}

/** A five-field cron expression, split; null for anything else. */
private data class CronFields(
    val minute: String,
    val hour: String,
    val dayOfMonth: String,
    val month: String,
    val dayOfWeek: String,
) {
    companion object {
        private const val FIELDS = 5

        fun of(text: String): CronFields? {
            val parts = text.trim().split(Regex("""\s+"""))
            if (parts.size != FIELDS) return null
            return CronFields(parts[0], parts[1], parts[2], parts[3], parts[4])
        }
    }
}

private const val SUNDAY_LATE = 7

/** Sunday is 0 in cron (7 too). */
private fun DayOfWeek.cronDay(): Int = value % SUNDAY_LATE

/** A day-of-week field of `*`, ranges and lists of numbers; null for names and steps. */
private fun cronDays(field: String): Set<DayOfWeek>? {
    if (field == "*") return DayOfWeek.entries.toSet()
    val days = mutableSetOf<DayOfWeek>()
    for (part in field.split(',')) {
        val bounds = part.split('-').map { it.toIntOrNull()?.takeIf { day -> day in 0..SUNDAY_LATE } ?: return null }
        val range = when (bounds.size) {
            1 -> bounds[0]..bounds[0]
            2 -> bounds[0]..bounds[1]
            else -> return null
        }
        if (range.isEmpty()) return null
        range.forEach { days += DayOfWeek.of(if (it == 0) SUNDAY_LATE else it) }
    }
    return days
}

/** "every 120m", as the repository reads an interval back. */
private fun intervalMinutes(input: String): Long? =
    Regex("""every (\d+)m""").matchEntire(input.trim())?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }

private fun parseInstant(text: String): Instant? = try {
    OffsetDateTime.parse(text).toInstant()
} catch (_: DateTimeParseException) {
    null
}
