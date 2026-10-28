package lab.dawnforge.hermes.feature.cron

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import lab.dawnforge.hermes.core.data.cron.IntervalUnit
import lab.dawnforge.hermes.core.data.cron.ScheduleChoice
import lab.dawnforge.hermes.core.data.cron.ScheduleWords
import lab.dawnforge.hermes.core.model.CronJob
import lab.dawnforge.hermes.core.model.CronState
import lab.dawnforge.hermes.core.model.DeliveryTarget

/** A schedule in plain words, with the profile's time zone after a cron time when it isn't the phone's. */
@Composable
internal fun scheduleText(words: ScheduleWords, zoneLabel: String?): String {
    val text = when (words) {
        is ScheduleWords.Every -> when (words.unit) {
            IntervalUnit.Minutes -> pluralStringResource(
                R.plurals.feature_cron_every_minutes,
                words.count.toInt(),
                words.count.toInt(),
            )

            IntervalUnit.Hours -> pluralStringResource(
                R.plurals.feature_cron_every_hours,
                words.count.toInt(),
                words.count.toInt(),
            )

            IntervalUnit.Days -> pluralStringResource(
                R.plurals.feature_cron_every_days,
                words.count.toInt(),
                words.count.toInt(),
            )
        }

        is ScheduleWords.HourlyAt -> stringResource(R.string.feature_cron_words_hourly, "%02d".format(words.minute))

        is ScheduleWords.At -> {
            val time = clockTime(words.hour, words.minute)
            when (words.days) {
                DayOfWeek.entries.toSet() -> stringResource(R.string.feature_cron_words_daily, time)
                ScheduleChoice.WEEKDAYS -> stringResource(R.string.feature_cron_words_weekdays, time)
                WEEKEND -> stringResource(R.string.feature_cron_words_weekends, time)
                else -> stringResource(R.string.feature_cron_words_days, dayList(words.days), time)
            }
        }

        is ScheduleWords.MonthlyAt ->
            stringResource(R.string.feature_cron_words_monthly, words.day, clockTime(words.hour, words.minute))

        is ScheduleWords.Once -> stringResource(R.string.feature_cron_words_once, dateTime(words.epochMs))

        is ScheduleWords.AsHermesSays -> words.text
    }
    return if (zoneLabel != null && words.inProfileZone) "$text ($zoneLabel)" else text
}

@Composable
internal fun scheduleText(job: CronJob, zoneLabel: String?): String = scheduleText(ScheduleWords.of(job), zoneLabel)

/** "Next run in 3 hours" within a day, else "Next run Mon 22 Oct, 08:00"; finished and paused jobs say so. */
@Composable
internal fun nextRunText(job: CronJob, nowMs: Long = System.currentTimeMillis()): String? = when (job.state) {
    CronState.Completed -> stringResource(R.string.feature_cron_state_completed)

    CronState.Paused -> stringResource(R.string.feature_cron_state_paused)

    CronState.Error -> stringResource(R.string.feature_cron_state_error)

    CronState.Scheduled -> job.nextRunAtMs?.let { next ->
        val minutes = ((next - nowMs) / MINUTE_MS).coerceAtLeast(0)
        when {
            minutes < 1 -> stringResource(R.string.feature_cron_next_soon)

            minutes < HOUR_MINUTES -> pluralStringResource(
                R.plurals.feature_cron_next_minutes,
                minutes.toInt(),
                minutes.toInt(),
            )

            minutes < DAY_MINUTES -> (minutes / HOUR_MINUTES).toInt().let {
                pluralStringResource(R.plurals.feature_cron_next_hours, it, it)
            }

            else -> stringResource(R.string.feature_cron_next_run, dateTime(next))
        }
    }
}

/** A target as the phone names it: saving only and the home channel by what they do, the rest as Hermes does. */
@Composable
internal fun deliveryTitle(id: String, targets: List<DeliveryTarget>): String = when (id) {
    DeliveryTarget.LOCAL -> stringResource(R.string.feature_cron_deliver_local)
    DeliveryTarget.ORIGIN -> stringResource(R.string.feature_cron_deliver_origin)
    else -> targets.firstOrNull { it.id == id }?.name ?: id
}

/** "08:00" or "8:00 AM", as the phone tells time. */
@Composable
internal fun clockTime(hour: Int, minute: Int): String {
    val context = LocalContext.current
    val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(pattern, currentLocale()))
}

/** The days of the week, starting where the phone's week starts. */
internal fun weekInOrder(locale: Locale): List<DayOfWeek> {
    val first = WeekFields.of(locale).firstDayOfWeek
    return List(DayOfWeek.entries.size) { first.plus(it.toLong()) }
}

internal fun dayName(day: DayOfWeek, locale: Locale): String = day.getDisplayName(TextStyle.SHORT, locale)

/** The phone's locale, read so a change of it redraws. */
@Composable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** "Mon, Wed and Fri", in the phone's week order. */
@Composable
private fun dayList(days: Set<DayOfWeek>): String {
    val locale = currentLocale()
    val names = weekInOrder(locale).filter { it in days }.map { dayName(it, locale) }
    return if (names.size < 2) {
        names.joinToString()
    } else {
        stringResource(R.string.feature_cron_words_and, names.dropLast(1).joinToString(", "), names.last())
    }
}

/** A date and time in the phone's own format, long enough to tell days apart. */
@Composable
internal fun longDateTime(epochMs: Long): String = DateUtils.formatDateTime(
    LocalContext.current,
    epochMs,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or
        DateUtils.FORMAT_ABBREV_ALL,
)

private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
private const val MINUTE_MS = 60_000L
private const val HOUR_MINUTES = 60
private const val DAY_MINUTES = 24 * 60
