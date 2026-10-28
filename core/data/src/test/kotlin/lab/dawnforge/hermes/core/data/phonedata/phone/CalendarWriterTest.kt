package lab.dawnforge.hermes.core.data.phonedata.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The times and lengths Hermes sends for calendar changes, as Android's calendar keeps them. */
class CalendarWriterTest {
    @Test
    fun `a timed event's time keeps its offset`() {
        assertEquals(1_792_738_800_000, CalendarWriter.time("2026-10-23T09:00:00+02:00", allDay = false))
        assertEquals(1_792_738_800_000, CalendarWriter.time("2026-10-23T07:00:00Z", allDay = false))
    }

    @Test
    fun `an all-day event starts at UTC midnight, even when sent with a time`() {
        assertEquals(1_792_713_600_000, CalendarWriter.time("2026-10-23", allDay = true))
        assertEquals(1_792_713_600_000, CalendarWriter.time("2026-10-23T15:00:00+02:00", allDay = true))
        // A bare day reads as all-day.
        assertEquals(1_792_713_600_000, CalendarWriter.time("2026-10-23", allDay = false))
    }

    @Test
    fun `a time the phone can't read is refused`() {
        assertNull(CalendarWriter.time(null, allDay = false))
        assertNull(CalendarWriter.time("tomorrow at nine", allDay = false))
        assertNull(CalendarWriter.time("2026-10-23T09:00:00", allDay = false))
        assertNull(CalendarWriter.time("2026-13-40", allDay = true))
    }

    @Test
    fun `a series' length is whole days when all-day, seconds otherwise`() {
        assertEquals("P2D", CalendarWriter.duration(2 * 86_400_000L, allDay = true))
        assertEquals("P1D", CalendarWriter.duration(0, allDay = true))
        assertEquals("P5400S", CalendarWriter.duration(5_400_000, allDay = false))
        assertEquals("P1S", CalendarWriter.duration(0, allDay = false))
    }
}
