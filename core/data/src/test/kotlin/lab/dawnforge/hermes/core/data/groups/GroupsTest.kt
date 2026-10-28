package lab.dawnforge.hermes.core.data.groups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import lab.dawnforge.hermes.core.data.bots.BotProfile
import lab.dawnforge.hermes.core.data.bots.BotRoster
import lab.dawnforge.hermes.core.data.bots.BotSession

/** Hosted group chats' rules, with the cases iOS's GroupChatModelsTests check. */
class GroupsTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun event(
        seq: Int,
        kind: String = "message.user",
        payload: String = "{}",
        id: String = "e$seq",
        room: String = "r1",
        actor: String = """{"kind":"user","id":"u"}""",
    ) = GroupEvent.parse(
        json("""{"room_id":"$room","seq":$seq,"event_id":"$id","kind":"$kind","actor":$actor,"payload":$payload}"""),
    )!!

    private fun page(vararg events: GroupEvent) =
        GroupLogPage(events.toList(), events.maxOfOrNull { it.seq } ?: 0, hasMore = false)

    private val furina = GroupMember("furina", "furina", "furina", "Furina")
    private val zhongli = GroupMember("zhongli", "zhongli", "zhongli", "Zhongli")
    private val members = listOf(furina, zhongli)

    private fun userMessage(seq: Int, text: String) =
        event(seq, payload = """{"text":"$text","thread_id":"main"}""", id = "user:d1")

    private fun memberMessage(seq: Int, member: String, round: Int, text: String) = event(
        seq,
        kind = "message.member",
        actor = """{"kind":"member","id":"$member","profile":"$member"}""",
        payload = """{"text":"$text","thread_id":"main","discussion_event_id":"user:d1","member_id":"$member",""" +
            """"round_index":$round}""",
        id = "dmessage:$seq",
    )

    private fun settled(seq: Int, member: String, round: Int, messageSeq: Int?) = event(
        seq,
        kind = "turn.settled",
        actor = """{"kind":"gateway","id":"gw"}""",
        payload = """{"thread_id":"main","discussion_event_id":"user:d1","member_id":"$member",""" +
            """"round_index":$round,"passed":${messageSeq == null},""" +
            """"message_event_id":${messageSeq?.let { "\"dmessage:$it\"" } ?: "null"}}""",
    )

    @Test
    fun `capabilities need every foundation method, and a bad answer is no answer`() {
        val all = GroupCapabilities.REQUIRED.joinToString(",") { "\"$it\"" }
        val caps = GroupCapabilities.parse(
            json("""{"protocol_version":2,"driver":true,"methods":[$all,"groups.stop"],"max_log_limit":300}"""),
        )!!
        assertTrue(caps.foundation)
        assertTrue(caps.supports("groups.stop"))
        assertFalse(caps.supports("groups.approve"))
        assertEquals(300, caps.maxLogLimit)
        assertFalse(GroupCapabilities.parse(json("""{"methods":["groups.list"]}"""))!!.foundation)
        assertNull(GroupCapabilities.parse(json("""{"methods":[]}""")))
        assertNull(GroupCapabilities.parse(json("[]")))
    }

    @Test
    fun `rooms and events decode defensively`() {
        val room = GroupRoom.parse(
            json(
                """{"room_id":" r1 ","name":"Team","members":[{"member_id":"a","handle":"ay"},{"profile":"b"}],""" +
                    """"revision":3,"latest_seq":9}""",
            ),
        )!!
        assertEquals("r1", room.roomId)
        assertEquals(listOf("a", "b"), room.members.map { it.identityKey })
        assertFalse(room.isDisbanded)
        assertTrue(GroupRoom.parse(json("""{"room_id":"r","disbanded_at":5}"""))!!.isDisbanded)
        assertNull(GroupRoom.parse(json("""{"name":"no id"}""")))

        val future = event(1, kind = "room.something_new", payload = """{"x":1}""")
        assertEquals("room.something_new", future.kind)
        assertNull(GroupEvent.parse(json("""{"room_id":"r1","seq":0}""")))
        assertNull(GroupEvent.parse(json("""{"seq":2}""")))
        // 1.5 or "2" is never a seq.
        assertNull(GroupEvent.parse(json("""{"room_id":"r1","seq":1.5}""")))
        assertNull(GroupEvent.parse(json("""{"room_id":"r1","seq":"2"}""")))
    }

    @Test
    fun `an actor is named by its identity, never by a bare display name`() {
        val actor = GroupActor("member", "furina", null, "Impostor")
        assertEquals("Furina", actor.label(members))
        assertEquals("Impostor", GroupActor("system", "", null, "Impostor").label(members))
    }

    @Test
    fun `approvals need their whole identity, and offer run and reject by default`() {
        val status = GroupDriverStatus.parse(
            json(
                """{"running":true,"working":true,"blocked":true,"pending_actions":[""" +
                    """{"kind":"approval","member_id":"furina","task_id":"t","execution_generation":2,""" +
                    """"approval":{"request_id":"q","command":"rm x","choices":["once","session","deny"]}},""" +
                    """{"kind":"approval","member_id":"furina","task_id":"t"},""" +
                    """{"kind":"clarify","member_id":"x","task_id":"y","execution_generation":1,"request_id":"z"}]}""",
            ),
        )!!
        assertEquals(1, status.approvals.size)
        val approval = status.approvals.single()
        assertEquals("q", approval.requestId)
        assertEquals(listOf("once", "deny"), approval.choices)
        val bare = GroupApproval.parse(
            json("""{"kind":"approval","member_id":"m","task_id":"t","execution_generation":1,"request_id":"r"}""")
                .jsonObject,
        )!!
        assertEquals(listOf("once", "deny"), bare.choices)
    }

    @Test
    fun `the replay keeps one ordered log and finds gaps`() {
        val start = GroupRoomReplay("r1").initial(page(event(5), event(4)))
        assertEquals(listOf(4, 5), start.events.map { it.seq })
        assertEquals(5, start.cursor)
        assertFalse(start.hasGap)

        val next = start.adopt(page(event(5), event(7), event(6)))
        assertEquals(listOf(4, 5, 6, 7), next.events.map { it.seq })
        assertFalse(next.hasGap)
        assertFalse(start.skipsAhead(page(event(6), event(7))))
        assertTrue(start.skipsAhead(page(event(7))))
        assertTrue(start.adopt(page(event(8))).hasGap)
        assertEquals(start, start.adopt(page(event(6, room = "other"))))
    }

    @Test
    fun `a message keeps its retry key until the gateway files it`() {
        assertEquals(
            "user:402620b9378cc92bb283e080f48aa667da933f3d54588b555c9c0fb61b545c0a",
            GroupRoomOutbox.serverEventId("talaria-abc"),
        )
        val outbox = GroupRoomOutbox().begin("hello")
        val id = outbox.pending!!.eventId
        assertEquals(id, outbox.begin("hello").pending!!.eventId)
        assertTrue(outbox.accepts("hello"))
        assertFalse(outbox.accepts("other"))
        assertEquals(outbox, outbox.accept("someone-else"))
        assertNull(outbox.accept(id).pending)
        assertFalse(outbox.settled(listOf(event(1, id = "user:other"))))
        assertTrue(outbox.settled(listOf(event(2, id = GroupRoomOutbox.serverEventId(id)))))
    }

    @Test
    fun `a create retry keeps its room id only for the same inputs`() {
        val first = GroupCreate.mintRoomId()
        assertTrue(first.startsWith("talaria-"))
        assertEquals(first, GroupCreate.roomId("Team", setOf("a", "b"), first, null))
        assertEquals(first, GroupCreate.roomId("Team", setOf("a", "b"), first, "Team" to setOf("a", "b")))
        assertTrue(GroupCreate.roomId("Team 2", setOf("a", "b"), first, "Team" to setOf("a", "b")) != first)
    }

    @Test
    fun `members get their mention tag, else their handle, and never a clash`() {
        fun bot(name: String, title: String?) = BotProfile(
            name, title, null, "", false, false, false, null, null, null, null, emptyList(), false,
            JsonObject(emptyMap()), null,
        )
        val rows = GroupCreate.members(listOf(bot("researcher", "Research Buddy"), bot("scout", null)))!!
        assertEquals(listOf("research-buddy", "scout"), rows.map { it["handle"] })
        assertEquals("Research Buddy", rows[0]["display_name"])
        // Two bots titled alike: the second falls back to its profile handle.
        assertEquals(
            listOf("twin", "b"),
            GroupCreate.members(listOf(bot("a", "Twin"), bot("b", "Twin")))!!.map { it["handle"] },
        )
        assertNull(GroupCreate.members(listOf(bot("user", null))))
        assertNotNull(BotSession("s", null, null, null))
    }

    @Test
    fun `room mentions style only real tags`() {
        val renamed = GroupMember("researcher", "researcher", "researcher", "Research Buddy", listOf("scout"))
        assertEquals(GroupMentions.Kind.Member, GroupMentions.classify("RESEARCHER", listOf(renamed)))
        assertEquals(GroupMentions.Kind.Member, GroupMentions.classify("scout", listOf(renamed)))
        assertEquals(GroupMentions.Kind.Member, GroupMentions.classify("research", listOf(renamed)))
        assertEquals(GroupMentions.Kind.Everyone, GroupMentions.classify("all", members))
        assertEquals(GroupMentions.Kind.User, GroupMentions.classify("user", members))
        assertNull(GroupMentions.classify("nobody", members))
        assertNull(GroupMentions.classify("example.com", members))
        val guide = GroupMember("guide", "guide", "guide", "User Guide")
        assertEquals(GroupMentions.Kind.User, GroupMentions.classify("user", listOf(guide)))
        assertEquals(GroupMentions.Kind.Member, GroupMentions.classify("user-guide", listOf(guide)))
        val tokens = { text: String -> GroupMentions.SCAN.findAll(text).map { it.groupValues[1] }.toList() }
        assertEquals(listOf("all", "user", "scout"), tokens("@all hi @user\n@scout"))
        assertEquals(emptyList(), tokens("a@all x@user name@example.com"))
    }

    @Test
    fun `the composer offers members, then everyone, never shadowing reserved tags`() {
        val shadow = GroupMember("all", "all", "all", "All")
        val human = GroupMember("user", "user", "user", "User")
        assertEquals(
            listOf("furina", "zhongli", "all"),
            GroupMentions.candidates(members + shadow + human, "Everyone").map { it.tag },
        )
    }

    @Test
    fun `the responder follows the discussion's rounds`() {
        var log = listOf(userMessage(1, "Hello everyone"))
        fun responder() = GroupRoomTurns.pendingResponder(log, members)?.memberId
        assertEquals("furina", responder())
        log = log + memberMessage(2, "furina", 0, "@zhongli, attend!") + settled(3, "furina", 0, 2)
        assertEquals("zhongli", responder())
        log = log + memberMessage(4, "zhongli", 0, "@furina, verified.") + settled(5, "zhongli", 0, 4)
        assertEquals("furina", responder())
        log = log + settled(6, "furina", 1, null)
        assertNull(responder())
    }

    @Test
    fun `mentions, a settled room and a stop end the wait`() {
        val log = listOf(userMessage(1, "@zhongli what do you think?"))
        assertEquals("zhongli", GroupRoomTurns.pendingResponder(log, members)?.memberId)
        val done = log + event(
            2,
            kind = "room.activity",
            payload = """{"status":"settled","thread_id":"main","discussion_event_id":"user:d1"}""",
        )
        assertNull(GroupRoomTurns.pendingResponder(done, members))
        assertNull(GroupRoomTurns.pendingResponder(log + event(2, kind = "room.stop_requested"), members))
        // The gateway's routing has no boundary rule: a@zhongli still routes.
        assertEquals(listOf("zhongli"), GroupRoomTurns.resolve(listOf("a@zhongli"), members, false).map { it.memberId })
    }

    @Test
    fun `ten member messages bound a discussion, and a deferred member is still the one awaited`() {
        val three = members + GroupMember("nahida", "nahida", "nahida", "Nahida")
        var capped = listOf(userMessage(1, "@all hello"))
        var seq = 2
        repeat(10) { index ->
            val member = listOf("furina", "zhongli", "nahida")[index % 3]
            capped = capped + memberMessage(seq, member, 0, "@all again") + settled(seq + 1, member, 0, seq)
            seq += 2
        }
        assertNull(GroupRoomTurns.pendingResponder(capped, three))
        val deferred = event(
            2,
            kind = "turn.deferred",
            payload = """{"thread_id":"main","discussion_event_id":"user:d1","member_id":"furina","round_index":0}""",
        )
        assertEquals(
            "furina",
            GroupRoomTurns.pendingResponder(listOf(userMessage(1, "hi"), deferred), members)?.memberId,
        )
        assertFalse(GroupRoomTurns.isVisible(deferred))
    }

    @Test
    fun `only passed, failed and stopped turns earn a row`() {
        assertFalse(GroupRoomTurns.isVisible(settled(3, "furina", 0, 2)))
        assertTrue(GroupRoomTurns.isVisible(settled(6, "furina", 1, null)))
        assertTrue(GroupRoomTurns.isVisible(event(4, kind = "turn.failed")))
        assertFalse(GroupRoomTurns.isVisible(event(5, kind = "turn.heartbeat")))
        val upper = event(7, kind = "turn.settled", payload = """{"member_id":"Zhongli","passed":true}""")
        assertEquals("zhongli", GroupRoomTurns.member(upper, members)?.memberId)
    }

    @Test
    fun `a room message's leading file lines become files`() {
        val parts = GroupMessageParts.of("@file:/srv/rooms/a/note.txt\n@file:\"b c.pdf\"\n\nRead these")
        assertEquals(listOf("note.txt", "b c.pdf"), parts.files)
        assertEquals("Read these", parts.text)
        assertEquals(GroupMessageParts("Just text", emptyList()), GroupMessageParts.of("Just text"))
        assertEquals("@file:a\n@file:b\n\nhi", GroupMessageParts.compose(" hi ", listOf("@file:a", "@file:b")))
        assertEquals("hi", GroupMessageParts.compose("hi", emptyList()))
    }
}
