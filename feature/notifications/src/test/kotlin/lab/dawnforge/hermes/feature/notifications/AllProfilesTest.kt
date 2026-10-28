package lab.dawnforge.hermes.feature.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import lab.dawnforge.hermes.core.data.plugin.ReplySources
import lab.dawnforge.hermes.core.push.PushStore.NotifyPreferences

class AllProfilesTest {
    private fun row(
        name: String,
        status: ProfileNotifications,
        preferences: NotifyPreferences = NotifyPreferences(),
        sources: ReplySources? = null,
    ) = ProfileRow(name, name, status, preferences, sources)

    private val on = ProfileNotifications.On
    private val off = ProfileNotifications.Off
    private val elsewhere = ProfileNotifications.OnElsewhere
    private val checking = ProfileNotifications.Checking

    @Test
    fun `the switch is on when every profile that can notify this phone does`() {
        assertTrue(AllProfiles.allOn(listOf(row("default", on), row("nova", on))))
        assertFalse(AllProfiles.allOn(listOf(row("default", on), row("nova", off))))
        // A profile notifying another phone is left to its own button, and doesn't hold the switch off.
        assertTrue(AllProfiles.allOn(listOf(row("default", on), row("echo", elsewhere))))
        assertFalse(AllProfiles.allOn(listOf(row("echo", elsewhere))))
        assertFalse(AllProfiles.allOn(emptyList()))
    }

    @Test
    fun `while profiles are still being checked the switch can't be used`() {
        assertTrue(AllProfiles.checking(listOf(row("default", on), row("nova", checking))))
        assertFalse(AllProfiles.checking(listOf(row("default", on), row("nova", off))))
    }

    @Test
    fun `turning everything on turns on the profiles that are off, never taking one from another phone`() {
        val rows = listOf(row("default", on), row("nova", off), row("echo", elsewhere), row("writer", off))

        assertEquals(listOf("nova", "writer"), AllProfiles.toTurnOn(rows))
    }

    @Test
    fun `turning everything off turns off the profiles on this phone`() {
        val rows = listOf(row("default", on), row("nova", off), row("echo", elsewhere), row("writer", on))

        assertEquals(listOf("default", "writer"), AllProfiles.toTurnOff(rows))
    }

    @Test
    fun `the shared choices are the first profile's on this phone, else the defaults`() {
        val quiet = NotifyPreferences(muted = setOf("background_task.finished"), hideText = true)
        val rows = listOf(row("nova", off), row("default", on, quiet), row("writer", on))

        assertEquals(quiet, AllProfiles.preferences(rows))
        assertEquals(NotifyPreferences(), AllProfiles.preferences(listOf(row("nova", off))))
    }

    @Test
    fun `the shared reply sources come from the first profile on this phone whose host reports them`() {
        val sources = ReplySources(app = true, desktop = false, messaging = true, terminal = false)
        val rows = listOf(row("default", on), row("nova", on, sources = sources), row("echo", off, sources = sources))

        assertEquals(sources, AllProfiles.sources(rows))
        assertNull(AllProfiles.sources(listOf(row("default", on))))
    }

    @Test
    fun `a profile whose choices differ from the shared ones is brought in line`() {
        val shared = NotifyPreferences(muted = setOf("turn.failed"))
        val rows = listOf(row("default", on, shared), row("nova", on), row("writer", off))

        assertEquals(listOf("nova"), AllProfiles.outOfLine(rows, shared))
    }
}
