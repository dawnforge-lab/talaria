package lab.dawnforge.hermes.core.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.push.calls.CallKind
import lab.dawnforge.hermes.core.push.calls.HermesCall
import lab.dawnforge.hermes.core.push.e2e.NotificationE2E

/** Data messages as the relay builds them (relay/src/fcm.mjs), around envelopes the plugin sealed. */
class IncomingPushReaderTest {
    private val vectors = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/e2e-vectors.json")).readText(),
    ).jsonObject
    private val installation = vectors["installation_id"]!!.jsonPrimitive.content
    private val gateway = vectors["gateway_id"]!!.jsonPrimitive.content
    private val keys = NotificationE2E.deriveKeys(
        vectors["kid"]!!.jsonPrimitive.content,
        NotificationE2E.unb64u(vectors["secret"]!!.jsonPrimitive.content),
    )
    private val dashboard = DashboardId("3f1c3c1e-5b2a-4c1e-9f1a-0a1b2c3d4e5f")
    private val events = vectors["events"]!!.jsonArray.map { it.jsonObject }
    private val reply = events.first { it.type() == "response.ready" }
    private val now = reply.envelope()["iat"]!!.jsonPrimitive.long + 30

    private suspend fun read(
        data: Map<String, String>,
        nowSeconds: Long = now,
        known: Boolean = true,
        installationId: String? = installation,
        preferences: PushStore.NotifyPreferences = PushStore.NotifyPreferences(),
    ) = IncomingPushReader.read(data, installationId, nowSeconds) { id ->
        if (known && id == gateway) PairingKeys(dashboard, "default", keys, preferences) else null
    }

    @Test
    fun `a type the profile muted is dropped, and hidden text leaves only the generic copy`() = runTest {
        val muted = PushStore.NotifyPreferences(muted = setOf("response.ready"))
        assertEquals(IncomingPush.Ignore, read(dataFor(reply), preferences = muted))
        val failed = events.first { it.type() == "turn.failed" }
        assertIs<IncomingPush.Show>(read(dataFor(failed), preferences = muted))

        val hidden = PushStore.NotifyPreferences(hideText = true)
        val shown = assertIs<IncomingPush.Show>(read(dataFor(reply), preferences = hidden)).notification
        assertNull(shown.title)
        assertNull(shown.body)
        assertEquals(
            reply["inner"]!!.jsonObject.text("stored_session_id"),
            shown.target?.sessionId,
            "the tap still opens the chat",
        )
        // A question can still be answered, but its wording and choices stay off the screen.
        val question = events.first { it.type() == "input.needed" }
        assertEquals(
            NotificationAction.Question(gateway, "talaria-push-abc123def456", "", "", emptyList()),
            assertIs<IncomingPush.Show>(read(dataFor(question), preferences = hidden)).notification.action,
        )
    }

    @Test
    fun `a sealed call from Hermes carries what it's about, its chat and when it was placed`() = runTest {
        val event = events.first { it.type() == "call.requested" }
        val shown = assertIs<IncomingPush.Show>(
            read(
                dataFor(event),
                nowSeconds =
                    event.envelope()["iat"]!!.jsonPrimitive.long + 5,
            ),
        )
        val notification = shown.notification
        assertEquals(NotificationKind.Call, notification.kind)
        val call = notification.call!!
        assertEquals("0123456789abcdef01234567", call.id)
        assertEquals(CallKind.Done, call.kind)
        assertEquals("Check the server", call.title)
        assertEquals("The deploy is live.", call.reason)
        assertEquals(listOf("sess-1", "20261021_120000_abcdef"), call.sessionIds)
        assertEquals(event.envelope()["iat"]!!.jsonPrimitive.long, call.issuedAt)
        assertEquals("20261021_120000_abcdef", notification.target?.sessionId)

        // Unverified, it's only "Hermes wants to talk": nothing to ring or answer.
        val plain = dataFor(event) - "talaria_e2e"
        val unverified = assertIs<IncomingPush.Show>(read(plain)).notification
        assertEquals(NotificationKind.Call, unverified.kind)
        assertNull(unverified.call)
        assertNull(unverified.target)
    }

    @Test
    fun `a call's id and text are kept only in the shape the host accepts back`() {
        val call = HermesCall.parse(
            Json.parseToJsonElement(
                """{"id":"bad id","kind":"later","title":"  Two \"quoted\"
 lines ","session_ids":["a","a","",1,"b","c","d","e"]}""",
            ).jsonObject,
            issuedAt = 1,
        )!!
        assertEquals("", call.id)
        assertNull(call.kind)
        assertEquals("Two 'quoted' lines", call.title)
        assertEquals(listOf("a", "b", "c", "d"), call.sessionIds)
    }

    @Test
    fun `a sealed reply shows its own text and opens its chat`() = runTest {
        val push = assertIs<IncomingPush.Show>(read(dataFor(reply)))
        val inner = reply["inner"]!!.jsonObject
        assertEquals(NotificationKind.Reply, push.notification.kind)
        assertEquals(inner.text("title"), push.notification.title)
        assertEquals(inner.text("body"), push.notification.body)
        assertEquals(
            HermesNotification.Target(dashboard, "default", inner.text("stored_session_id")),
            push.notification.target,
        )
        assertEquals("gw:$gateway:${inner.text("stored_session_id")}", push.notification.thread)
        assertEquals(reply.envelope()["msg"]!!.jsonPrimitive.content, push.messageId)
    }

    @Test
    fun `approvals and questions are the needs-you kind, failures their own`() = runTest {
        val kinds = events.associate { it.type() to assertIs<IncomingPush.Show>(read(dataFor(it))).notification.kind }
        assertEquals(NotificationKind.NeedsYou, kinds["approval.needed"])
        assertEquals(NotificationKind.NeedsYou, kinds["input.needed"])
        assertEquals(NotificationKind.Failure, kinds["turn.failed"])
        // A failure with no session threads by the plugin's token, and keeps the pairing's profile.
        val failed = assertIs<IncomingPush.Show>(
            read(
                dataFor(
                    events.first {
                        it.type() == "turn.failed"
                    },
                ),
            ),
        ).notification
        assertEquals("default", failed.target?.profile)
        assertNull(failed.target?.sessionId)
    }

    @Test
    fun `a verified approval can be allowed once or denied, a verified question answered`() = runTest {
        val approval = assertIs<IncomingPush.Show>(read(dataFor(events.first { it.type() == "approval.needed" })))
        // The plugin's card offers once, session, always and deny; the notification only once and deny.
        assertEquals(NotificationAction.Approval("sess-1", listOf("once", "deny")), approval.notification.action)

        val question = assertIs<IncomingPush.Show>(read(dataFor(events.first { it.type() == "input.needed" })))
        assertEquals(
            NotificationAction.Question(
                gateway,
                "talaria-push-abc123def456",
                "",
                "Deploy to prod?",
                listOf("Yes", "No"),
            ),
            question.notification.action,
        )
        assertNull(assertIs<IncomingPush.Show>(read(dataFor(reply))).notification.action)
    }

    @Test
    fun `a push that doesn't verify shows the generic copy and leads nowhere`() = runTest {
        val tampered = dataFor(reply).toMutableMap()
        val envelope = reply.envelope().toMutableMap()
        envelope["iat"] = kotlinx.serialization.json.JsonPrimitive(reply.envelope()["iat"]!!.jsonPrimitive.long + 1)
        tampered["talaria_e2e"] = JsonObject(envelope).toString()
        val shown = assertIs<IncomingPush.Show>(read(tampered)).notification
        assertNull(shown.target)
        assertNull(shown.action)
        assertNull(shown.title)
        assertNull(shown.body)

        // Plaintext for a keyed pairing could only come from someone other than the plugin.
        val plain = dataFor(reply) - "talaria_e2e" + ("title" to "Open me")
        assertNull(assertIs<IncomingPush.Show>(read(plain)).notification.target)
        assertNull(assertIs<IncomingPush.Show>(read(dataFor(reply) + ("talaria_e2e" to "{"))).notification.target)
        assertNull(assertIs<IncomingPush.Show>(read(dataFor(reply), installationId = null)).notification.target)
    }

    @Test
    fun `unknown pairings, iPhone-only types, malformed routing and stale pushes are ignored`() = runTest {
        assertEquals(IncomingPush.Ignore, read(dataFor(reply), known = false))
        assertEquals(IncomingPush.Ignore, read(mapOf("talaria" to routing("location.requested").toString())))
        assertEquals(IncomingPush.Ignore, read(mapOf("talaria" to "not json")))
        assertEquals(IncomingPush.Ignore, read(emptyMap()))
        val issued = reply.envelope()["iat"]!!.jsonPrimitive.long
        assertEquals(
            IncomingPush.Ignore,
            read(
                dataFor(reply),
                nowSeconds =
                    issued + IncomingPushReader.MAX_AGE_SECONDS + 1,
            ),
        )
        assertEquals(
            IncomingPush.Ignore,
            read(
                dataFor(reply),
                nowSeconds =
                    issued - IncomingPushReader.MAX_FUTURE_SECONDS - 1,
            ),
        )
    }

    @Test
    fun `a phone change that alerts is a confirmation Hermes waits on, one that wakes in the background is not`() =
        runTest {
            val alert = buildJsonObject {
                routing("phone.changes").forEach { (key, value) -> put(key, value) }
                put("mode", "alert")
            }
            val shown = assertIs<IncomingPush.Show>(read(mapOf("talaria" to alert.toString()))).notification
            assertEquals(NotificationKind.NeedsYou, shown.kind)
            assertEquals("phone.changes", shown.type)
            // As the relay sends it: without the mode.
            val relayed = read(mapOf("talaria" to routing("phone.changes").toString()))
            assertEquals(NotificationKind.NeedsYou, assertIs<IncomingPush.Show>(relayed).notification.kind)

            val background = buildJsonObject {
                routing("phone.changes").forEach { (key, value) -> put(key, value) }
                put("mode", "background")
            }
            assertEquals(IncomingPush.Ignore, read(mapOf("talaria" to background.toString())))
        }

    @Test
    fun `a token challenge goes to the waiting registration`() = runTest {
        assertEquals(IncomingPush.Challenge("n".repeat(43)), read(mapOf("talaria_token_challenge" to "n".repeat(43))))
    }

    private fun routing(type: String) = buildJsonObject {
        put("type", type)
        put("e2e", 1)
        put("gateway_id", gateway)
        put("dashboard_id", dashboard.value)
    }

    private fun dataFor(event: JsonObject): Map<String, String> = mapOf(
        "title" to "Response ready",
        "body" to "Hermes has finished responding.",
        "thread" to event.envelope()["tok"]!!.jsonPrimitive.content,
        "talaria" to routing(event.type()).toString(),
        "talaria_e2e" to event.envelope().toString(),
    )

    private fun JsonObject.type() = this["type"]!!.jsonPrimitive.content

    private fun JsonObject.envelope() = this["envelope"]!!.jsonObject

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content
}
