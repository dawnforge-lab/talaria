package lab.dawnforge.hermes

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/** The app's network security rules, read as the file Android reads. */
class NetworkSecurityConfigTest {
    private val config: Element = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(File("src/main/res/xml/network_security_config.xml"))
        .documentElement

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map(childNodes::item).filterIsInstance<Element>().filter { it.tagName == tag }

    @Test
    fun `the system's certificate authorities and those the user installs are trusted, as on iOS`() {
        val anchors = config.children("base-config").single().children("trust-anchors").single()
        assertEquals(
            setOf("system", "user"),
            anchors.children("certificates").map { it.getAttribute("src") }.toSet(),
        )
    }

    @Test
    fun `no certificate check is bypassed for any host`() {
        // No per-host trust of its own, and no debug-only anchors that would ship by mistake.
        assertTrue(config.children("domain-config").isEmpty())
        assertTrue(config.children("debug-overrides").isEmpty())
        assertTrue(config.getElementsByTagName("pin-set").length == 0)
    }
}
