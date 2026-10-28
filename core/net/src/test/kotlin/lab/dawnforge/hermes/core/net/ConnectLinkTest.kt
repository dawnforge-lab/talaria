package lab.dawnforge.hermes.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ConnectLinkTest {
    @Test
    fun `full link is decoded`() {
        val link = ConnectLink.parse(
            "talaria://connect?url=https%3A%2F%2Fbox.tail1234.ts.net&username=%20me%20&password=a%2Bb+c%C3%A9",
        )
        assertEquals(ConnectLink("https://box.tail1234.ts.net", "me", "a+b+cé"), link)
    }

    @Test
    fun `only the address is required`() {
        assertEquals(
            ConnectLink("192.168.1.5:9119", null, null),
            ConnectLink.parse("talaria://connect?url=192.168.1.5:9119"),
        )
        assertEquals(
            ConnectLink("https://h.example", null, null),
            ConnectLink.parse("TALARIA://Connect/?url=https://h.example&username=&password="),
        )
    }

    @Test
    fun `unreadable links return null`() {
        listOf(
            "talaria://connect",
            "talaria://connect?username=me",
            "talaria://connect?url=%20",
            "talaria://connect?url=a&url=b",
            "talaria://other?url=a",
            "talaria://connect/extra?url=a",
            "talaria://connect?url=a#frag",
            "talaria://connect?url=%E2%28",
            "talaria://connect?url=%4",
            "https://connect?url=a",
            "not a link",
        ).forEach { assertNull(ConnectLink.parse(it), it) }
    }

    @Test
    fun `password is never printed`() {
        val text = ConnectLink("https://h", "me", "hunter2").toString()
        assertFalse(text.contains("hunter2"))
    }

    @Test
    fun `scanned text is classified`() {
        assertEquals(
            ScannedCode.Link(ConnectLink("https://h.example", "me", null)),
            ScannedCode.classify("talaria://connect?url=https://h.example&username=me"),
        )
        assertEquals(ScannedCode.Address("https://h.example"), ScannedCode.classify(" https://h.example "))
        assertEquals(ScannedCode.Unreadable, ScannedCode.classify("talaria://connect"))
        assertEquals(ScannedCode.Unreadable, ScannedCode.classify("two words"))
        assertEquals(ScannedCode.Unreadable, ScannedCode.classify("http://example.com"))
    }
}
