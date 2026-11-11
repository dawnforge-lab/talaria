package lab.dawnforge.hermes.feature.chat

import kotlin.test.Test
import kotlin.test.assertContentEquals

class ChatTableTest {
    @Test
    fun `a table that fits keeps its columns at their content's width, capped`() {
        val widths = columnWidths(
            natural = intArrayOf(80, 900),
            narrowest = intArrayOf(40, 90),
            available = 1000,
            maxColumn = 240,
        )
        assertContentEquals(intArrayOf(80, 240), widths)
    }

    @Test
    fun `a table too wide narrows the columns with most room to wrap, to fill the width exactly`() {
        val widths = columnWidths(
            natural = intArrayOf(100, 300, 300),
            narrowest = intArrayOf(100, 60, 120),
            available = 500,
            maxColumn = 400,
        )
        // The middle column had 240 to give and the last 180: 200 comes off in that proportion.
        assertContentEquals(intArrayOf(100, 185, 215), widths)
    }

    @Test
    fun `past the longest words the table keeps them and scrolls`() {
        val widths = columnWidths(
            natural = intArrayOf(400, 400),
            narrowest = intArrayOf(150, 200),
            available = 300,
            maxColumn = 500,
        )
        assertContentEquals(intArrayOf(150, 200), widths)
    }
}
