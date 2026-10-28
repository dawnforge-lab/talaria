package lab.dawnforge.hermes.core.data.groups

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import lab.dawnforge.hermes.core.data.bots.BotMentions
import lab.dawnforge.hermes.core.data.bots.BotProfile
import lab.dawnforge.hermes.core.data.bots.MentionCandidate

/*
 * Hermes' hosted group chats (`groups.*`, protocol version 2), as iOS reads them: rooms of 2–6 bots
 * that the gateway's driver runs on its own, a log the client polls, and the client's own
 * presentation of whose turn it is. Decoding is defensive: an unknown event kind is a neutral row,
 * a row without its identity is dropped, and nothing is attributed to the wrong member.
 */

/** What `groups.capabilities` says this gateway can do. */
data class GroupCapabilities(
    val protocolVersion: Int,
    /** The gateway runs rooms itself, without this phone. */
    val driverReady: Boolean,
    val methods: Set<String>,
    val maxLogLimit: Int,
) {
    fun supports(method: String): Boolean = method in methods

    /** Lists, makes, reads, sends to and disbands rooms: what a group chat needs at all. */
    val foundation: Boolean get() = methods.containsAll(REQUIRED)

    companion object {
        val REQUIRED =
            setOf("groups.list", "groups.create", "groups.state", "groups.send", "groups.log", "groups.disband")
        private const val DEFAULT_LOG_LIMIT = 500

        /** Null when it isn't a capabilities answer: a failure, never "no groups". */
        fun parse(json: JsonElement?): GroupCapabilities? {
            val reply = json as? JsonObject ?: return null
            val methods = (reply["methods"] as? JsonArray).orEmpty().mapNotNullTo(HashSet()) { it.text() }
            if (methods.isEmpty()) return null
            return GroupCapabilities(
                protocolVersion = reply.int("protocol_version") ?: 0,
                driverReady = reply.flag("driver") == true,
                methods = methods,
                maxLogLimit = reply.int("max_log_limit") ?: DEFAULT_LOG_LIMIT,
            )
        }
    }
}

/** One member of a room. Its name is never its identity: two members may share one. */
data class GroupMember(
    val memberId: String?,
    val profile: String?,
    val handle: String?,
    val displayName: String?,
    val previousNames: List<String> = emptyList(),
) {
    /** The member id, else the profile, else the handle. */
    val identityKey: String
        get() = listOf(memberId, profile, handle).firstNotNullOfOrNull {
            it?.trim()?.takeIf(String::isNotEmpty)
        }.orEmpty()

    /** The id the driver stamps on its turns (`member_id`), lowercased. */
    val routingId: String get() = (memberId ?: identityKey).lowercase()

    /** Its name for captions; null when it has none at all. */
    val label: String?
        get() = listOf(displayName, handle, profile, memberId)
            .firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }

    companion object {
        fun parse(json: JsonElement?): GroupMember? {
            val row = json as? JsonObject ?: return null
            return GroupMember(
                memberId = row.text("member_id"),
                profile = row.text("profile"),
                handle = row.text("handle"),
                displayName = row.text("display_name"),
                previousNames = (row["previous_names"] as? JsonArray).orEmpty()
                    .mapNotNull { it.text()?.takeIf(String::isNotEmpty) },
            )
        }
    }
}

/** Who a log event is from: `user`, `member`, `gateway` or `system` (an unknown kind stays as sent). */
data class GroupActor(val kind: String, val id: String, val profile: String?, val displayName: String?) {
    /** A member's name, matched by its id or profile; a bare display name never decides who spoke. */
    fun label(members: List<GroupMember>): String {
        val key = id.trim()
        if (kind == "member" && key.isNotEmpty()) {
            members.firstOrNull {
                it.memberId.equals(key, ignoreCase = true) || it.profile.equals(key, ignoreCase = true)
            }?.let { member ->
                listOf(member.displayName, member.handle, member.profile, member.memberId)
                    .firstOrNull { !it.isNullOrEmpty() }
                    ?.let { return it }
            }
        }
        if (!profile.isNullOrEmpty() && kind == "member") return profile
        return displayName.orEmpty()
    }

    companion object {
        fun parse(json: JsonElement?): GroupActor {
            val row = json as? JsonObject ?: JsonObject(emptyMap())
            return GroupActor(
                kind = row.text("kind") ?: "unknown",
                id = row.text("id").orEmpty(),
                profile = row.text("profile"),
                displayName = row.text("display_name"),
            )
        }
    }
}

/** One room-log event. The kind stays raw: the gateway adds kinds this app predates. */
data class GroupEvent(
    val roomId: String,
    val seq: Int,
    val eventId: String,
    val kind: String,
    val actor: GroupActor,
    val payload: JsonObject,
    val createdAt: Double,
) {
    val key: String get() = eventId.ifEmpty { "seq-$seq" }
    val isUserMessage: Boolean get() = kind == "message.user"
    val isMemberMessage: Boolean get() = kind == "message.member"
    val isMessage: Boolean get() = isUserMessage || isMemberMessage

    /** A message's text. */
    val text: String? get() = if (isMessage) payload.text("text") else null

    val threadId: String? get() = payload.text("thread_id")

    companion object {
        fun parse(json: JsonElement?): GroupEvent? {
            val row = json as? JsonObject ?: return null
            val roomId = row.text("room_id") ?: return null
            val seq = row.int("seq")?.takeIf { it >= 1 } ?: return null
            return GroupEvent(
                roomId = roomId,
                seq = seq,
                eventId = row.text("event_id").orEmpty(),
                kind = row.text("kind") ?: "unknown",
                actor = GroupActor.parse(row["actor"]),
                payload = row["payload"] as? JsonObject ?: JsonObject(emptyMap()),
                createdAt = row.number("created_at") ?: 0.0,
            )
        }
    }
}

/** A hosted room, as `groups.list` and `groups.state` give it. */
data class GroupRoom(
    val roomId: String,
    val name: String,
    val members: List<GroupMember>,
    val revision: Int,
    val updatedAt: Double,
    /** Set once it's disbanded; a disbanded room never reopens. */
    val disbandedAt: Double?,
    /** The highest seq its log ever gave. */
    val latestSeq: Int?,
) {
    val isDisbanded: Boolean get() = disbandedAt != null

    companion object {
        fun parse(json: JsonElement?): GroupRoom? {
            val row = json as? JsonObject ?: return null
            val roomId = row.text("room_id")?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return GroupRoom(
                roomId = roomId,
                name = row.text("name") ?: roomId,
                members = (row["members"] as? JsonArray).orEmpty().mapNotNull(GroupMember::parse),
                revision = row.int("revision") ?: 0,
                updatedAt = row.number("updated_at") ?: 0.0,
                disbandedAt = row.number("disbanded_at"),
                latestSeq = row.int("latest_seq"),
            )
        }
    }
}

/** A member's tool call the room's driver holds until someone answers it (`groups.approve`). */
data class GroupApproval(
    val memberId: String,
    val taskId: String,
    val executionGeneration: Int,
    val requestId: String,
    val command: String,
    val description: String,
    /** Rooms answer `once` or `deny` only. */
    val choices: List<String>,
) {
    val id: String get() = "$memberId|$taskId|$executionGeneration|$requestId"

    fun allows(choice: String): Boolean = choice in choices

    companion object {
        fun parse(action: JsonObject): GroupApproval? {
            if (action.text("kind") != "approval") return null
            val memberId = action.text("member_id")?.takeIf(String::isNotEmpty) ?: return null
            val taskId = action.text("task_id")?.takeIf(String::isNotEmpty) ?: return null
            val generation = action.int("execution_generation") ?: return null
            val approval = action["approval"] as? JsonObject ?: JsonObject(emptyMap())
            val requestId = (action.text("request_id") ?: approval.text("request_id"))
                ?.takeIf(String::isNotEmpty) ?: return null
            val offered = (approval["choices"] as? JsonArray).orEmpty()
                .mapNotNull { it.text() }
                .filter { it == ONCE || it == DENY }
            return GroupApproval(
                memberId,
                taskId,
                generation,
                requestId,
                approval.text("command").orEmpty(),
                approval.text("description").orEmpty(),
                offered.ifEmpty { listOf(ONCE, DENY) },
            )
        }

        const val ONCE = "once"
        const val DENY = "deny"
    }
}

/** The room's driver as `groups.state` reports it. */
data class GroupDriverStatus(
    val running: Boolean,
    val working: Boolean,
    /** Waiting on something the room owes it, such as an approval. */
    val blocked: Boolean,
    val approvals: List<GroupApproval>,
) {
    companion object {
        fun parse(json: JsonElement?): GroupDriverStatus? {
            val row = json as? JsonObject ?: return null
            return GroupDriverStatus(
                running = row.flag("running") == true,
                working = row.flag("working") == true,
                blocked = row.flag("blocked") == true,
                approvals = (row["pending_actions"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.let(GroupApproval::parse) },
            )
        }
    }
}

/** One `groups.log` page. */
data class GroupLogPage(val events: List<GroupEvent>, val latestSeq: Int, val hasMore: Boolean) {
    companion object {
        fun parse(json: JsonElement?): GroupLogPage? {
            val reply = json as? JsonObject ?: return null
            val latest = reply.int("latest_seq") ?: return null
            return GroupLogPage(
                events = (reply["events"] as? JsonArray).orEmpty().mapNotNull(GroupEvent::parse),
                latestSeq = latest,
                hasMore = reply.flag("has_more") == true,
            )
        }
    }
}

/** What `groups.send` answered: the event it filed, and whether the driver took it on. */
data class GroupSendResult(val event: GroupEvent, val driverStarted: Boolean)

/**
 * A room's log as the client holds it: one ordered list without duplicates. The cursor moves only
 * over accepted events. A page that skips unseen events raises [hasGap], and the room is read
 * again instead of showing a hole.
 */
data class GroupRoomReplay(
    val roomId: String,
    val events: List<GroupEvent> = emptyList(),
    val cursor: Int = 0,
    val hasGap: Boolean = false,
) {
    /** The `since_seq` of the next `groups.log`. */
    val sinceSeq: Int get() = cursor

    /** The opening tail: where it starts is by design, never a gap. */
    fun initial(page: GroupLogPage): GroupRoomReplay {
        val incoming = page.events.filter { it.roomId == roomId }.sortedBy { it.seq }
        return copy(events = incoming, cursor = incoming.lastOrNull()?.seq ?: 0, hasGap = false)
    }

    /** Whether [page] jumps past events this replay hasn't seen. */
    fun skipsAhead(page: GroupLogPage): Boolean {
        var expected = cursor + 1
        for (seq in page.events.filter { it.roomId == roomId && it.seq > cursor }.map { it.seq }.toSortedSet()) {
            if (seq != expected) return true
            expected++
        }
        return false
    }

    fun adopt(page: GroupLogPage): GroupRoomReplay {
        var events = events
        var cursor = cursor
        var gap = hasGap
        for (event in page.events.filter { it.roomId == roomId && it.seq > this.cursor }.sortedBy { it.seq }) {
            if (event.seq <= cursor) continue
            if (event.seq > cursor + 1) gap = true
            events = events + event
            cursor = event.seq
        }
        return copy(events = events, cursor = cursor, hasGap = gap)
    }
}

/**
 * The room composer's retry key. `groups.send` deduplicates on the client's event id, so one
 * message keeps its id until a send is accepted: a retry of the same text reuses it, and other
 * text waits until the first is settled, so nothing is lost or sent twice.
 */
data class GroupRoomOutbox(val pending: Pending? = null) {
    data class Pending(val text: String, val eventId: String)

    fun accepts(text: String): Boolean = pending == null || pending.text == text

    /** The send of [text]: the pending one when it's a retry, else a new id. */
    fun begin(text: String): GroupRoomOutbox = if (pending != null) this else GroupRoomOutbox(Pending(text, mintId()))

    fun accept(eventId: String): GroupRoomOutbox = if (pending?.eventId == eventId) GroupRoomOutbox() else this

    /** Settled when [events] hold the message the gateway filed for it (a send whose answer was lost). */
    fun settled(events: List<GroupEvent>): Boolean {
        val pending = pending ?: return false
        val serverId = serverEventId(pending.eventId)
        return events.any { it.eventId == serverId }
    }

    companion object {
        fun mintId(): String = "talaria-" + UUID.randomUUID().toString().lowercase()

        /** The id the gateway files a user message under: `user:` and the SHA-256 of the client's id. */
        fun serverEventId(clientEventId: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(clientEventId.trim().toByteArray())
            return "user:" + digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Making a room: its id (also the create's retry key) and its members' rows. */
object GroupCreate {
    const val MIN_MEMBERS = 2
    const val MAX_MEMBERS = 6

    fun mintRoomId(): String = "talaria-" + UUID.randomUUID().toString().lowercase()

    /** The same name and members keep the id, so a retry finds the room; edited ones get a new id. */
    fun roomId(name: String, members: Set<String>, current: String, attempted: Pair<String, Set<String>>?): String =
        if (attempted == null || attempted == (name to members)) current else mintRoomId()

    /**
     * Each bot's row: its profile is its identity, and its handle is its mention tag (else its profile
     * handle). Null when two bots would claim one handle, or a handle the room reserves.
     */
    fun members(bots: List<BotProfile>): List<Map<String, String>>? {
        val used = hashSetOf("all", "everyone", "user")
        return bots.map { bot ->
            var handle = BotMentions.tag(bot)
            if (handle.lowercase() in used) handle = BotMentions.handle(bot)
            if (!BotMentions.isValidToken(handle) || !used.add(handle.lowercase())) return null
            mapOf("member_id" to bot.name, "profile" to bot.name, "handle" to handle, "display_name" to bot.label)
        }
    }
}

/** How a room reads its log: whose turn it is and which events earn a row (presentation only). */
object GroupRoomTurns {
    private const val MAX_ROUNDS = 3
    private const val MAX_MESSAGES = 10
    private val TERMINAL = setOf("turn.settled", "turn.failed", "turn.cancelled")
    private val ROUTING = Regex("@([A-Za-z0-9][A-Za-z0-9._:-]*)")

    /** A member turn that posted settles silently (its message says so); other `turn.*` are bookkeeping. */
    fun isVisible(event: GroupEvent): Boolean = when (event.kind) {
        "turn.settled" -> event.payload.flag("passed") == true
        "turn.failed", "turn.cancelled" -> true
        else -> !event.kind.startsWith("turn.")
    }

    /** The member a turn event is about. */
    fun member(event: GroupEvent, members: List<GroupMember>): GroupMember? =
        event.payload.text("member_id")?.let { member(it, members) }

    fun member(id: String, members: List<GroupMember>): GroupMember? {
        val key = id.trim().lowercase()
        return members.firstOrNull { it.routingId == key } ?: members.firstOrNull { it.profile.equals(key, true) }
    }

    /**
     * The member whose turn the driver runs (or runs next) for the room's open discussion, or null
     * when the log says the room is idle: the gateway's discussion policy replayed on the log.
     */
    fun pendingResponder(events: List<GroupEvent>, members: List<GroupMember>): GroupMember? {
        if (members.isEmpty()) return null
        val discussion = pendingDiscussion(events) ?: return null
        val thread = discussion.threadId.orEmpty()
        val committed = events.filter { it.kind == "turn.settled" }.mapNotNullTo(HashSet()) {
            it.payload.text("message_event_id")
        }
        val threadMessages = events.filter {
            it.threadId.orEmpty() == thread && (it.isUserMessage || (it.isMemberMessage && it.eventId in committed))
        }
        val discussionMessages = threadMessages.filter { it.seq >= discussion.seq }
        val memberMessages = threadMessages.filter {
            it.isMemberMessage && it.payload.text("discussion_event_id") == discussion.eventId
        }
        if (memberMessages.size >= MAX_MESSAGES) return null
        val terminals = events.filter {
            it.kind in TERMINAL && it.payload.text("discussion_event_id") == discussion.eventId
        }.mapNotNullTo(HashSet()) { event ->
            val round = event.payload.int("round_index") ?: return@mapNotNullTo null
            val member = event.payload.text("member_id") ?: return@mapNotNullTo null
            "$round|${member.lowercase()}"
        }
        for (round in 0 until MAX_ROUNDS) {
            val responders = if (round == 0) {
                resolve(listOf(discussion.payload.text("text").orEmpty()), members, everyoneByDefault = true)
            } else {
                unaddressed(discussionMessages, members)
            }
            rotate(responders, round).firstOrNull { "$round|${it.routingId}" !in terminals }?.let { return it }
            if (memberMessages.none { it.payload.int("round_index") == round }) return null
        }
        return null
    }

    /** The oldest thread's latest user message not stopped, settled or bounded. */
    private fun pendingDiscussion(events: List<GroupEvent>): GroupEvent? {
        val stoppedThrough = events.filter { it.kind == "room.stop_requested" }.maxOfOrNull { it.seq } ?: 0
        val completed = events.filter {
            it.kind == "room.activity" && it.payload.text("status").let { s -> s == "settled" || s == "bounded" }
        }.mapNotNullTo(HashSet()) { it.payload.text("discussion_event_id") }
        val latest = HashMap<String, GroupEvent>()
        for (event in events.filter { it.isUserMessage }) {
            val thread = event.threadId.orEmpty()
            val existing = latest[thread]
            if (existing == null || existing.seq <= event.seq) latest[thread] = event
        }
        return latest.values.sortedBy { it.seq }.firstOrNull { it.seq > stoppedThrough && it.eventId !in completed }
    }

    /** Exact handles from the frozen roster; `@all`, `@everyone` (or none, when [everyoneByDefault]) is everyone. */
    fun resolve(texts: List<String>, members: List<GroupMember>, everyoneByDefault: Boolean): List<GroupMember> {
        val handles = members.mapNotNullTo(HashSet()) { it.handle?.lowercase() }
        val mentioned = HashSet<String>()
        var everyone = false
        for (text in texts) {
            for (match in ROUTING.findAll(text)) {
                val handle = match.groupValues[1].lowercase()
                if (handle == "all" ||
                    handle == "everyone"
                ) {
                    everyone = true
                } else if (handle in handles) {
                    mentioned += handle
                }
            }
        }
        if (everyone || (everyoneByDefault && mentioned.isEmpty())) return members
        return members.filter { it.handle?.lowercase().orEmpty() in mentioned }
    }

    /** Peers a member cited who haven't posted since. */
    private fun unaddressed(messages: List<GroupEvent>, members: List<GroupMember>): List<GroupMember> {
        val citedAt = HashMap<String, Int>()
        val lastPostAt = HashMap<String, Int>()
        for (event in messages.filter { it.isMemberMessage }) {
            val speaker = event.payload.text("member_id")?.lowercase().orEmpty()
            lastPostAt[speaker] = event.seq
            for (member in resolve(listOf(event.payload.text("text").orEmpty()), members, false)) {
                if (member.routingId != speaker) citedAt[member.routingId] = event.seq
            }
        }
        return members.filter { member ->
            val cited = citedAt[member.routingId] ?: return@filter false
            (lastPostAt[member.routingId] ?: 0) <= cited
        }
    }

    private fun rotate(members: List<GroupMember>, round: Int): List<GroupMember> {
        if (members.isEmpty()) return members
        val shift = round % members.size
        return members.drop(shift) + members.take(shift)
    }
}

/** @mentions in a room: what a tag means (for styling) and what the composer offers. */
object GroupMentions {
    /** A tag at the start or after a space, as upstream styles them. */
    val SCAN = Regex("(?<!\\S)@([a-z0-9][a-z0-9._:-]*)", RegexOption.IGNORE_CASE)

    enum class Kind { Member, Everyone, User }

    /** What `@token` means in the room, or null when it's plain text. */
    fun classify(token: String, members: List<GroupMember>): Kind? {
        val handle = token.lowercase()
        if (handle == "user") return Kind.User
        if (handle == "all" || handle == "everyone") return Kind.Everyone
        val collapsed = handle.filterNot { it in "._-" }
        for (member in members) {
            val names = listOfNotNull(member.handle, member.memberId, member.profile, member.displayName) +
                member.previousNames
            for (name in names) {
                val lowered = name.lowercase()
                if (lowered == handle || lowered == collapsed) return Kind.Member
                if (handle in BotMentions.nameForms(name)) return Kind.Member
                if (lowered.split(' ').firstOrNull() == handle) return Kind.Member
            }
            val stripped = names.firstNotNullOfOrNull { name ->
                name.filterNot { it in "._-" }.lowercase().takeIf(String::isNotEmpty)
            }
            if (stripped != null && stripped == collapsed) return Kind.Member
        }
        return null
    }

    /** The members with a handle, then `@all` ([everyone] is its title). */
    fun candidates(members: List<GroupMember>, everyone: String): List<MentionCandidate> {
        val seen = hashSetOf("all", "everyone", "user")
        return members.mapNotNull { member ->
            val handle = member.handle?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            if (!seen.add(handle.lowercase())) return@mapNotNull null
            MentionCandidate(handle, member.label ?: handle)
        } + MentionCandidate("all", everyone)
    }
}

private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.text(key: String) = this[key]?.text()

private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

/** A whole number only: 1.5 or "2" isn't one. */
private fun JsonObject.int(key: String): Int? {
    val value = (this[key] as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
    value.longOrNull?.let { return it.toInt() }
    val number = value.doubleOrNull ?: return null
    return if (number % 1.0 == 0.0) number.toInt() else null
}

/** A room message's files and text: the `@file:` lines it opens with (how files reach a room), then the rest. */
data class GroupMessageParts(val text: String, val files: List<String>) {
    companion object {
        fun of(source: String): GroupMessageParts {
            val lines = source.replace("\r\n", "\n").lines().toMutableList()
            val files = mutableListOf<String>()
            while (lines.isNotEmpty()) {
                val first = lines.first().trim()
                if (first.isEmpty() && files.isNotEmpty()) {
                    lines.removeAt(0)
                    continue
                }
                if (!first.startsWith(FILE)) break
                val path = first.removePrefix(FILE).trim().trim('`', '"', '\'')
                if (path.isEmpty() || (' ' in path && first.removePrefix(FILE).trim().first() !in "`\"'")) break
                files += path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }
                lines.removeAt(0)
            }
            return GroupMessageParts(lines.joinToString("\n").trim(), files)
        }

        /** The text a room gets for a message with files: their references, a blank line, then what was typed. */
        fun compose(text: String, references: List<String>): String =
            if (references.isEmpty()) text else (references + "" + text.trim()).joinToString("\n").trim()

        private const val FILE = "@file:"
    }
}
