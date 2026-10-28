package lab.dawnforge.hermes.core.data.cron

import java.time.DayOfWeek
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import lab.dawnforge.hermes.core.model.CronJob
import lab.dawnforge.hermes.core.model.CronState

class CronScheduleTest {
    private fun job(kind: String?, input: String, display: String = input) = CronJob(
        id = "j1",
        name = "Job",
        prompt = "p",
        schedule = display,
        scheduleInput = input,
        scheduleKind = kind,
        state = CronState.Scheduled,
        nextRunAtMs = null,
        lastRunAtMs = null,
        lastStatus = null,
        lastError = null,
        deliver = "local",
        model = null,
        isScript = false,
    )

    @Test
    fun `a time on chosen days becomes a cron expression`() {
        assertEquals("0 8 * * *", ScheduleChoice.AtTime().toHermes())
        assertEquals("30 9 * * 1-5", ScheduleChoice.AtTime(9, 30, ScheduleChoice.WEEKDAYS).toHermes())
        assertEquals(
            "0 10 * * 0,6",
            ScheduleChoice.AtTime(10, 0, setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)).toHermes(),
        )
        assertNull(ScheduleChoice.AtTime(days = emptySet()).toHermes())
    }

    @Test
    fun `intervals, one-offs and custom text are written as Hermes reads them`() {
        assertEquals("every 2h", ScheduleChoice.Every(2, IntervalUnit.Hours).toHermes())
        assertEquals("every 15m", ScheduleChoice.Every(15, IntervalUnit.Minutes).toHermes())
        assertNull(ScheduleChoice.Every(0).toHermes())
        assertNull(ScheduleChoice.Every(1000).toHermes())
        val at = OffsetDateTime.of(2026, 10, 22, 9, 0, 41, 0, ZoneOffset.ofHours(1))
        assertEquals("2026-10-22T09:00:00+01:00", ScheduleChoice.Once(at).toHermes())
        assertEquals(
            "2026-10-22T09:00:00+00:00",
            ScheduleChoice.Once(at.withOffsetSameLocal(ZoneOffset.UTC)).toHermes(),
        )
        assertEquals("weekdays at 9am", ScheduleChoice.Custom("  weekdays at 9am ").toHermes())
        assertNull(ScheduleChoice.Custom(" ").toHermes())
    }

    @Test
    fun `a job opens in the editor as it was made, and anything else as Hermes wrote it`() {
        assertEquals(ScheduleChoice.Every(2, IntervalUnit.Hours), ScheduleChoice.of(job("interval", "every 120m")))
        assertEquals(ScheduleChoice.Every(90, IntervalUnit.Minutes), ScheduleChoice.of(job("interval", "every 90m")))
        assertEquals(ScheduleChoice.Every(3, IntervalUnit.Days), ScheduleChoice.of(job("interval", "every 4320m")))
        assertEquals(
            ScheduleChoice.AtTime(9, 0, ScheduleChoice.WEEKDAYS),
            ScheduleChoice.of(job("cron", "0 9 * * 1-5")),
        )
        assertEquals(
            ScheduleChoice.AtTime(7, 15, setOf(DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY)),
            ScheduleChoice.of(job("cron", "15 7 * * 3,7")),
        )
        val once = assertIs<ScheduleChoice.Once>(ScheduleChoice.of(job("once", "2026-11-01T09:00:00+00:00")))
        assertEquals(1_793_523_600_000, once.at.toInstant().toEpochMilli())
        assertEquals(ScheduleChoice.Custom("*/15 * * * *"), ScheduleChoice.of(job("cron", "*/15 * * * *")))
        assertEquals(ScheduleChoice.Custom("0 9 1 * *"), ScheduleChoice.of(job("cron", "0 9 1 * *")))
        assertEquals(ScheduleChoice.Custom("0 9 * * mon"), ScheduleChoice.of(job("cron", "0 9 * * mon")))
        assertEquals(ScheduleChoice.Custom("tomorrow"), ScheduleChoice.of(job(null, "tomorrow")))
    }

    @Test
    fun `schedules read as words where they can`() {
        assertEquals(ScheduleWords.Every(2, IntervalUnit.Hours), ScheduleWords.of(job("interval", "every 120m")))
        assertEquals(ScheduleWords.HourlyAt(5), ScheduleWords.of(job("cron", "5 * * * *")))
        assertEquals(
            ScheduleWords.At(8, 0, DayOfWeek.entries.toSet()),
            ScheduleWords.of(job("cron", "0 8 * * *")),
        )
        assertEquals(ScheduleWords.MonthlyAt(1, 9, 0), ScheduleWords.of(job("cron", "0 9 1 * *")))
        assertEquals(
            ScheduleWords.AsHermesSays("every 15 minutes"),
            ScheduleWords.of(job("cron", "*/15 * * * *", display = "every 15 minutes")),
        )
        assertEquals(
            ScheduleWords.Once(1_793_523_600_000),
            ScheduleWords.of(job("once", "2026-11-01T09:00:00+00:00")),
        )
        assertNull(ScheduleWords.of(ScheduleChoice.AtTime(days = emptySet())))
    }
}
