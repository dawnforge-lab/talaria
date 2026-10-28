package lab.dawnforge.hermes.core.data.chat

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.chat.normalizeHistory
import lab.dawnforge.hermes.core.data.LiveHermes
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.net.NetResult
import org.junit.Assume.assumeTrue

/**
 * A real turn that waits on the user, for answering it from a phone's notification (P3). Runs
 * only with `-Phermes.live.push=approval` (a command Hermes asks to approve, with approvals.mode
 * manual) or `=question` (the clarify tool). This test never answers: the phone does, and the test
 * waits for the turn to end and prints how it went. It deletes its chat afterwards.
 */
class LivePushAnswerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `a turn waits for the phone's answer and carries on`(): Unit = runBlocking {
        val kind = System.getProperty("hermes.live.push").orEmpty()
        assumeTrue("set -Phermes.live.push=approval|question", LiveHermes.config != null && kind.isNotEmpty())
        val prompt = when (kind) {
            "approval" -> APPROVAL_PROMPT
            "question" -> QUESTION_PROMPT
            else -> error("hermes.live.push must be approval or question")
        }
        val live = LiveHermes.connect(scope)
        val created = live.gateway.call(
            "session.create",
            buildJsonObject {
                put("profile", Profile.DEFAULT)
                put("title", "Android push $kind check (androidcheck-p3)")
            },
        ) as JsonObject
        val runtimeId = assertNotNull((created["session_id"] as? JsonPrimitive)?.contentOrNull)
        var storedId = (created["stored_session_id"] as? JsonPrimitive)?.contentOrNull
        live.gateway.trackSession(runtimeId, storedId)
        // Says when Hermes asks; the phone answers, never this connection.
        val asked = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            live.gateway.serverRequests.collect { println("live: Hermes asks: ${it.method}, now answer on the phone") }
        }
        try {
            val ended = scope.async(start = CoroutineStart.UNDISPATCHED) {
                live.gateway.events.first { event ->
                    event.sessionId == runtimeId && event.type == "session.info" &&
                        (event.payload?.get("running") as? JsonPrimitive)?.booleanOrNull == false
                }
            }
            println("live: sending the $kind prompt")
            assertNotNull((live.chats.send(runtimeId, prompt) as? NetResult.Success)?.value)
            val info = withTimeout(TURN_TIMEOUT_MS) { ended.await() }
            storedId = storedId ?: (info.payload?.get("stored_session_id") as? JsonPrimitive)?.contentOrNull
            val stored = assertNotNull(storedId)
            val page = (live.chats.history(live.id, Profile.DEFAULT, stored) as NetResult.Success).value
            val items = normalizeHistory(page.rows)
            for (tool in items.filterIsInstance<ChatItem.Tool>()) {
                println(
                    "live: tool ${tool.name}: ${tool.summary?.take(200)}",
                )
            }
            val reply = items.filterIsInstance<ChatItem.Assistant>().lastOrNull()?.text
            println("live: the agent answered: ${reply?.take(400)}")
            assertNotNull(reply, "the turn ended with a reply")
        } finally {
            asked.cancel()
            storedId?.let { live.sessions.delete(live.id, Profile.DEFAULT, it) }
            println("live: deleted the test chat")
            live.gateway.stop()
        }
    }

    private companion object {
        /** Room to see the notification and answer it. */
        const val TURN_TIMEOUT_MS = 8 * 60_000L

        const val APPROVAL_PROMPT = "Android push approval check (androidcheck-p3). Use the terminal tool to run " +
            "exactly this one command: mkdir androidcheck-p3-tmp && rm -rf androidcheck-p3-tmp . It only makes and " +
            "removes an empty folder. Then reply with one short sentence saying whether it ran."

        const val QUESTION_PROMPT = "Android push question check (androidcheck-p3). Use the clarify tool to ask me " +
            "exactly one question, 'Which colour?', with the two choices 'Red' and 'Blue'. When I have answered, " +
            "reply with one short sentence naming the colour I picked."
    }
}
