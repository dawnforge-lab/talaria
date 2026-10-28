package lab.dawnforge.hermes.feature.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import lab.dawnforge.hermes.feature.notifications.BackgroundLimits.Advice

class BackgroundLimitsTest {
    @Test
    fun `any phone where the app is limited gets the battery advice`() {
        assertEquals(Advice.Battery, BackgroundLimits.advice(unrestricted = false, "Google", "google"))
        assertEquals(Advice.Battery, BackgroundLimits.advice(unrestricted = false, "samsung", "samsung"))
        assertEquals(Advice.Battery, BackgroundLimits.advice(unrestricted = false, null, null))
    }

    @Test
    fun `Xiaomi's phones, under any of their brands, also get Autostart`() {
        // A Redmi reports its maker as Xiaomi.
        assertEquals(Advice.BatteryAndAutostart, BackgroundLimits.advice(false, "Xiaomi", "Redmi"))
        assertEquals(Advice.BatteryAndAutostart, BackgroundLimits.advice(false, "Xiaomi", "POCO"))
        assertEquals(Advice.BatteryAndAutostart, BackgroundLimits.advice(false, " xiaomi ", null))
        assertEquals(Advice.BatteryAndAutostart, BackgroundLimits.advice(false, null, "Redmi"))
    }

    @Test
    fun `an app already allowed unrestricted battery use gets none`() {
        assertNull(BackgroundLimits.advice(unrestricted = true, "Google", "google"))
        assertNull(BackgroundLimits.advice(unrestricted = true, "Xiaomi", "Redmi"))
    }
}
