package lab.dawnforge.hermes.core.data.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlowColorsTest {
    private val red = 0xE53935
    private val orange = 0xF4511E
    private val blue = 0x1E88E5
    private val green = 0x43A047

    private fun photo(vararg colors: Pair<Int, Int>): IntArray =
        colors.flatMap { (rgb, count) -> List(count) { (0xFF shl 24) or rgb } }.toIntArray()

    @Test
    fun `a photo's main colours come most prominent first, lifted to a glow`() {
        val palette = GlowColors.palette(photo(blue to 600, red to 300, 0x101010 to 100))
        assertEquals(2, palette.size)
        assertTrue(GlowColors.looksLike(palette[0], blue))
        assertTrue(GlowColors.looksLike(palette[1], red))
        assertTrue(GlowColors.hsb(palette[0]).brightness >= 0.75)
    }

    @Test
    fun `grey, faint and scattered pixels give no colour`() {
        assertEquals(emptyList(), GlowColors.palette(photo(0x808080 to 1000)))
        assertEquals(emptyList(), GlowColors.palette(IntArray(100) { red }))
        assertEquals(1, GlowColors.palette(photo(blue to 1000, red to 10)).size)
    }

    @Test
    fun `a later profile whose main colour is taken glows in its second`() {
        val glows = GlowColors.assign(
            order = listOf("default", "work"),
            palettes = mapOf("default" to listOf(red), "work" to listOf(orange, green)),
            picked = emptyMap(),
        )
        assertEquals(red, glows["default"])
        assertEquals(green, glows["work"])
    }

    @Test
    fun `a pick is claimed first, and a pick for a profile gone is dropped`() {
        val glows = GlowColors.assign(
            order = listOf("default", "work"),
            palettes = mapOf("default" to listOf(blue, red)),
            picked = mapOf("work" to blue, "gone" to green),
        )
        assertEquals(mapOf("work" to blue, "default" to red), glows)
    }

    @Test
    fun `colours round-trip through hue, saturation and brightness`() {
        listOf(red, blue, green, 0x000000, 0xFFFFFF).forEach { rgb ->
            val hsb = GlowColors.hsb(rgb)
            assertEquals(rgb, GlowColors.fromHsb(hsb.hue, hsb.saturation, hsb.brightness), GlowColors.hex(rgb))
        }
    }
}
