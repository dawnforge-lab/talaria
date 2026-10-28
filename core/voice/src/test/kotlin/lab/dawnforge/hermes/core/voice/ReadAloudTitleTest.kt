package lab.dawnforge.hermes.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the lock screen names a reply read aloud, as iOS's now playing does. */
class ReadAloudTitleTest {
    @Test
    fun `the first line, cut to 80 characters`() {
        assertEquals("First line", ReadAloud.title("\n  First line  \nSecond"))
        val long = "a".repeat(100)
        assertEquals("a".repeat(79) + "…", ReadAloud.title(long))
        assertEquals("", ReadAloud.title("  \n "))
    }
}
