package lab.dawnforge.hermes

import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchReplayTest {
    @Test
    fun `a start Android replays from Recents is not acted on again`() {
        // On the Pixel (2026-10-24) and the emulator (2026-10-26): the app started by a sign-in
        // link, closed with Back, reopened from Recents showed "Sign in with this link?" again.
        assertTrue(LaunchReplay.isReplay(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        assertTrue(
            LaunchReplay.isReplay(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY),
        )
    }

    @Test
    fun `a fresh link, notification or share is acted on`() {
        assertFalse(LaunchReplay.isReplay(0))
        assertFalse(LaunchReplay.isReplay(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertFalse(LaunchReplay.isReplay(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }
}
