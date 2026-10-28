package lab.dawnforge.hermes.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lab.dawnforge.hermes.core.gateway.GatewayEvent
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.model.Delivery
import lab.dawnforge.hermes.core.model.NoteKind
import lab.dawnforge.hermes.core.model.NoticeKind
import lab.dawnforge.hermes.core.model.Reaction
import lab.dawnforge.hermes.core.model.ToolStatus

/** How full the model's context window is, from Hermes' usage ticks. */
data class ContextUsage(val used: Long, val max: Long, val percent: Double)

/**
 * One chat's transcript: the stored [history], and on top of it the live [turn] built from the
 * socket's events. When a turn ends ([turnsEnded] moves), the screen reloads the newest history
 * and hands it to [withHistory], which replaces the live rows with their stored copies.
 */
data class ChatState(
    val history: List<ChatItem> = emptyList(),
    /** Rows streamed since the history was last loaded. */
    val turn: List<ChatItem> = emptyList(),
    /** Whether the agent is working on a turn. */
    val running: Boolean = false,
    /** Hermes' passing status line ("waiting for the model…", "compacting…"); not part of the transcript. */
    val status: String? = null,
    val title: String? = null,
    val context: ContextUsage? = null,
    /** The model the next turn uses, from `session.info`; it changes when the chat is switched. */
    val model: ChatModel? = null,
    /** Counts turns started, so live rows get keys of their own. */
    val turnSerial: Int = 0,
    /** Counts turns ended: each one asks for the history to be reloaded. */
    val turnsEnded: Int = 0,
    /** Whether this turn has shown reasoning, so the copy on `message.complete` is not shown twice. */
    private val turnHadReasoning: Boolean = false,
    /** A turn has started and not yet been counted in [turnsEnded]. */
    private val turnOpen: Boolean = false,
    /** Messages typed here, waiting in the app for the running turn to end; always shown last. */
    val queued: List<ChatItem.User> = emptyList(),
    /** The stored row id of each message sent from here, by its key, once Hermes said it. */
    private val sentRows: Map<String, String> = emptyMap(),
    /** Notes only this phone shows, each with the key of the row it followed. */
    private val notes: List<Pair<String?, ChatItem.Note>> = emptyList(),
    /** The sub-agents this chat's turns delegated to, oldest first. */
    val agents: List<DelegateAgent> = emptyList(),
    /** The chat's fast mode and approvals, from `session.info`. */
    val settings: ChatSettings? = null,
    /** The chat's working folder on the host, from `session.info`; null until Hermes names one. */
    val cwd: String? = null,
) {
    val items: List<ChatItem>
        get() {
            val rows = if (turn.isEmpty()) history else history + turn
            return placeNotes(rows) + queued
        }

    /** Sub-agents queued or working. */
    val activeAgents: Int get() = agents.count(DelegateAgent::isActive)

    /**
     * Each note after the row it followed. A note whose row is gone (a live row the stored copy
     * replaced) goes at the end, where it was written.
     */
    private fun placeNotes(rows: List<ChatItem>): List<ChatItem> {
        if (notes.isEmpty()) return rows
        val keys = rows.mapTo(HashSet()) { it.key }
        val byRow = notes.filter { it.first in keys }.groupBy({ it.first }, { it.second })
        val first = notes.filter { it.first == null }.map { it.second }
        val last = notes.filter { it.first != null && it.first !in keys }.map { it.second }
        return buildList {
            addAll(first)
            for (row in rows) {
                add(row)
                byRow[row.key]?.let(::addAll)
            }
            addAll(last)
        }
    }

    // ---- Notes and sub-agents ------------------------------------------------------------------

    /** A note shown after the newest row, kept when the history is reloaded. */
    fun withNote(kind: NoteKind, title: String?, text: String): ChatState {
        val after = (history + turn).lastOrNull()?.key
        val note = ChatItem.Note("note-${notes.size}", kind, title, text)
        return copy(notes = notes + (after to note))
    }

    /**
     * The sub-agents Hermes still has (`subagent.list`): those it lists are updated, and one shown
     * working that it no longer lists has finished.
     */
    fun withListedAgents(listed: List<DelegateAgent>): ChatState {
        val byId = listed.associateBy(DelegateAgent::id)
        val known = agents.map { agent ->
            val fresh = byId[agent.subagentId ?: agent.id]
            when {
                fresh != null -> fresh.copy(
                    id = agent.id,
                    summary = agent.summary,
                    lastLine =
                        agent.lastLine ?: fresh.lastLine,
                )

                agent.isActive && agent.subagentId != null -> agent.copy(status = AgentStatus.Completed)

                else -> agent
            }
        }
        val added = listed.filter { fresh -> agents.none { (it.subagentId ?: it.id) == fresh.id } }
        return copy(agents = known + added)
    }

    /**
     * The row [key]'s reactions, as the user just picked them or Hermes answered; [rowId] when
     * Hermes named the stored row (a live reply learns its id this way).
     */
    fun withReactions(key: String, reactions: List<Reaction>, rowId: Long? = null): ChatState {
        fun update(item: ChatItem): ChatItem = when {
            item.key != key -> item
            item is ChatItem.Assistant -> item.copy(reactions = reactions, rowId = rowId ?: item.rowId)
            item is ChatItem.User -> item.copy(reactions = reactions, rowId = rowId ?: item.rowId)
            else -> item
        }
        return copy(history = history.map(::update), turn = turn.map(::update))
    }

    /**
     * The agent reacted (`message.reaction`): to the row [rowId], or, for a message not stored
     * yet, to the newest one of [role].
     */
    private fun agentReaction(payload: JsonObject): ChatState {
        val reactions = parseReactions(payload["reactions"] as? JsonArray)
        val rowId = payload.long("row_id")
        val rows = history + turn
        val target = rows.lastOrNull { item ->
            when (item) {
                is ChatItem.Assistant -> item.rowId == rowId
                is ChatItem.User -> item.rowId == rowId
                else -> false
            }
        } ?: rows.lastOrNull { item ->
            when (payload.string("role")) {
                "assistant" -> item is ChatItem.Assistant && item.rowId == null
                "user" -> item is ChatItem.User && item.rowId == null
                else -> false
            }
        } ?: return this
        return withReactions(target.key, reactions, rowId)
    }

    /** One sub-agent's new state, after steering or stopping it. */
    fun withAgent(agent: DelegateAgent): ChatState = copy(agents = agents.map { if (it.id == agent.id) agent else it })

    /**
     * The newest history, loaded when [loadedAtTurnsEnded] turns had ended. It replaces the live
     * rows unless a turn has started since, whose rows are not stored yet. Messages sent from here
     * stay until the history holds them; one that is still sending or failed always stays.
     *
     * A history loaded while a turn runs (after a reconnect, say) already holds the parts of the
     * turn Hermes has stored: a sealed reply segment, its reasoning, a tool call. The live copies of
     * those go, so nothing shows twice; a tool's live result is kept on its stored card.
     */
    fun withHistory(items: List<ChatItem>, loadedAtTurnsEnded: Int = turnsEnded): ChatState {
        val settled = !running && loadedAtTurnsEnded == turnsEnded
        val stored = items.mapTo(HashSet()) { it.key }
        val storedTurn = items.subList(items.indexOfLast { it is ChatItem.User } + 1, items.size)
        fun storedCopy(item: ChatItem): Boolean = when (item) {
            is ChatItem.Tool -> item.key in stored

            is ChatItem.Assistant ->
                !item.streaming &&
                    storedTurn.any { it is ChatItem.Assistant && it.text == item.text.trim() }

            is ChatItem.Reasoning ->
                !item.streaming &&
                    storedTurn.any { it is ChatItem.Reasoning && it.text == item.text.trim() }

            else -> false
        }
        val kept = turn.filter { item ->
            when {
                item is ChatItem.User && item.delivery != null -> true
                item is ChatItem.User && sentRows[item.key]?.let { "row-$it" in stored } == true -> false
                settled -> false
                else -> !storedCopy(item)
            }
        }
        // A call stored before its result: the result the stream already brought wins.
        val liveTools = turn.filterIsInstance<ChatItem.Tool>().filter { it.status != ToolStatus.Running }
            .associateBy { it.key }
        val merged = if (liveTools.isEmpty()) {
            items
        } else {
            items.map { item ->
                val live = liveTools[item.key]
                if (item is ChatItem.Tool && live != null && item.output == null) live else item
            }
        }
        return copy(
            history = merged,
            turn = kept,
            sentRows = sentRows.filterKeys { key -> kept.any { it.key == key } },
        )
    }

    // ---- Messages typed here -------------------------------------------------------------------

    /** A message on its way: shown at once, after the live rows. */
    fun withOutgoing(message: ChatItem.User): ChatState =
        copy(turn = turn.map(::settled) + message, queued = queued.filter { it.key != message.key })

    /** A message held until the running turn ends. */
    fun withQueued(message: ChatItem.User): ChatState = copy(queued = queued + message)

    /** A queued message taken back (to edit it, or not to send it). */
    fun withoutQueued(key: String): ChatState = copy(queued = queued.filter { it.key != key })

    /**
     * Where the message [key] is now: [delivery] null once Hermes has it, with its stored
     * [rowId] when Hermes said it. [isSteer] marks one that went into the running turn.
     */
    fun withDelivery(key: String, delivery: Delivery?, rowId: String? = null, isSteer: Boolean = false): ChatState {
        val rows = turn.map { item ->
            if (item is ChatItem.User && item.key == key) {
                item.copy(delivery = delivery, isSteer = item.isSteer || isSteer)
            } else {
                item
            }
        }
        return copy(turn = rows, sentRows = if (rowId != null) sentRows + (key to rowId) else sentRows)
    }

    /** A message that did not get through, taken out (it goes back to the composer). */
    fun withoutOutgoing(key: String): ChatState = copy(turn = turn.filter { it.key != key })

    /**
     * A turn that was already running when the chat was opened. Hermes' snapshot holds everything
     * the turn has written so far ([replySoFar], every segment run together); the history already
     * holds the segments it sealed. What is left after those is the segment being written now.
     */
    fun withRunningTurn(replySoFar: String?): ChatState {
        val start = copy(running = true, turnSerial = turnSerial + 1, turnHadReasoning = false, turnOpen = true)
        var rest = replySoFar.orEmpty()
        val storedTurn = history.subList(history.indexOfLast { it is ChatItem.User } + 1, history.size)
        for (segment in storedTurn.filterIsInstance<ChatItem.Assistant>()) {
            val at = rest.indexOf(segment.text)
            if (at >= 0) rest = rest.substring(at + segment.text.length)
        }
        val text = rest.trimStart()
        if (text.isEmpty()) return start
        return start.copy(turn = turn + ChatItem.Assistant(start.liveKey(turn.size), text, streaming = true))
    }

    /**
     * Whether a turn is running, from a fresh snapshot (after a reconnect whose replay could not
     * cover everything): a turn that ended meanwhile ends here too.
     */
    fun withRunning(running: Boolean): ChatState = when {
        running == this.running -> this
        running -> copy(running = true)
        else -> sessionInfo(JsonObject(mapOf("running" to JsonPrimitive(false))))
    }

    /** Folds one event of this chat into the transcript. Events of other sessions must not come here. */
    fun reduce(event: GatewayEvent): ChatState {
        val payload = event.payload ?: EMPTY
        return when (event.type) {
            "message.start" -> copy(
                turn = turn.map(::settled),
                running = true,
                status = null,
                turnSerial = turnSerial + 1,
                turnHadReasoning = false,
                turnOpen = true,
            )

            "message.delta" -> payload.string("text")?.takeIf(String::isNotEmpty)?.let(::appendText) ?: this

            "reasoning.delta" -> payload.string("text")?.takeIf(String::isNotEmpty)?.let(::appendReasoning) ?: this

            "reasoning.available" -> payload.string("text")?.takeIf(String::isNotBlank)?.let(::replaceReasoning) ?: this

            "message.interim" -> payload.string("text")?.let(::sealText) ?: this

            "tool.start" -> startTool(payload)

            "tool.complete" -> completeTool(payload)

            "message.complete" -> complete(payload)

            "error" -> copy(
                turn = turn.map(::settled) + notice(NoticeKind.Failed, payload.string("message")),
                status = null,
                turnsEnded = if (turnOpen) turnsEnded + 1 else turnsEnded,
                turnOpen = false,
            )

            "session.info" -> sessionInfo(payload)

            "message.reaction" -> agentReaction(payload)

            "background.complete" -> payload.string("text")?.takeIf(String::isNotBlank)?.let {
                withNote(NoteKind.Background, payload.string("task_id"), it.trim())
            } ?: this

            // Hermes doesn't store these: the phone keeps them as notes, as the iOS app does.
            "review.summary" -> payload.string("text")?.takeIf(String::isNotBlank)?.let {
                withNote(NoteKind.Review, null, reviewText(it))
            } ?: this

            "btw.complete" -> payload.string("text")?.takeIf(String::isNotBlank)?.let {
                withNote(NoteKind.SideQuestion, payload.string("question"), it.trim())
            } ?: this

            "subagent.spawn_requested", "subagent.start", "subagent.progress", "subagent.thinking",
            "subagent.tool", "subagent.complete",
            -> DelegateAgent.fromEvent(event.type, payload, agents.associateBy(DelegateAgent::id))?.let { agent ->
                if (agents.any { it.id == agent.id }) withAgent(agent) else copy(agents = agents + agent)
            } ?: this

            "session.title" -> payload.string("title")?.trim()?.takeIf(String::isNotEmpty)?.let { copy(title = it) }
                ?: this

            "session.usage" -> payload.obj("usage")?.let(::usage) ?: this

            "thinking.delta" -> copy(
                status = payload.string("text")?.trim()?.lines()?.lastOrNull()?.takeIf(String::isNotEmpty),
            )

            "status.update" -> when (payload.string("kind")) {
                "compacting", "status", "lifecycle" -> copy(
                    status = payload.string("text")?.trim()?.takeIf(String::isNotEmpty),
                )

                else -> this
            }

            else -> this
        }
    }

    // ---- Text segments -------------------------------------------------------------------------

    private fun appendText(delta: String): ChatState {
        val open = turn.lastOrNull() as? ChatItem.Assistant
        val rows = if (open != null && open.streaming) {
            turn.dropLast(1) + open.copy(text = (open.text + delta).trimStart('\n'))
        } else {
            // A new segment: Hermes starts the first delta after a tool round with a blank line.
            val text = delta.trimStart('\n')
            turn.map(::settled) + ChatItem.Assistant(liveKey(turn.size), text, streaming = true)
        }
        return copy(turn = rows, running = true, status = null, turnOpen = true)
    }

    private fun appendReasoning(delta: String): ChatState {
        val open = turn.lastOrNull() as? ChatItem.Reasoning
        val rows = if (open != null && open.streaming) {
            turn.dropLast(1) + open.copy(text = open.text + delta)
        } else {
            turn.map(::settled) + ChatItem.Reasoning(liveKey(turn.size), delta.trimStart(), streaming = true)
        }
        return copy(turn = rows, running = true, turnHadReasoning = true, turnOpen = true)
    }

    /**
     * `reasoning.available`: a model call's whole reasoning, sent once that call is done (it may
     * come after its reply has streamed). It replaces the segment's reasoning, ahead of the
     * segment's text. When the provider gave no reasoning, Hermes sends the reply itself: that is
     * not shown twice, as the stored row does not hold it either.
     */
    private fun replaceReasoning(text: String): ChatState {
        val clean = text.trim()
        // The segment is everything since the last tool call.
        val start = turn.indexOfLast { it is ChatItem.Tool } + 1
        val segment = turn.subList(start, turn.size)
        if (segment.any { it is ChatItem.Assistant && it.text.trim() == clean }) return this
        val reasoningAt = segment.indexOfLast { it is ChatItem.Reasoning }
        val rows = turn.toMutableList()
        if (reasoningAt >= 0) {
            val at = start + reasoningAt
            rows[at] = (rows[at] as ChatItem.Reasoning).copy(text = clean, streaming = false)
        } else {
            val textAt = segment.indexOfFirst { it is ChatItem.Assistant }
            val at = if (textAt >= 0) start + textAt else rows.size
            rows.add(at, ChatItem.Reasoning(liveKey(turn.size), clean))
        }
        return copy(turn = rows, turnHadReasoning = true)
    }

    /**
     * `message.interim`: the segment just finished, as Hermes stored it. It replaces the streamed
     * text (which may hold separators the stored row does not), or stands alone if nothing streamed.
     */
    private fun sealText(text: String): ChatState {
        val clean = text.trim()
        val open = turn.lastOrNull() as? ChatItem.Assistant
        val rows = when {
            open != null && open.streaming ->
                if (clean.isEmpty()) turn.dropLast(1) else turn.dropLast(1) + open.copy(text = clean, streaming = false)

            clean.isEmpty() || lastSegmentTextIn(turn) == clean -> turn.map(::settled)

            else -> turn.map(::settled) + ChatItem.Assistant(liveKey(turn.size), clean)
        }
        return copy(turn = rows)
    }

    // ---- Tools ---------------------------------------------------------------------------------

    private fun startTool(payload: JsonObject): ChatState {
        val callId = payload.string("tool_id")?.trim()?.takeIf(String::isNotEmpty)
        val name = payload.string("name")?.takeIf(String::isNotBlank) ?: return this
        // A replayed start, or one for a call already in the stored history.
        if (callId != null && items.any { it is ChatItem.Tool && it.callId == callId }) return this
        val args = toolArguments(payload["args"])
        val tool = ChatItem.Tool(
            key = callId?.let(::toolKey) ?: liveKey(turn.size),
            callId = callId,
            name = name,
            summary = toolSummary(args) ?: payload.string("context")?.trim()?.takeIf(String::isNotEmpty),
            input = toolInputText(args),
            output = null,
            status = ToolStatus.Running,
        )
        return copy(turn = turn.map(::settled) + tool, running = true, status = null, turnOpen = true)
    }

    private fun completeTool(payload: JsonObject): ChatState {
        val callId = payload.string("tool_id")?.trim()?.takeIf(String::isNotEmpty)
        val name = payload.string("name")
        val result = payload["result"]
        val status = if (toolFailed(result)) ToolStatus.Failed else ToolStatus.Complete
        val durationMs = payload.double("duration_s")?.let { (it * 1000).toLong() }
        val output = toolOutputText(result)

        fun matches(item: ChatItem) = item is ChatItem.Tool && if (callId != null) {
            item.callId == callId
        } else {
            item.status == ToolStatus.Running && item.name == name
        }

        fun ChatItem.completed(): ChatItem = (this as ChatItem.Tool).copy(
            output = output ?: this.output,
            status = status,
            durationMs = durationMs ?: this.durationMs,
            cards = cardsOf(this, result, name),
        )

        val inTurn = turn.indexOfLast(::matches)
        if (inTurn >= 0) {
            return copy(turn = turn.toMutableList().apply { set(inTurn, get(inTurn).completed()) }, status = null)
        }
        val inHistory = history.indexOfLast(::matches)
        if (inHistory >= 0) {
            return copy(history = history.toMutableList().apply { set(inHistory, get(inHistory).completed()) })
        }
        name ?: return this
        val tool = ChatItem.Tool(
            callId?.let(::toolKey) ?: liveKey(turn.size),
            callId,
            name,
            null,
            null,
            output,
            status,
            durationMs,
        )
        return copy(turn = turn.map(::settled) + tool)
    }

    // ---- End of a turn -------------------------------------------------------------------------

    private fun complete(payload: JsonObject): ChatState {
        val status = payload.string("status") ?: "complete"
        val text = payload.string("text")?.trim().orEmpty()
        val alreadyShown = payload.flag("response_previewed") == true || payload.flag("response_reused") == true
        var rows = turn

        // The last call's reasoning, for a provider that did not stream it.
        val reasoning = payload.string("reasoning")?.trim()
        if (!turnHadReasoning && !reasoning.isNullOrEmpty() && reasoning != text) {
            val open = rows.lastOrNull() as? ChatItem.Assistant
            val reasoningRow = ChatItem.Reasoning(liveKey(rows.size), reasoning)
            rows = if (open != null && open.streaming) rows.dropLast(1) + reasoningRow + open else rows + reasoningRow
        }

        val failedWithoutReply = status == "error" && payload.flag("partial") != true
        val open = rows.lastOrNull() as? ChatItem.Assistant
        rows = when {
            failedWithoutReply -> rows
            alreadyShown -> rows
            open != null && open.streaming -> if (text.isEmpty()) rows else rows.dropLast(1) + open.copy(text = text)
            text.isEmpty() || lastSegmentTextIn(rows) == text -> rows
            else -> rows + ChatItem.Assistant(liveKey(rows.size), text)
        }
        rows = rows.map(::settled)
        when (status) {
            "interrupted" -> rows = rows + ChatItem.Notice(liveKey(rows.size), NoticeKind.Stopped)

            "error" -> {
                val error = payload.string("error")?.takeIf(String::isNotBlank)
                    ?: text.takeIf { failedWithoutReply && it.isNotEmpty() }
                rows = rows + ChatItem.Notice(liveKey(rows.size), NoticeKind.Failed, error)
            }
        }
        val usage = payload.obj("usage")?.let { usage(it).context } ?: context
        return copy(turn = rows, status = null, context = usage, turnsEnded = turnsEnded + 1, turnOpen = false)
    }

    private fun sessionInfo(payload: JsonObject): ChatState {
        val model = ChatModel.from(payload) ?: model
        val settings = ChatSettings.from(payload) ?: settings
        val cwd = payload.string("cwd")?.trim()?.takeIf(String::isNotEmpty) ?: cwd
        val running = payload.flag("running") ?: return copy(model = model, settings = settings, cwd = cwd)
        val title = payload.string("title")?.trim()?.takeIf(String::isNotEmpty) ?: title
        if (running) return copy(running = true, title = title, model = model, settings = settings, cwd = cwd)
        val rows = turn.map(::settled).map { item ->
            if (item is ChatItem.Tool &&
                item.status == ToolStatus.Running
            ) {
                item.copy(status = ToolStatus.Stopped)
            } else {
                item
            }
        }
        return copy(
            turn = rows,
            running = false,
            status = null,
            title = title,
            model = model,
            settings = settings,
            cwd = cwd,
            turnsEnded = if (turnOpen) turnsEnded + 1 else turnsEnded,
            turnOpen = false,
        )
    }

    private fun usage(usage: JsonObject): ChatState {
        val max = usage.long("context_max")?.takeIf { it > 0 } ?: return this
        val used = usage.long("context_used") ?: 0
        val percent = usage.double("context_percent") ?: (used * 100.0 / max)
        return copy(context = ContextUsage(used, max, percent))
    }

    private fun notice(kind: NoticeKind, text: String?) =
        ChatItem.Notice(liveKey(turn.size), kind, text?.trim()?.takeIf(String::isNotEmpty))

    private fun liveKey(index: Int) = "turn$turnSerial-$index"

    private companion object {
        val EMPTY = JsonObject(emptyMap())

        fun settled(item: ChatItem): ChatItem = when {
            item is ChatItem.Assistant && item.streaming -> item.copy(streaming = false)
            item is ChatItem.Reasoning && item.streaming -> item.copy(streaming = false)
            else -> item
        }

        /** The text of the reply segment since the last tool call, if there is one. */
        fun lastSegmentTextIn(rows: List<ChatItem>): String? {
            for (item in rows.asReversed()) {
                when (item) {
                    is ChatItem.Assistant -> return item.text
                    is ChatItem.Tool -> return null
                    else -> Unit
                }
            }
            return null
        }
    }
}
