package lab.dawnforge.hermes.core.data.phonedata.alarms

import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** When an alarm rings next. 2026-10-23 is a Friday. */
class AlarmBookTest {
    private val zone = ZoneOffset.UTC

    private fun ms(text: String) = ZonedDateTime.parse(text).toInstant().toEpochMilli()

    private fun clock(hour: Int, minute: Int, vararg days: String) =
        PhoneAlarm(id = "a", kind = "alarm", hour = hour, minute = minute, repeats = days.toList())

    @Test
    fun `a one-off rings at its time, and not once it has passed`() {
        val now = ms("2026-10-23T08:00:00Z")
        val alarm = PhoneAlarm(id = "a", kind = "timer", at = now + 60_000)
        assertEquals(now + 60_000, AlarmBook.nextRing(alarm, now, zone))
        assertNull(AlarmBook.nextRing(alarm, now + 60_000, zone))
    }

    @Test
    fun `a repeating alarm rings today when its time is still ahead`() {
        val now = ms("2026-10-23T06:00:00Z")
        assertEquals(ms("2026-10-23T07:30:00Z"), AlarmBook.nextRing(clock(7, 30, "fri"), now, zone))
    }

    @Test
    fun `a repeating alarm whose time passed today rings on its next day`() {
        val now = ms("2026-10-23T08:00:00Z")
        assertEquals(ms("2026-10-26T07:30:00Z"), AlarmBook.nextRing(clock(7, 30, "mon", "wed"), now, zone))
        // Only Fridays: a week on.
        assertEquals(ms("2026-10-30T07:30:00Z"), AlarmBook.nextRing(clock(7, 30, "fri"), now, zone))
    }

    @Test
    fun `an alarm ringing exactly now is due next time`() {
        val now = ms("2026-10-23T07:30:00Z")
        assertEquals(ms("2026-10-24T07:30:00Z"), AlarmBook.nextRing(clock(7, 30, "fri", "sat"), now, zone))
    }

    @Test
    fun `a clock alarm without days or time has no next ring`() {
        val now = ms("2026-10-23T08:00:00Z")
        assertNull(AlarmBook.nextRing(clock(7, 30), now, zone))
        assertNull(AlarmBook.nextRing(clock(7, 30, "someday"), now, zone))
        assertNull(AlarmBook.nextRing(PhoneAlarm(id = "a", kind = "alarm", repeats = listOf("mon")), now, zone))
    }
}
