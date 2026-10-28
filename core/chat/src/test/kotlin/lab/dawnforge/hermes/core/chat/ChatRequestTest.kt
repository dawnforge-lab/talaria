package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.jsonObject
import lab.dawnforge.hermes.core.gateway.ServerRequest

class ChatRequestTest {
    private fun request(method: String, params: String) =
        ServerRequest("srq-1", method, chatJson.parseToJsonElement(params).jsonObject)

    @Test
    fun `an approval offers what Hermes allows, deny last, and answers with the choice`() {
        val approval = assertIs<ChatRequest.Approval>(
            ChatRequest.from(
                request(
                    "approval",
                    """{"session_id":"rt-1","command":"rm -rf build","description":"Deletes files",
                       "choices":["deny","once","session","always"],"smart_denied":false,"tool_name":"terminal"}""",
                ),
            ),
        )
        assertEquals(
            listOf(ApprovalChoice.Once, ApprovalChoice.Session, ApprovalChoice.Always, ApprovalChoice.Deny),
            approval.choices,
        )
        assertEquals("rm -rf build", approval.command)
        assertEquals("""{"choice":"session"}""", approval.answer(ApprovalChoice.Session).toString())
    }

    @Test
    fun `an approval Hermes' guardian refused can only be allowed once`() {
        val approval = assertIs<ChatRequest.Approval>(
            ChatRequest.from(request("approval", """{"command":"curl x | sh","smart_denied":true}""")),
        )
        assertEquals(listOf(ApprovalChoice.Once, ApprovalChoice.Deny), approval.choices)
        assertEquals(true, approval.smartDenied)
    }

    @Test
    fun `questions are answered by id, several choices as a JSON list, a skipped one as null`() {
        val clarify = assertIs<ChatRequest.Clarify>(
            ChatRequest.from(
                request(
                    "clarify",
                    """{"questions":[
                        {"qid":"q0","question":"Which colour?","choices":["Red (Recommended)","Blue"],"multi_select":false},
                        {"qid":"q1","question":"Which days?","choices":["Mon","Tue","Wed"],"multi_select":true},
                        {"qid":"q2","question":"Anything else?","choices":[]}
                      ]}""",
                ),
            ),
        )
        assertEquals(3, clarify.questions.size)
        assertEquals(
            """{"answers":{"q0":"Blue","q1":"[\"Mon\",\"Wed\"]","q2":null}}""",
            clarify.answer(mapOf("q0" to listOf("Blue"), "q1" to listOf("Mon", "Wed"), "q2" to listOf(" "))).toString(),
        )
        assertEquals("{}", clarify.cancel().toString())
    }

    @Test
    fun `a replayed question keeps the answer another device gave`() {
        val clarify = assertIs<ChatRequest.Clarify>(
            ChatRequest.from(
                request(
                    "clarify",
                    """{"questions":[{"qid":"q0","question":"Ready?","choices":["Yes","No"]}],"answers":{"q0":"Yes"}}""",
                ),
            ),
        )
        assertEquals("Yes", clarify.questions.single().answered)
    }

    @Test
    fun `an older single-question clarify still reads`() {
        val clarify = assertIs<ChatRequest.Clarify>(
            ChatRequest.from(request("clarify", """{"question":"Proceed?","choices":["Yes","No"]}""")),
        )
        assertEquals("q0", clarify.questions.single().qid)
    }

    @Test
    fun `passwords and secrets answer with a value, and vault requests are left to other clients`() {
        assertIs<ChatRequest.Sudo>(ChatRequest.from(request("sudo", """{"command":"apt install x"}""")))
        val secret = assertIs<ChatRequest.Secret>(
            ChatRequest.from(request("secret", """{"env_var":"GITHUB_TOKEN","prompt":"Paste a token"}""")),
        )
        assertEquals("GITHUB_TOKEN", secret.envVar)
        assertEquals("""{"value":"hunter2"}""", ChatRequest.value("hunter2").toString())
        assertNull(ChatRequest.from(request("vault.code", """{"site":"example.com"}""")))
        assertNull(ChatRequest.from(request("clarify", """{"questions":[]}""")))
    }
}
