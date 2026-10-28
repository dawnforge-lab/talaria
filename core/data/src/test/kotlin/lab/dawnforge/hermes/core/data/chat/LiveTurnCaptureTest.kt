package lab.dawnforge.hermes.core.data.chat

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.chat.ChatState
import lab.dawnforge.hermes.core.chat.normalizeHistory
import lab.dawnforge.hermes.core.data.LiveHermes
import lab.dawnforge.hermes.core.gateway.GatewayEvent
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.net.NetResult
import org.junit.Assume.assumeTrue

/**
 * Runs one real turn and records it: every event of the turn, and the history Hermes stored for
 * it, written to `-Phermes.live.turn=<directory>` as `events.jsonl` and `history.json`. Those become
 * core:chat's replay fixtures. It then checks that the streamed turn folds into the same rows as
 * its stored copy.
 *
 * **This calls the model, which costs money:** it runs only when that property is set, never in CI.
 * The chat it creates is deleted afterwards. A server request (an approval) is declined.
 */
class LiveTurnCaptureTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `records a real turn, which reads the same streamed and stored`() = runBlocking {
        val out = System.getProperty("hermes.live.turn")?.let(::File)
        assumeTrue("set -Phermes.live.turn=<dir> to run a real (paid) turn", LiveHermes.config != null && out != null)
        out!!.mkdirs()
        val live = LiveHermes.connect(scope)
        val created = live.gateway.call("session.create", buildJsonObject { put("profile", Profile.DEFAULT) })
            as JsonObject
        val runtimeId = assertNotNull((created["session_id"] as? JsonPrimitive)?.contentOrNull)
        var storedId = (created["stored_session_id"] as? JsonPrimitive)?.contentOrNull
        live.gateway.trackSession(runtimeId, storedId)

        val events = mutableListOf<GatewayEvent>()
        val recorder = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            live.gateway.events.collect { if (it.sessionId == runtimeId) synchronized(events) { events += it } }
        }
        val declined = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            live.gateway.serverRequests.collect {
                println("live: declined a ${it.method} request")
                live.gateway.decline(it)
            }
        }
        try {
            val submitted = live.gateway.call(
                "prompt.submit",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("text", System.getProperty("hermes.live.prompt") ?: PROMPT)
                },
            )
            println("live: submitted, $submitted")
            withTimeout(TURN_TIMEOUT_MS) {
                live.gateway.events.first { event ->
                    event.sessionId == runtimeId && event.type == "session.info" &&
                        (event.payload?.get("running") as? JsonPrimitive)?.booleanOrNull == false
                }
            }
            recorder.cancelAndJoin()
            val turn = synchronized(events) { events.toList() }
            storedId = storedId ?: turn.firstNotNullOfOrNull { e ->
                (e.payload?.get("stored_session_id") as? JsonPrimitive)?.contentOrNull
            }
            val stored = assertNotNull(storedId, "Hermes named the stored session")
            val page = assertIs<NetResult.Success<HistoryPage>>(
                live.chats.history(live.id, Profile.DEFAULT, stored),
            ).value

            out.resolve("events.jsonl").writeText(turn.joinToString("\n") { it.toJson().toString() } + "\n")
            out.resolve("history.json").writeText(JsonArray(page.rows).toString())
            println(
                "live: wrote ${turn.size} events (${turn.map {
                    it.type
                }.distinct()}) and ${page.rows.size} rows to $out",
            )

            val streamed = turn.fold(ChatState()) { state, event -> state.reduce(event) }.items
            val history = normalizeHistory(page.rows)
            val storedTurn = history.drop(history.indexOfLast { it is ChatItem.User } + 1)
            assertEquals(storedTurn.map(::shown), streamed.map(::shown))
        } finally {
            recorder.cancel()
            declined.cancel()
            storedId?.let { assertIs<NetResult.Success<Unit>>(live.sessions.delete(live.id, Profile.DEFAULT, it)) }
            println("live: deleted the test chat")
            live.gateway.stop()
        }
    }

    /**
     * For watching a turn stream on a device: with `-Phermes.live.into=<stored id>` it sends one
     * prompt into that existing chat (which the app may have open; Hermes shares the live session)
     * and waits for the turn to end. It touches only the chat named, and leaves it in place.
     */
    @Test
    fun `sends a turn into a chat the app has open`() = runBlocking {
        val into = System.getProperty("hermes.live.into")
        assumeTrue("set -Phermes.live.into=<stored id> to send a real turn", LiveHermes.config != null && into != null)
        val live = LiveHermes.connect(scope)
        try {
            val opened = assertIs<NetResult.Success<OpenedChat>>(live.chats.open(Profile.DEFAULT, into!!)).value
            val ended = scope.async(start = CoroutineStart.UNDISPATCHED) {
                live.gateway.events.first { event ->
                    event.sessionId == opened.runtimeId && event.type == "session.info" &&
                        (event.payload?.get("running") as? JsonPrimitive)?.booleanOrNull == false
                }
            }
            live.gateway.call(
                "prompt.submit",
                buildJsonObject {
                    put("session_id", opened.runtimeId)
                    put("text", System.getProperty("hermes.live.prompt") ?: PROMPT)
                },
            )
            withTimeout(TURN_TIMEOUT_MS) { ended.await() }
            println("live: the turn in $into ended")
        } finally {
            live.gateway.stop()
        }
    }

    private fun GatewayEvent.toJson() = buildJsonObject {
        put("type", type)
        put("session_id", sessionId.orEmpty())
        payload?.let { put("payload", it) }
        seq?.let { put("seq", it) }
    }

    /** What a row shows, without keys, timestamps or durations (only the streamed copy has those). */
    private fun shown(item: ChatItem): String = when (item) {
        is ChatItem.User -> "user: ${item.text}"
        is ChatItem.Assistant -> "assistant: ${item.text}"
        is ChatItem.Reasoning -> "reasoning: ${item.text}"
        is ChatItem.Tool -> "tool ${item.name} [${item.status}] ${item.summary.orEmpty()} -> ${item.output.orEmpty()}"
        is ChatItem.Notice -> "notice ${item.kind} ${item.text.orEmpty()}"
        is ChatItem.Note -> "note ${item.kind} ${item.title.orEmpty()}: ${item.text}"
    }

    private companion object {
        /** A turn that thinks, calls one harmless tool, and answers in a line; `-Phermes.live.prompt` overrides it. */
        const val PROMPT = "This is an automated test of a mobile client. Use your terminal tool to run " +
            "`echo hermes-android-check`, then tell me in one short sentence what it printed."

        const val TURN_TIMEOUT_MS = 180_000L
    }
}
