package lab.dawnforge.hermes.core.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ConnectorsJsonTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `servers read their transport, sign-in and owner`() {
        val servers = ConnectorsJson.servers(
            json(
                """{"servers": [
                   {"name": "notion", "transport": "http", "url": "https://mcp.notion.com/mcp", "auth": "oauth"},
                   {"name": "fs", "transport": "stdio", "command": "npx", "args": ["-y", "fs"], "enabled": false},
                   {"name": "keyed", "transport": "http", "url": "https://x", "auth": "header"},
                   {"name": "bundled", "transport": "http", "url": "https://y", "source": "plugin", "plugin": "kit"}]}""",
            ),
        )
        val (notion, fs, keyed, bundled) = servers
        assertTrue(notion.canSignIn && notion.enabled)
        assertEquals("npx -y fs", fs.runs)
        assertFalse(fs.enabled || fs.canSignIn)
        assertFalse(keyed.canSignIn)
        assertEquals("kit", bundled.plugin)
        assertFalse(bundled.canSignIn)
    }

    @Test
    fun `catalog entries keep their keys, and probes tell a missing sign-in`() {
        val entry = ConnectorsJson.catalog(
            json(
                """{"entries": [{"name": "brave", "transport": "stdio", "auth_type": "api_key", "command": "npx",
                   "args": ["brave"], "required_env": [{"name": "BRAVE_KEY", "prompt": "Brave key"},
                   {"name": "REGION", "required": false}]}]}""",
            ),
        ).single()
        assertEquals(
            listOf(CatalogKey("BRAVE_KEY", "Brave key", true), CatalogKey("REGION", "REGION", false)),
            entry.keys,
        )
        val probe = ConnectorsJson.probe(
            json("""{"ok": false, "error": "OAuth authentication required — no token found."}"""),
        )
        assertTrue(probe.needsSignIn)
        assertFalse(ConnectorsJson.probe(json("""{"ok": false, "error": "Connection refused"}""")).needsSignIn)
        assertEquals(
            listOf("search" to "Searches"),
            ConnectorsJson.probe(
                json("""{"ok": true, "tools": [{"name": "search", "description": "Searches"}]}"""),
            ).tools,
        )
        assertNull(ConnectorsJson.signIn(json("""{"error": "no flow"}""")))
        assertEquals(
            McpSignIn("f1", "https://auth", "authorization_required", null),
            ConnectorsJson.signIn(
                json("""{"flow_id": "f1", "authorization_url": "https://auth", "status": "authorization_required"}"""),
            ),
        )
    }

    @Test
    fun `plugins hide Hermes' own parts and are named by their folder`() {
        val plugins = ConnectorsJson.plugins(
            json(
                """{"plugins": [
                   {"name": "browser-x", "source": "bundled", "runtime_status": "enabled"},
                   {"name": "disk-cleanup", "source": "bundled", "runtime_status": "disabled"},
                   {"name": "platforms/telegram", "source": "user"},
                   {"name": "Kit", "source": "git", "runtime_status": "enabled", "can_remove": true,
                    "can_update_git": true, "path": "C:\\hermes\\plugins\\kit-plugin", "auth_required": true,
                    "auth_command": "hermes kit login"}]}""",
            ),
        )
        assertEquals(listOf("disk-cleanup", "Kit"), plugins.map(AgentPlugin::name))
        val kit = plugins[1]
        assertEquals("kit-plugin", kit.folder)
        assertEquals("hermes kit login", kit.signInCommand)
        assertTrue(kit.enabled && kit.canUpdate)
    }
}
