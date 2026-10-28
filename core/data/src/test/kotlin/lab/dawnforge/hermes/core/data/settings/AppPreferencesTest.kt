package lab.dawnforge.hermes.core.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppPreferencesTest {
    @Test
    fun `text sizes scale as on iOS, and the limit is in megabytes`() {
        assertEquals(1f, AppPreferences().textScale)
        assertEquals(0.85f, AppPreferences(textSize = 0).textScale)
        assertEquals(1.25f, AppPreferences(textSize = 4).textScale)
        // Out of range reads as the nearest size.
        assertEquals(1.25f, AppPreferences(textSize = 9).textScale)
        assertEquals(16L * 1024 * 1024, AppPreferences().attachmentLimitBytes)
    }

    @Test
    fun `a hidden provider hides its models, a hidden model only itself`() {
        val visibility = ModelVisibility(
            hiddenProviders = setOf("anthropic"),
            hiddenModels = setOf(ModelVisibility.modelKey("ollama", "big:70b")),
        )
        assertFalse(visibility.shows("anthropic"))
        assertFalse(visibility.shows("anthropic", "claude-x"))
        assertFalse(visibility.shows("ollama", "big:70b"))
        assertTrue(visibility.shows("ollama", "small:7b"))
        assertTrue(visibility.shows("openrouter", "big:70b"))
    }
}
