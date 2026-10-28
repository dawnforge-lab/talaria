package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import lab.dawnforge.hermes.core.gateway.GatewayEvent
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.model.NoteKind

class ChatExtrasTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun ChatState.reduce(type: String, payload: JsonObject) =
        reduce(GatewayEvent(type, "s1", payload, seq = null))

    @Test
    fun `the catalog lists categories then extensions, with aliases, leaving out terminal-only commands`() {
        val commands = SlashCommand.parseCatalog(
            json(
                """
                {"pairs":[["/new","Start a new session"],["/my-skill","A skill"]],
                 "canon":{"/new":"/new","/reset":"/new","/compact":"/compress","/compress":"/compress"},
                 "commands":{"/model":{"desktop":"hidden"},"/fast":{"desktop":"advanced"},"/clear":{"desktop":"terminal"}},
                 "categories":[
                   {"name":"Session","pairs":[["/new","Start a new session"],["/compress","Compress (usage: /compress [focus])"],["/clear","Clear"]]},
                   {"name":"Configuration","pairs":[["/model","Switch model"],["/fast","Fast mode"]]}
                 ]}
                """,
            ),
        )
        assertEquals(listOf("new", "compress", "fast", "my-skill"), commands.map { it.name })
        assertEquals(listOf("reset"), commands.first { it.name == "new" }.aliases)
        assertEquals("Session", commands.first { it.name == "compress" }.category)
        assertEquals(SlashCommand.EXTENSIONS, commands.last().category)
        assertTrue(commands.first { it.name == "compress" }.matches("comp"))
        assertTrue(commands.first { it.name == "compress" }.matches("COMPA"))
    }

    @Test
    fun `slash results say what to do`() {
        assertEquals(SlashResult.Output("Model: x", null), SlashResult.parse(json("""{"output":"Model: x"}""")))
        assertEquals(
            SlashResult.Output("done", "busy"),
            SlashResult.parse(json("""{"type":"exec","output":"done","warning":"busy"}""")),
        )
        assertEquals(
            SlashResult.Send("scaffold", "Run the skill"),
            SlashResult.parse(json("""{"type":"skill","message":"scaffold","display":"Run the skill"}""")),
        )
        assertEquals(
            SlashResult.Prefill("last message", "Undid 1 turn"),
            SlashResult.parse(json("""{"type":"prefill","message":"last message","notice":"Undid 1 turn"}""")),
        )
        assertEquals(
            SlashResult.Alias("/compress"),
            SlashResult.parse(json("""{"type":"alias","target":"/compress"}""")),
        )
        assertEquals(SlashResult.Output("note", null), SlashResult.parse(json("""{"type":"send","notice":"note"}""")))
    }

    @Test
    fun `compression says what changed, or why nothing did`() {
        assertEquals(
            CompressOutcome.Done("Compressed 12 messages\n9k → 2k tokens"),
            CompressOutcome.parse(
                json(
                    """{"status":"compressed","summary":{"noop":false,"headline":"Compressed 12 messages","token_line":"9k → 2k tokens"}}""",
                ),
            ),
        )
        assertEquals(
            CompressOutcome.Unchanged("Nothing to compress"),
            CompressOutcome.parse(
                json("""{"status":"compressed","summary":{"noop":true,"headline":"Nothing to compress"}}"""),
            ),
        )
        assertEquals(
            CompressOutcome.Unchanged("held"),
            CompressOutcome.parse(json("""{"compressed":false,"lock_held":true,"message":"held"}""")),
        )
        assertEquals(
            CompressOutcome.Pending("later"),
            CompressOutcome.parse(json("""{"status":"pending","message":"later"}""")),
        )
    }

    @Test
    fun `the context breakdown reads its categories`() {
        val breakdown = ContextBreakdown.parse(
            json(
                """
                {"categories":[{"id":"system_prompt","label":"System prompt","tokens":3000,"color":"var(--x)"},
                               {"id":"conversation","label":"Conversation","tokens":1200}],
                 "context_max":200000,"context_percent":2,"context_used":4200,"context_estimated":true}
                """,
            ),
        )
        assertEquals(4200, breakdown.used)
        assertEquals(200000, breakdown.max)
        assertEquals(2, breakdown.percent)
        assertTrue(breakdown.estimated)
        assertEquals(listOf("system_prompt", "conversation"), breakdown.categories.map { it.id })
    }

    @Test
    fun `sub-agents follow their events and the list`() {
        var state = ChatState()
        state =
            state.reduce(
                "subagent.spawn_requested",
                json("""{"goal":"Read the docs","task_count":1,"task_index":0,"delegation_id":"d1"}"""),
            )
        assertEquals(1, state.activeAgents)
        state = state.reduce(
            "subagent.start",
            json("""{"goal":"Read the docs","task_count":1,"task_index":0,"subagent_id":"sa1","model":"m"}"""),
        )
        state = state.reduce(
            "subagent.tool",
            json(
                """{"goal":"Read the docs","task_count":1,"task_index":0,"subagent_id":"sa1","tool_preview":"read_file docs/a.md","tool_count":3}""",
            ),
        )
        val agent = state.agents.first { it.id == "sa1" }
        assertEquals(AgentStatus.Running, agent.status)
        assertEquals("read_file docs/a.md", agent.lastLine)
        assertEquals(3, agent.toolCount)
        // Hermes no longer lists it: it finished.
        state = state.withListedAgents(emptyList())
        assertEquals(AgentStatus.Completed, state.agents.first { it.id == "sa1" }.status)
        state =
            state.reduce(
                "subagent.complete",
                json("""{"goal":"x","task_count":1,"task_index":0,"subagent_id":"sa2","status":"failed"}"""),
            )
        assertEquals(AgentStatus.Failed, state.agents.first { it.id == "sa2" }.status)
    }

    @Test
    fun `notes stay after their row when the history is reloaded, and side answers become notes`() {
        val first = ChatItem.User("row-1", "hello")
        val reply = ChatItem.Assistant("row-2", "hi")
        var state = ChatState().withHistory(listOf(first, reply))
        state = state.withNote(NoteKind.Command, "/usage", "12 calls")
        state = state.reduce("btw.complete", json("""{"task_id":"btw_1","question":"why?","text":"because"}"""))
        state = state.reduce("background.complete", json("""{"task_id":"bg_1","text":"   "}"""))
        val later = ChatItem.User("row-3", "next")
        state = state.withHistory(listOf(first, reply, later))
        val notes = state.items.filterIsInstance<ChatItem.Note>()
        assertEquals(listOf(NoteKind.Command, NoteKind.SideQuestion), notes.map { it.kind })
        assertEquals("why?", notes.last().title)
        assertEquals(listOf("row-1", "row-2", notes[0].key, notes[1].key, "row-3"), state.items.map { it.key })
    }

    @Test
    fun `session info carries fast mode and approvals`() {
        var state = ChatState().reduce("session.info", json("""{"fast":true,"yolo":false,"approval_mode":"smart"}"""))
        assertEquals(ChatSettings(fast = true, yolo = false, approvalMode = "smart"), state.settings)
        state = state.reduce("session.info", json("""{"running":false,"approval_mode":"off","yolo":true}"""))
        assertTrue(state.settings!!.yoloLocked)
        assertNull(ChatSettings.from(json("""{"model":"m"}""")))
    }
}
