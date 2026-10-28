package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import lab.dawnforge.hermes.core.gateway.GatewayEvent

class ChatModelTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun ChatState.reduce(type: String, payload: JsonObject) =
        reduce(GatewayEvent(type, "s1", payload, seq = null))

    @Test
    fun `session info names the model, provider and reasoning`() {
        assertEquals(
            ChatModel("glm-5.3-flash", "ollama-cloud", "high"),
            ChatModel.from(json("""{"model":"glm-5.3-flash","provider":"ollama-cloud","reasoning_effort":"high"}""")),
        )
        assertEquals(
            ChatModel("kimi-k2.6", null, ""),
            ChatModel.from(json("""{"model":"kimi-k2.6","reasoning_effort":""}""")),
        )
        assertNull(ChatModel.from(json("""{"running":true}""")))
    }

    @Test
    fun `the options keep signed-in providers with models, and what each model can do`() {
        val choices = ModelChoices.parse(
            json(
                """
                {"model":"glm-5.3-flash","provider":"ollama-cloud","providers":[
                  {"slug":"ollama-cloud","name":"Ollama Cloud","authenticated":true,"models":["glm-5.3","kimi-k2.6"],
                   "capabilities":{"glm-5.3":{"reasoning":true,"fast":true},"kimi-k2.6":{"reasoning":false}}},
                  {"slug":"openrouter","name":"OpenRouter","authenticated":true,"models":["x/y"],
                   "capabilities":{"x/y":{"reasoning":true,"can_disable_reasoning":false}}},
                  {"slug":"anthropic","name":"Anthropic","authenticated":false,"models":["claude-opus-5-5"]},
                  {"slug":"empty","name":"Empty","models":[]}
                ]}
                """,
            ),
        )
        assertEquals(listOf("ollama-cloud", "openrouter"), choices.providers.map { it.slug })
        assertEquals(
            ModelChoices.Model("glm-5.3", reasons = true, canDisableReasoning = true, fast = true),
            choices.model("ollama-cloud", "glm-5.3"),
        )
        assertEquals(false, choices.model("ollama-cloud", "kimi-k2.6")?.reasons)
        assertEquals(false, choices.model("ollama-cloud", "kimi-k2.6")?.fast)
        assertEquals(false, choices.model("openrouter", "x/y")?.canDisableReasoning)
        // A chat whose provider isn't known still finds its model by id.
        assertEquals("x/y", choices.model(null, "x/y")?.id)
    }

    @Test
    fun `the chat follows its model through session info, running or not`() {
        val info = { model: String, running: String ->
            json("""{"model":"$model","provider":"ollama-cloud","reasoning_effort":"low"$running}""")
        }
        var state = ChatState().reduce("session.info", info("glm-5.3-flash", ""))
        assertEquals(ChatModel("glm-5.3-flash", "ollama-cloud", "low"), state.model)
        state = state.reduce("session.info", info("kimi-k2.6", ""","running":true"""))
        assertEquals("kimi-k2.6", state.model?.model)
        assertEquals(true, state.running)
        // An update without a model keeps the one known.
        state = state.reduce("session.info", json("""{"running":false}"""))
        assertEquals("kimi-k2.6", state.model?.model)
    }
}
