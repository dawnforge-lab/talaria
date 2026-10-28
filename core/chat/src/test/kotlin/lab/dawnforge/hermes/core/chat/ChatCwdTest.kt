package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import lab.dawnforge.hermes.core.gateway.GatewayEvent

class ChatCwdTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun ChatState.reduce(type: String, payload: JsonObject) =
        reduce(GatewayEvent(type, "s1", payload, seq = null))

    @Test
    fun `the chat follows its working folder through session info`() {
        var state = ChatState()
        assertNull(state.cwd)

        state = state.reduce("session.info", json("""{"cwd":"D:\\hermes-talaria","branch":"main"}"""))
        assertEquals("D:\\hermes-talaria", state.cwd)

        state = state.reduce("session.info", json("""{"running":true,"cwd":"D:\\other"}"""))
        assertEquals("D:\\other", state.cwd)

        // An update that names no folder keeps the one known.
        state = state.reduce("session.info", json("""{"running":false,"cwd":""}"""))
        assertEquals("D:\\other", state.cwd)
    }
}
