package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class PlainTextTest {
    @Test
    fun `marks go and words stay`() {
        assertEquals(
            "Send Ana: See you at 7, bring the notes.",
            PlainText.of("Send **Ana**: _See you_ at `7`, bring [the notes](https://example.com/n \"Notes\")."),
        )
        assertEquals("Buy milk ~ eggs", PlainText.of("~~Buy~~ milk ~ *eggs*"))
    }

    @Test
    fun `snake case, lone stars and line breaks are left alone`() {
        assertEquals("rename my_file_name to 2 * 3\nnext line", PlainText.of("rename my_file_name to 2 * 3\nnext line"))
    }
}
