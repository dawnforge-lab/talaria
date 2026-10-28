package lab.dawnforge.hermes.core.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class HermesConfigTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `values are read by dotted path, by kind`() {
        val config = json(
            """{"display": {"show_reasoning": false, "personality": "noir"}, "approvals": {"timeout": 300},
               "compression": {"threshold": 0.8}, "command_allowlist": ["rm -rf build", 3], "flag": 1, "off": null}""",
        )
        assertEquals(ConfigValue.Flag(false), config.valueAt("display.show_reasoning"))
        assertEquals(ConfigValue.Text("noir"), config.valueAt("display.personality"))
        assertEquals(ConfigValue.Number(300.0), config.valueAt("approvals.timeout"))
        assertEquals(ConfigValue.Number(0.8), config.valueAt("compression.threshold"))
        assertEquals(ConfigValue.Items(listOf("rm -rf build", "3")), config.valueAt("command_allowlist"))
        // A 1 is a number, not a flag.
        assertEquals(ConfigValue.Number(1.0), config.valueAt("flag"))
        assertNull(config.valueAt("off"))
        assertNull(config.valueAt("display.missing.deeper"))
        assertNull(config.valueAt("display"))
    }

    @Test
    fun `a save sends only its own key`() {
        assertEquals(
            json("""{"display": {"show_reasoning": true}}"""),
            configPatch("display.show_reasoning", ConfigValue.Flag(true).toJson()),
        )
        assertEquals(json("""{"timezone": ""}"""), configPatch("timezone", JsonPrimitive("")))
        assertEquals(
            json("""{"delegation": {"provider": "openrouter", "model": ""}, "timezone": "UTC"}"""),
            configPatch(
                mapOf(
                    "delegation.provider" to JsonPrimitive("openrouter"),
                    "delegation.model" to JsonPrimitive(""),
                    "timezone" to JsonPrimitive("UTC"),
                ),
            ),
        )
        // Whole numbers stay integers; fractions stay fractions.
        assertEquals("300", ConfigValue.Number(300.0).toJson().toString())
        assertEquals("0.75", ConfigValue.Number(0.75).toJson().toString())
    }

    @Test
    fun `numbers and lines are read as typed`() {
        assertEquals(0.8, parseNumber(" 0,8 "))
        assertEquals(300.0, parseNumber("300"))
        assertNull(parseNumber("lots"))
        assertNull(parseNumber("NaN"))
        assertEquals("300", formatNumber(300.0))
        assertEquals("0.5", formatNumber(0.5))
        assertEquals(listOf("A", "B"), parseLines(" A \n\n B\n "))
    }

    @Test
    fun `the schema hides keys this Hermes lacks, unless it couldn't be read`() {
        val schema = ConfigSchema.parse(
            Json.parseToJsonElement(
                """{"fields": {"agent.service_tier": {"type": "select", "options": ["", "fast"]},
                   "memory.memory_enabled": {"type": "boolean"}}}""",
            ),
        )
        assertTrue(schema.has("agent.service_tier"))
        assertFalse(schema.has("sessions.auto_archive"))
        assertEquals(listOf("", "fast"), schema.options("agent.service_tier"))
        assertTrue(ConfigSchema().has("sessions.auto_archive"))
    }

    @Test
    fun `fallbacks keep their other settings, and read the older strings`() {
        val config = json(
            """{"fallback_providers": [{"provider": "openrouter", "model": "a/b", "base_url": "https://x"},
               "anthropic/claude-x", 5]}""",
        )
        val list = FallbackModel.list(config)
        assertEquals(
            listOf(
                FallbackModel("openrouter", "a/b", json("""{"base_url": "https://x"}""")),
                FallbackModel("anthropic", "claude-x"),
            ),
            list,
        )
        assertEquals(json("""{"base_url": "https://x", "provider": "openrouter", "model": "a/b"}"""), list[0].toJson())
    }

    @Test
    fun `default reasoning follows Hermes' reading`() {
        assertEquals("high", defaultReasoning(json("""{"agent": {"reasoning_effort": "high"}}""")))
        assertEquals("none", defaultReasoning(json("""{"agent": {"reasoning_effort": false}}""")))
        assertEquals("none", defaultReasoning(json("""{"agent": {"reasoning_effort": "disabled"}}""")))
        assertEquals(
            "low",
            defaultReasoning(json("""{"agent": {"reasoning_effort": {"enabled": true, "effort": "low"}}}""")),
        )
        assertEquals("medium", defaultReasoning(json("""{"agent": {"reasoning_effort": ""}}""")))
        assertEquals("xhigh", defaultReasoning(json("""{"reasoning": "xhigh"}""")))
        assertEquals("medium", defaultReasoning(json("{}")))
    }
}
