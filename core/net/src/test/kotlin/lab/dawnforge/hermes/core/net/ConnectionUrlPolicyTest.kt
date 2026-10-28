package lab.dawnforge.hermes.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import lab.dawnforge.hermes.core.model.DashboardUrl

class ConnectionUrlPolicyTest {
    private fun valid(input: String): List<String> {
        val result = ConnectionUrlPolicy.check(input)
        check(result is AddressCheck.Valid) { "Expected $input to be valid, got $result" }
        return result.candidates.map(DashboardUrl::toString)
    }

    private fun problem(input: String): AddressProblem {
        val result = ConnectionUrlPolicy.check(input)
        check(result is AddressCheck.Invalid) { "Expected $input to be invalid, got $result" }
        return result.problem
    }

    @Test
    fun `https address is normalized`() {
        assertEquals(listOf("https://hermes.example.com"), valid("  https://Hermes.Example.com/  "))
        assertEquals(listOf("https://hermes.example.com"), valid("https://hermes.example.com:443"))
        assertEquals(listOf("https://hermes.example.com:8443"), valid("https://hermes.example.com:8443/"))
    }

    @Test
    fun `base path is kept without trailing slashes`() {
        val url = (ConnectionUrlPolicy.check("https://example.com/hermes//") as AddressCheck.Valid).candidates.single()
        assertEquals("/hermes", url.basePath)
        assertEquals("https://example.com/hermes/api/status", url.resolve("/api/status"))
    }

    @Test
    fun `address without a scheme tries https first for host names`() {
        assertEquals(listOf("https://hermes.example.com"), valid("hermes.example.com"))
        assertEquals(
            listOf("https://box.tail1234.ts.net", "http://box.tail1234.ts.net"),
            valid("box.tail1234.ts.net"),
        )
    }

    @Test
    fun `address without a scheme tries http first for IPs and localhost`() {
        assertEquals(listOf("http://192.168.1.5:9119", "https://192.168.1.5:9119"), valid("192.168.1.5:9119"))
        assertEquals(listOf("http://localhost:9119", "https://localhost:9119"), valid("localhost:9119"))
        assertEquals(listOf("http://10.0.2.2:9119", "https://10.0.2.2:9119"), valid("10.0.2.2:9119"))
    }

    @Test
    fun `plain http to a public host is refused`() {
        assertEquals(AddressProblem.InsecureTransport, problem("http://hermes.example.com"))
        assertEquals(AddressProblem.InsecureTransport, problem("http://8.8.8.8:9119"))
    }

    @Test
    fun `malformed and unsupported addresses are refused`() {
        assertEquals(AddressProblem.Empty, problem("   "))
        assertEquals(AddressProblem.UnsupportedScheme, problem("ftp://example.com"))
        assertEquals(AddressProblem.HasCredentials, problem("https://me:secret@example.com"))
        assertEquals(AddressProblem.HasQueryOrFragment, problem("https://example.com/?a=1"))
        assertEquals(AddressProblem.HasQueryOrFragment, problem("https://example.com/#top"))
        assertEquals(AddressProblem.Malformed, problem("https://"))
    }

    @Test
    fun `cleartext is allowed only on loopback, private and tailnet addresses`() {
        listOf(
            "localhost", "127.0.0.1", "::1", "10.0.2.2", "172.16.0.1", "172.31.255.255",
            "192.168.0.10", "100.64.0.1", "100.127.255.254", "machine.tail1234.ts.net",
        ).forEach { assertTrue(ConnectionUrlPolicy.allowsCleartext(it), it) }

        listOf(
            "example.com", "8.8.8.8", "172.15.0.1", "172.32.0.1", "100.63.0.1", "100.128.0.1",
            "010.0.0.1", "192.168.1", "192.168.1.256", "ts.net",
        ).forEach { assertFalse(ConnectionUrlPolicy.allowsCleartext(it), it) }
    }

    @Test
    fun `loopback-only hosts are recognised`() {
        listOf("localhost", "app.localhost", "127.0.0.1", "127.8.9.1", "::1", "0.0.0.0")
            .forEach { assertTrue(ConnectionUrlPolicy.isLoopbackOnly(it), it) }
        listOf("10.0.2.2", "192.168.1.5", "example.com")
            .forEach { assertFalse(ConnectionUrlPolicy.isLoopbackOnly(it), it) }
    }
}
