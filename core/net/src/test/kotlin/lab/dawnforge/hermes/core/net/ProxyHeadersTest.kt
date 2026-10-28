package lab.dawnforge.hermes.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import lab.dawnforge.hermes.core.model.DashboardUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Extra headers for a reverse proxy, with iOS's rules (`CustomHeaders.swift`). */
class ProxyHeadersTest {
    private fun header(name: String, value: String = "v") = ProxyHeader(name, value)

    @Test
    fun `names must be tokens the app doesn't manage, and values one line`() {
        fun problem(row: ProxyHeader, all: List<ProxyHeader> = listOf(row)) = ProxyHeaderRules.problem(row, all)
        assertNull(problem(header("X-Auth-Key")))
        assertEquals(ProxyHeaderProblem.EmptyName, problem(header("  ")))
        assertEquals(ProxyHeaderProblem.InvalidName, problem(header("X Auth")))
        assertEquals(ProxyHeaderProblem.Reserved, problem(header("Cookie")))
        assertEquals(ProxyHeaderProblem.Reserved, problem(header("sec-fetch-mode")))
        assertEquals(ProxyHeaderProblem.InvalidValue, problem(header("X-A", "a\nb")))
        assertNull(problem(header("X-A", "a\tb")))
        val twice = listOf(header("X-A"), header("x-a"))
        assertEquals(ProxyHeaderProblem.Duplicate, problem(twice[0], twice))
    }

    @Test
    fun `only valid rows go out, the first of a name winning, and blank rows are dropped`() {
        val rows = listOf(header("X-A", "1"), header("Cookie"), header("x-a", "2"), header(" ", " "))
        assertEquals(listOf(header("X-A", "1")), ProxyHeaderRules.sendable(rows))
        assertEquals(3, ProxyHeaderRules.tidy(rows).size)
    }

    @Test
    fun `headers are bound to an https origin only`() {
        assertEquals(
            "https://box.example",
            ProxyHeaderRules.origin(DashboardUrl("https", "Box.Example", 443, "/hermes")),
        )
        assertEquals(
            "https://box.example:8443",
            ProxyHeaderRules.origin(DashboardUrl("https", "box.example", 8443, "")),
        )
        assertEquals("https://[::1]", ProxyHeaderRules.origin(DashboardUrl("https", "::1", null, "")))
        assertNull(ProxyHeaderRules.origin(DashboardUrl("http", "192.168.1.5", 9119, "")))
    }

    @Test
    fun `requests to the dashboard over https carry them, never over a header already set`() {
        val dashboard = DashboardUrl("https", "box.example", null, "")
        val headers = ProxyHeaders { origin ->
            if (origin ==
                "https://box.example"
            ) {
                listOf(header("X-Proxy", "secret"), header("Accept", "nope"))
            } else {
                emptyList()
            }
        }
        val sent = mutableListOf<Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor(DashboardHeadersInterceptor(dashboard, { null }, { null }, headers))
            .addInterceptor { chain ->
                sent += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{}".toResponseBody()).build()
            }
            .build()
        client.newCall(Request.Builder().url("https://box.example/api/status").build()).execute().close()
        client.newCall(Request.Builder().url("https://elsewhere.example/x").build()).execute().close()
        assertEquals("secret", sent[0].header("X-Proxy"))
        assertEquals("application/json", sent[0].header("Accept"))
        assertNull(sent[1].header("X-Proxy"))
    }
}
