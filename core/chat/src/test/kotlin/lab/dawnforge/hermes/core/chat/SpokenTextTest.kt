package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenTextTest {
    @Test
    fun `narrated actions and emoji go, emphasis and words stay`() {
        assertEquals(
            "Order! Court is in session.",
            SpokenText.filter("Order! *sets down the gavel* Court is in session."),
        )
        assertEquals("That is *really* good.", SpokenText.filter("That is *really* good. 🎉").trim())
        assertEquals("Use my_file_name and **bold** text.", SpokenText.filter("Use my_file_name and **bold** text."))
        assertEquals("Hi there", SpokenText.filter("Hi _waves at you warmly_ there"))
        assertEquals("Thumbs up", SpokenText.filter("Thumbs 👍🏽 up"))
    }

    @Test
    fun `a marker that never closes is spoken as written`() {
        assertEquals("2 * 3 is six", SpokenText.filter("2 * 3 is six"))
        assertEquals("*not closed on this line\nnext", SpokenText.filter("*not closed on this line\nnext"))
    }

    @Test
    fun `an action split across deltas is held until it closes`() {
        val spoken = SpokenText()
        val parts = listOf("Well ", "*leans", " back in", " the chair* then", " we go")
        val heard = parts.joinToString("") { spoken.feed(it) } + spoken.finish()

        // Spaces left where the action was are harmless to speech.
        assertEquals("Well then we go", heard.replace(Regex(" +"), " "))
        // Nothing of the action is released while it is still open.
        val early = SpokenText()
        assertEquals("Well ", early.feed("Well *leans back"))
    }
}
