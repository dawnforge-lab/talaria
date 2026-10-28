package lab.dawnforge.hermes.core.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class CapabilitiesJsonTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `skills and catalog entries are read with their origins`() {
        val skills = CapabilitiesJson.skills(
            json(
                """[{"name": "claude-code", "description": "Delegate coding", "category": "autonomous-ai-agents",
                   "enabled": true, "usage": 12, "provenance": "bundled"},
                   {"name": "notes", "enabled": false, "provenance": "agent"}, {"description": "no name"}]""",
            ),
        )
        assertEquals(
            listOf(
                Skill("claude-code", "Delegate coding", "autonomous-ai-agents", true, 12, SkillOrigin.Bundled),
                Skill("notes", "", "", false, 0, SkillOrigin.Agent),
            ),
            skills,
        )
        val catalog = CapabilitiesJson.catalog(
            json(
                """{"skills": [{"name": "arbiter", "identifier": "official/x/arbiter", "tags": ["A"], "installed": true}]}""",
            ),
        )
        assertEquals(listOf(CatalogSkill("arbiter", "official/x/arbiter", "", "general", listOf("A"), true)), catalog)
    }

    @Test
    fun `toolsets lose their emoji and the hidden ones`() {
        val sets = CapabilitiesJson.toolsets(
            json(
                """[{"name": "web", "label": "🔍 Web Search & Scraping", "enabled": true, "configured": false,
                   "tools": ["web_search"]}, {"name": "moa", "enabled": true}, {"name": "image_gen", "enabled": false}]""",
            ),
        )
        assertEquals(listOf("Web Search & Scraping", "Image Gen"), sets.map(Toolset::label))
        assertFalse(sets[0].configured)
        assertEquals(
            mapOf("terminal" to 3L),
            CapabilitiesJson.toolUsage(json("""{"tools": [{"tool": "terminal", "count": 3}]}""")),
        )
    }

    @Test
    fun `web backends serve search or extract, through Nous or not`() {
        val config = CapabilitiesJson.toolsetConfig(
            json(
                """{"has_category": true, "active_search_backend": "firecrawl", "active_extract_backend": "firecrawl",
                   "search_via_nous": false, "extract_via_nous": true, "providers": [
                   {"name": "Nous Subscription", "status": "needs_auth", "web_backend": "firecrawl",
                    "managed_nous_feature": "web", "capabilities": ["search", "extract"], "env_vars": []},
                   {"name": "Firecrawl", "status": "ready", "web_backend": "firecrawl", "is_active": true,
                    "env_vars": [{"key": "FIRECRAWL_API_KEY", "prompt": "Firecrawl key", "is_set": true}]}]}""",
            ),
        )
        val (nous, firecrawl) = config.backends
        assertTrue(config.hasBackends)
        assertEquals(BackendStatus.NeedsAuth, nous.status)
        assertEquals(listOf(BackendKey("FIRECRAWL_API_KEY", "Firecrawl key", null, true)), firecrawl.keys)
        assertTrue(config.serves(firecrawl, "search"))
        assertFalse(config.serves(firecrawl, "extract"))
        assertTrue(config.serves(nous, "extract"))
        assertFalse(CapabilitiesJson.toolsetConfig(json("""{"has_category": true, "providers": []}""")).hasBackends)
    }

    @Test
    fun `a skill file splits into its fields and its text`() {
        val (fields, body) = splitSkillFile(
            "---\nname: notes\ndescription: \"Keeps notes\"\nmetadata:\n  hermes:\n    tags: [a]\nversion: 1.0\n---\n\n# Notes\nUse it.",
        )
        assertEquals(mapOf("name" to "notes", "description" to "Keeps notes", "version" to "1.0"), fields)
        assertEquals("# Notes\nUse it.", body)
        assertEquals(emptyMap<String, String>() to "No front matter", splitSkillFile("No front matter"))
    }

    @Test
    fun `counts and categories read as on iOS`() {
        assertEquals("×7", usageLabel(7))
        assertEquals("×1.2k", usageLabel(1_234))
        assertEquals("×2k", usageLabel(2_000))
        assertEquals("×3.4M", usageLabel(3_400_000))
        assertEquals("Software development", prettyCategory("software_development"))
        assertEquals("General", prettyCategory(""))
    }
}
