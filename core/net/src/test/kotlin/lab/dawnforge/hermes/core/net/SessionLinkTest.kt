package lab.dawnforge.hermes.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Chat links, in the iOS app's format. */
class SessionLinkTest {
    @Test
    fun `a chat link names its chat, and optionally its profile and dashboard`() {
        assertEquals(
            SessionLink("20261021_122451_03bf1f", "work", "564447f7-cf21-4580-bf1b-a03427814d9f"),
            SessionLink.parse(
                "talaria://session/20261021_122451_03bf1f?profile=work&dashboard=564447f7-cf21-4580-bf1b-a03427814d9f",
            ),
        )
        assertEquals(SessionLink("abc", null, null), SessionLink.parse("TALARIA://session/abc"))
        assertEquals(SessionLink("a:b.c", null, null), SessionLink.parse("talaria://session/a%3Ab.c/"))
    }

    @Test
    fun `anything else is not a chat link`() {
        listOf(
            "talaria://session/",
            "talaria://session/a b",
            "talaria://session/abc?profile=x&profile=y",
            "talaria://session/abc?other=1",
            "talaria://session/abc?profile=bad/name",
            "talaria://session/abc#frag",
            "talaria://session/" + "a".repeat(129),
            "talaria://connect?url=x",
            "https://session/abc",
        ).forEach { assertNull(SessionLink.parse(it), it) }
    }
}
