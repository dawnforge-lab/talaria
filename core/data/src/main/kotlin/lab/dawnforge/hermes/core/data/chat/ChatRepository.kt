package lab.dawnforge.hermes.core.data.chat

import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.chat.ChatModel
import lab.dawnforge.hermes.core.chat.ChatSettings
import lab.dawnforge.hermes.core.chat.CompressOutcome
import lab.dawnforge.hermes.core.chat.ContextBreakdown
import lab.dawnforge.hermes.core.chat.DelegateAgent
import lab.dawnforge.hermes.core.chat.ModelChoices
import lab.dawnforge.hermes.core.chat.NewChatRecovery
import lab.dawnforge.hermes.core.chat.SlashCommand
import lab.dawnforge.hermes.core.chat.SlashResult
import lab.dawnforge.hermes.core.chat.parseReactions
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.session.map
import lab.dawnforge.hermes.core.gateway.GatewayClient
import lab.dawnforge.hermes.core.gateway.GatewayClosedException
import lab.dawnforge.hermes.core.gateway.RpcException
import lab.dawnforge.hermes.core.gateway.RpcTimeoutException
import lab.dawnforge.hermes.core.gateway.ServerRequest
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.Reaction
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/**
 * One page of a chat's stored history, oldest row first.
 *
 * [sessionId] is the id Hermes read, which may be a later part of a compressed conversation than
 * the one asked for. [returned] counts every row, hidden ones included: the next older page starts
 * that many rows further back.
 */
data class HistoryPage(val sessionId: String, val rows: List<JsonObject>, val returned: Int, val hasMore: Boolean)

/** What Hermes did with a message. */
data class Sent(val status: SendStatus, val userRowId: String?)

enum class SendStatus {
    /** A turn started for it. */
    Streaming,

    /** Hermes holds it for after the running turn. */
    Queued,

    /** It went into the running turn. */
    Steered,
    Redirected,
}

/** What became of a correction sent into a running turn. */
enum class Correction {
    Accepted,

    /** Hermes could not apply it yet and will send it as the next message. */
    Queued,

    /** No turn was running: send it as an ordinary message. */
    Idle,
}

/** Hermes' setting for messages sent while a turn runs. */
enum class BusyMode {
    /** Correct the running turn (the default). */
    Interrupt,

    /** Hold it for after the turn. */
    Queue,

    /** Add it to the running turn as a note. */
    Steer,
}

/** A chat attached to the live connection, so its events come to this app. */
data class OpenedChat(
    /** The live session id that events carry. */
    val runtimeId: String,
    /** The stored id, for REST. */
    val storedId: String?,
    /** Whether a turn is running right now. */
    val running: Boolean,
    /** What the running turn has written so far, if Hermes has it. */
    val inflightReply: String?,
    /** The message the running turn answers, as Hermes received it. */
    val inflightPrompt: String?,
    /** The message Hermes holds for after the running turn. */
    val queuedPrompt: String?,
    val title: String?,
    /** The model the chat's next turn uses. */
    val model: ChatModel? = null,
    /** The chat's fast mode and approvals. */
    val settings: ChatSettings? = null,
    /** The chat's working folder on the host. */
    val cwd: String? = null,
)

/** A message's reactions after reacting to it, and its stored row. */
data class Reacted(val rowId: Long?, val reactions: List<Reaction>)

/** A branch of a chat, made from its transcript so far: a chat of its own. */
data class BranchedChat(val runtimeId: String, val storedId: String?, val title: String?)

/** How a model switch went. */
sealed interface ModelSwitch {
    /** Switched; [deferred] when a turn was running, so it applies from the next one. */
    data class Done(val model: String, val deferred: Boolean, val warning: String?) : ModelSwitch

    /** An expensive model: Hermes asks to confirm, with [message], before switching. */
    data class ConfirmRequired(val message: String?) : ModelSwitch
}

/**
 * A chat's history (Hermes' `GET /api/sessions/{id}/messages`) and its live session. See
 * docs/hermes-chat-protocol.md for the shapes.
 */
class ChatRepository(
    private val requests: DashboardRequests,
    /** The active dashboard's live connection, if any. */
    private val gateway: () -> GatewayClient?,
) {
    /**
     * The page of [sessionId]'s history that ends [offset] rows before the newest. Compacted turns
     * are included (they are still the user's conversation); images are left as `[image]` so a
     * page stays small.
     */
    suspend fun history(
        id: DashboardId,
        profile: String,
        sessionId: String,
        offset: Int = 0,
        limit: Int = PAGE_SIZE,
    ): NetResult<HistoryPage> = requests.run(id) { url, jar, cloudflare ->
        get(
            url,
            jar,
            cloudflare,
            "/api/sessions",
            listOf(sessionId, "messages"),
            query = mapOf(
                "profile" to profile,
                "order" to "latest",
                "offset" to offset.toString(),
                "limit" to limit.toString(),
                "include_compacted" to "true",
                "inline_images" to "false",
            ),
        ).map { body ->
            val obj = body as? JsonObject
            val rows = (obj?.get("messages") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val pagination = obj?.get("pagination") as? JsonObject
            val returned = (pagination?.get("returned") as? JsonPrimitive)?.intOrNull ?: rows.size
            HistoryPage(
                sessionId = (obj?.get("session_id") as? JsonPrimitive)?.contentOrNull ?: sessionId,
                rows = rows,
                returned = returned,
                hasMore = returned >= limit,
            )
        }
    }

    /**
     * Attaches a stored chat to the live connection (`session.resume`). Hermes reuses the session
     * if another client has it open, so a turn running on the desktop streams here too. The
     * history is not sent along; [history] pages it.
     */
    suspend fun open(profile: String, storedId: String): NetResult<OpenedChat> = gatewayCall { client ->
        val snapshot = client.call(
            "session.resume",
            buildJsonObject {
                put("session_id", storedId)
                put("profile", profile)
                put("omit_messages", true)
                put("defer_history", true)
                put("inline_images", false)
            },
            timeoutMs = OPEN_TIMEOUT_MS,
        ) as? JsonObject ?: return@gatewayCall NetResult.Failure(NO_SNAPSHOT)
        val runtimeId = snapshot.text("session_id") ?: return@gatewayCall NetResult.Failure(NO_SNAPSHOT)
        val stored = snapshot.text("session_key") ?: storedId
        client.trackSession(runtimeId, stored)
        // An approval or a question waiting when the chat is opened.
        client.offerOpenRequests(snapshot)
        NetResult.Success(snapshot.toOpenedChat(runtimeId, stored))
    }

    /** Re-attaches a chat started on this connection and not stored yet (`session.activate`). */
    suspend fun attach(runtimeId: String, storedId: String?): NetResult<OpenedChat> = gatewayCall { client ->
        val snapshot = client.attachSession(runtimeId, storedId)
        NetResult.Success(snapshot.toOpenedChat(runtimeId, snapshot.text("session_key") ?: storedId))
    }

    /**
     * Starts a chat Hermes let go before storing it again (`session.create`, in [cwd] when it had
     * one), and attaches it: a message waiting in it goes there ([NewChatRecovery]).
     */
    suspend fun startAgain(profile: String, cwd: String?): NetResult<OpenedChat> = gatewayCall { client ->
        val created = client.call(
            "session.create",
            buildJsonObject {
                put("profile", profile)
                cwd?.takeIf(String::isNotBlank)?.let {
                    put("cwd", it)
                    put("cwd_explicit", true)
                }
                // Lets Hermes recognise a retried create instead of making a second chat.
                put("idempotency_key", UUID.randomUUID().toString())
            },
            timeoutMs = OPEN_TIMEOUT_MS,
        ) as? JsonObject ?: return@gatewayCall NetResult.Failure(NO_SNAPSHOT)
        val runtimeId = created.text("session_id") ?: return@gatewayCall NetResult.Failure(NO_SNAPSHOT)
        val storedId = created.text("stored_session_id")
        val snapshot = client.attachSession(runtimeId, storedId)
        NetResult.Success(snapshot.toOpenedChat(runtimeId, snapshot.text("session_key") ?: storedId))
    }

    // ---- Sending -------------------------------------------------------------------------------

    /** This Hermes refused `voice_turn` once: it doesn't know it, so it isn't sent again. */
    @Volatile
    private var refusesVoiceTurn = false

    /**
     * Sends a message (`prompt.submit`), with whatever was attached to the session since the last
     * one. [queued] asks Hermes to hold it for after the running turn rather than act on it at once.
     * [voiceTurn] marks it as said in a voice conversation, so Hermes answers to be heard (its
     * `voice_chat` model); a Hermes that doesn't know the mark gets the message without it, and
     * isn't sent it again. Hermes may answer "busy" for a few seconds after a stop; that is retried.
     */
    suspend fun send(
        runtimeId: String,
        text: String,
        queued: Boolean = false,
        voiceTurn: Boolean = false,
    ): NetResult<Sent> = gatewayCall { client ->
        fun params(spoken: Boolean) = buildJsonObject {
            put("session_id", runtimeId)
            put("text", text)
            if (queued) put("queued", true)
            if (voiceTurn) put("surface", SPOKEN_SURFACE)
            if (spoken) put("voice_turn", true)
        }
        var spoken = voiceTurn && !refusesVoiceTurn
        var waited = 0L
        var result: JsonObject?
        while (true) {
            try {
                result = client.call("prompt.submit", params(spoken)) as? JsonObject
                break
            } catch (e: RpcException) {
                if (spoken && e.code == INVALID_PARAMS && "voice_turn" in e.message) {
                    refusesVoiceTurn = true
                    spoken = false
                    continue
                }
                if (e.code != SESSION_BUSY || waited >= BUSY_RETRY_FOR_MS) throw e
                delay(BUSY_RETRY_EVERY_MS)
                waited += BUSY_RETRY_EVERY_MS
            }
        }
        val status = when (result?.text("status")) {
            "queued" -> SendStatus.Queued
            "steered" -> SendStatus.Steered
            "redirected" -> SendStatus.Redirected
            else -> SendStatus.Streaming
        }
        NetResult.Success(Sent(status, result?.scalar("user_row_id")))
    }

    /**
     * Puts [text] into the running turn: as a correction the agent acts on now
     * (`session.redirect`), or, with [steer], as a note it picks up at its next step
     * (`session.steer`).
     */
    suspend fun correct(runtimeId: String, text: String, steer: Boolean): NetResult<Correction> =
        gatewayCall { client ->
            val result = client.call(
                if (steer) "session.steer" else "session.redirect",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("text", text)
                },
            ) as? JsonObject
            NetResult.Success(
                when (result?.text("status")) {
                    "redirected" -> Correction.Accepted

                    // session.steer says "queued" for a steer it accepted.
                    "queued" -> if (steer) Correction.Accepted else Correction.Queued

                    else -> Correction.Idle
                },
            )
        }

    // ---- Model and reasoning ------------------------------------------------------------------

    /** The models this chat can switch to, as the desktop app lists them. */
    suspend fun modelChoices(runtimeId: String): NetResult<ModelChoices> = gatewayCall { client ->
        val options = client.call(
            "model.options",
            buildJsonObject {
                put("session_id", runtimeId)
                put("explicit_only", true)
            },
            timeoutMs = MODEL_OPTIONS_TIMEOUT_MS,
        ) as? JsonObject ?: JsonObject(emptyMap())
        NetResult.Success(ModelChoices.parse(options))
    }

    /**
     * Switches this chat, and only this chat, to [model] of [provider] (`--session`: nothing is
     * written to Hermes' config). [confirmExpensive] answers Hermes' question about a costly model.
     */
    suspend fun switchModel(
        runtimeId: String,
        provider: String,
        model: String,
        confirmExpensive: Boolean = false,
    ): NetResult<ModelSwitch> = gatewayCall { client ->
        val reply = client.call(
            "config.set",
            buildJsonObject {
                put("session_id", runtimeId)
                put("key", "model")
                put("value", "$model --provider $provider --session")
                if (confirmExpensive) put("confirm_expensive_model", true)
            },
        ) as? JsonObject ?: JsonObject(emptyMap())
        if ((reply["confirm_required"] as? JsonPrimitive)?.booleanOrNull == true) {
            return@gatewayCall NetResult.Success(ModelSwitch.ConfirmRequired(reply.text("confirm_message")))
        }
        NetResult.Success(
            ModelSwitch.Done(
                model = reply.text("value") ?: model,
                deferred = (reply["deferred"] as? JsonPrimitive)?.booleanOrNull == true,
                warning = reply.text("warning"),
            ),
        )
    }

    /** This chat's reasoning level ([REASONING_LEVELS]); it applies at once. */
    suspend fun setReasoning(runtimeId: String, level: String): NetResult<Unit> = gatewayCall { client ->
        client.call(
            "config.set",
            buildJsonObject {
                put("session_id", runtimeId)
                put("key", "reasoning")
                put("value", level)
            },
        )
        NetResult.Success(Unit)
    }

    /** Fast mode for this chat: the provider's priority tier, where the model has one. */
    suspend fun setFast(runtimeId: String, on: Boolean): NetResult<Unit> =
        setting(runtimeId, "fast", if (on) "fast" else "normal")

    /** Runs this chat's commands without asking for approval; it lasts with the chat. */
    suspend fun setYolo(runtimeId: String, on: Boolean): NetResult<Unit> =
        setting(runtimeId, "yolo", if (on) "1" else "0")

    private suspend fun setting(runtimeId: String, key: String, value: String): NetResult<Unit> =
        gatewayCall { client ->
            client.call(
                "config.set",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("key", key)
                    put("value", value)
                },
            )
            NetResult.Success(Unit)
        }

    /** How this chat's context window is used, by category (`session.context_breakdown`). */
    suspend fun contextBreakdown(runtimeId: String): NetResult<ContextBreakdown> = gatewayCall { client ->
        val result = client.call("session.context_breakdown", buildJsonObject { put("session_id", runtimeId) })
        NetResult.Success(ContextBreakdown.parse(result as? JsonObject ?: JsonObject(emptyMap())))
    }

    // ---- Slash commands, side agents, compression and branches ---------------------------------

    /** The slash commands Hermes offers this chat (`commands.catalog`), for the composer's suggestions. */
    suspend fun slashCommands(runtimeId: String?): NetResult<List<SlashCommand>> = gatewayCall { client ->
        val catalog = client.call(
            "commands.catalog",
            buildJsonObject { runtimeId?.let { put("session_id", it) } },
        ) as? JsonObject ?: JsonObject(emptyMap())
        NetResult.Success(SlashCommand.parseCatalog(catalog))
    }

    /**
     * Runs a slash command on Hermes ([command] without its slash). `slash.exec` runs most; the
     * ones it sends elsewhere (skills, quick commands) go through `command.dispatch`, as the desktop
     * app does. A refusal keeps Hermes' own words, in [ConnectionFailure.UnexpectedResponse].
     */
    suspend fun runSlash(runtimeId: String, command: String): NetResult<SlashResult> = gatewayCall { client ->
        val result = try {
            client.call(
                "slash.exec",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("command", command)
                },
                timeoutMs = SLASH_TIMEOUT_MS,
            )
        } catch (refused: RpcException) {
            if (refused.code in SESSION_NOT_FOUND) throw refused
            val name = command.substringBefore(' ')
            val arg = command.substringAfter(' ', "").trim()
            try {
                client.call(
                    "command.dispatch",
                    buildJsonObject {
                        put("session_id", runtimeId)
                        put("name", name)
                        if (arg.isNotEmpty()) put("arg", arg)
                    },
                    timeoutMs = SLASH_TIMEOUT_MS,
                )
            } catch (dispatchRefused: RpcException) {
                // Hermes names command.dispatch when the command belongs there; otherwise its first answer stands.
                throw if (refused.message.contains("command.dispatch")) dispatchRefused else refused
            }
        }
        NetResult.Success(SlashResult.parse(result as? JsonObject ?: JsonObject(emptyMap())))
    }

    /**
     * Starts a side agent on this chat: a background task (`prompt.background`, `/bg`) or a side
     * question answered from a snapshot of the chat (`prompt.btw`, `/btw`). Its answer arrives
     * later as an event; this returns the task id.
     */
    suspend fun startSideAgent(runtimeId: String, text: String, background: Boolean): NetResult<String?> =
        gatewayCall { client ->
            val result = client.call(
                if (background) "prompt.background" else "prompt.btw",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("text", text)
                },
            ) as? JsonObject
            NetResult.Success(result?.text("task_id"))
        }

    /** Compresses the chat's history (`/compress`), optionally around a [focus] topic. It can take minutes. */
    suspend fun compress(runtimeId: String, focus: String?): NetResult<CompressOutcome> = gatewayCall { client ->
        val result = client.call(
            "session.compress",
            buildJsonObject {
                put("session_id", runtimeId)
                focus?.takeIf(String::isNotBlank)?.let { put("focus_topic", it) }
            },
            timeoutMs = COMPRESS_TIMEOUT_MS,
        ) as? JsonObject ?: JsonObject(emptyMap())
        NetResult.Success(CompressOutcome.parse(result))
    }

    /**
     * A new chat holding this one's transcript so far (`session.branch`), stored at once; with
     * [count], only its first [count] messages (see [branchCount]). Hermes copies the rows itself,
     * reasoning and all, and links the branch to this chat.
     */
    suspend fun branch(runtimeId: String, count: Int? = null): NetResult<BranchedChat> = gatewayCall { client ->
        val result = client.call(
            "session.branch",
            buildJsonObject {
                put("session_id", runtimeId)
                count?.let { put("count", it) }
                put("idempotency_key", UUID.randomUUID().toString())
            },
        ) as? JsonObject
        val branchId = result?.text("session_id") ?: return@gatewayCall NetResult.Failure(NO_SNAPSHOT)
        val storedId = result.text("stored_session_id")
        client.trackSession(branchId, storedId)
        NetResult.Success(BranchedChat(branchId, storedId, result.text("title")))
    }

    /**
     * Reacts to a message: the stored row [rowId], or, for a reply not stored yet, the newest
     * assistant message. Picking the reaction the user already has takes it back. Hermes answers
     * with the row's reactions; the agent hears of them with the next message.
     */
    suspend fun react(runtimeId: String, rowId: Long?, emoji: String): NetResult<Reacted> = gatewayCall { client ->
        val result = client.call(
            "message.react",
            buildJsonObject {
                put("session_id", runtimeId)
                if (rowId != null) put("row_id", rowId) else put("newest_role", "assistant")
                put("emoji", emoji)
                put("author", "user")
            },
        ) as? JsonObject ?: JsonObject(emptyMap())
        NetResult.Success(
            Reacted(
                rowId = (result["row_id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: rowId,
                reactions = parseReactions(result["reactions"] as? JsonArray),
            ),
        )
    }

    // ---- Sub-agents ----------------------------------------------------------------------------

    /** The sub-agents Hermes still has for this chat (`subagent.list`). */
    suspend fun subagents(runtimeId: String): NetResult<List<DelegateAgent>> = gatewayCall { client ->
        val result = client.call("subagent.list", buildJsonObject { put("session_id", runtimeId) }) as? JsonObject
        NetResult.Success(DelegateAgent.parseList(result ?: JsonObject(emptyMap())))
    }

    /** The end of a sub-agent's output so far, or null when Hermes has none for it. */
    suspend fun subagentOutput(runtimeId: String, subagentId: String): NetResult<String?> = gatewayCall { client ->
        val result = client.call(
            "subagent.tail",
            buildJsonObject {
                put("session_id", runtimeId)
                put("subagent_id", subagentId)
            },
        ) as? JsonObject
        val available = (result?.get("available") as? JsonPrimitive)?.booleanOrNull == true
        NetResult.Success(if (available) result.text("text") else null)
    }

    /** Sends a sub-agent a note to steer it; false when it no longer takes one. */
    suspend fun steerSubagent(runtimeId: String, subagentId: String, text: String): NetResult<Boolean> =
        gatewayCall { client ->
            val result = client.call(
                "subagent.steer",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("subagent_id", subagentId)
                    put("text", text)
                },
            ) as? JsonObject
            NetResult.Success(result?.text("status") == "queued")
        }

    /** Stops a sub-agent; false when it had already finished. */
    suspend fun stopSubagent(runtimeId: String, subagentId: String): NetResult<Boolean> = gatewayCall { client ->
        val result = client.call(
            "subagent.interrupt",
            buildJsonObject {
                put("session_id", runtimeId)
                put("subagent_id", subagentId)
            },
        ) as? JsonObject
        NetResult.Success((result?.get("found") as? JsonPrimitive)?.booleanOrNull == true)
    }

    /** Stops the running turn (`session.interrupt`). Hermes also drops anything it had queued. */
    suspend fun stop(runtimeId: String): NetResult<Boolean> = gatewayCall { client ->
        val result = client.call("session.interrupt", buildJsonObject { put("session_id", runtimeId) }) as? JsonObject
        NetResult.Success(result?.text("status") == "interrupted")
    }

    /** What Hermes does with a message sent while a turn runs (`display.busy_input_mode`). */
    suspend fun busyMode(): NetResult<BusyMode> = gatewayCall { client ->
        val result = client.call("config.get", buildJsonObject { put("key", "busy") }) as? JsonObject
        NetResult.Success(
            when (result?.text("value")) {
                "queue" -> BusyMode.Queue
                "steer" -> BusyMode.Steer
                else -> BusyMode.Interrupt
            },
        )
    }

    // ---- Attachments ---------------------------------------------------------------------------

    /** Stages an image for the next message (`image.attach_bytes`; Hermes takes up to 25 MiB). */
    suspend fun attachImage(runtimeId: String, bytes: ByteArray, fileName: String): NetResult<Unit> =
        gatewayCall { client ->
            client.call(
                "image.attach_bytes",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("content_base64", Base64.getEncoder().encodeToString(bytes))
                    put("filename", fileName)
                },
                timeoutMs = UPLOAD_TIMEOUT_MS,
            )
            NetResult.Success(Unit)
        }

    /**
     * Uploads a file (`file.attach`) and returns the `@file:` reference the message must carry:
     * Hermes tells the model where the file is, and the model reads it with its tools.
     */
    suspend fun attachFile(
        runtimeId: String,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
    ): NetResult<String> = gatewayCall { client ->
        val result = client.call(
            "file.attach",
            buildJsonObject {
                put("session_id", runtimeId)
                put("name", fileName)
                put("data_url", "data:$mimeType;base64," + Base64.getEncoder().encodeToString(bytes))
            },
            timeoutMs = UPLOAD_TIMEOUT_MS,
        ) as? JsonObject
        val reference = result?.text("ref_text")
            ?: result?.text("path")?.let { "@file:$it" }
            ?: return@gatewayCall NetResult.Failure(ConnectionFailure.UnexpectedResponse(null, "No file reference"))
        NetResult.Success(reference)
    }

    // ---- Requests ------------------------------------------------------------------------------

    /** Answers a server request; false when the connection is gone (it is replayed on reconnect). */
    fun answer(request: ServerRequest, result: JsonElement): Boolean = gateway()?.respond(request, result) ?: false

    /** Declines a request this app does not show, so another client can answer it. */
    fun decline(request: ServerRequest): Boolean = gateway()?.decline(request) ?: false

    /**
     * Stops following a chat. The live session stays: another client may be using it, and Hermes
     * tidies up idle ones itself.
     */
    fun leave(runtimeId: String) {
        gateway()?.untrackSession(runtimeId)
    }

    private inline fun <T> gatewayCall(block: (GatewayClient) -> NetResult<T>): NetResult<T> {
        val client = gateway() ?: return NetResult.Failure(NOT_CONNECTED)
        return try {
            block(client)
        } catch (e: RpcException) {
            NetResult.Failure(
                when {
                    e.code in SESSION_NOT_FOUND -> ConnectionFailure.NotFound(e.message)
                    e.reason == NOT_OWNED -> ConnectionFailure.OwnedElsewhere(e.message)
                    else -> ConnectionFailure.UnexpectedResponse(e.code, e.message)
                },
            )
        } catch (_: GatewayClosedException) {
            NetResult.Failure(NOT_CONNECTED)
        } catch (_: RpcTimeoutException) {
            NetResult.Failure(ConnectionFailure.TimedOut)
        }
    }

    private fun JsonObject.toOpenedChat(runtimeId: String, storedId: String?): OpenedChat {
        val inflight = get("inflight") as? JsonObject
        val info = get("info") as? JsonObject
        return OpenedChat(
            runtimeId = runtimeId,
            storedId = storedId,
            running = (get("running") as? JsonPrimitive)?.booleanOrNull ?: false,
            inflightReply = inflight?.text("assistant"),
            inflightPrompt = inflight?.text("user"),
            queuedPrompt = (get("queued") as? JsonObject)?.text("user"),
            title = info?.text("title") ?: text("title"),
            model = info?.let(ChatModel::from),
            settings = info?.let(ChatSettings::from),
            cwd = info?.text("cwd"),
        )
    }

    private fun JsonObject.scalar(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotEmpty)

    companion object {
        /** Listing every provider's models can take a moment on a cold cache. */
        private const val MODEL_OPTIONS_TIMEOUT_MS = 30_000L

        /** Most commands answer at once; some (`/usage`, a skill) run a moment in a worker. */
        private const val SLASH_TIMEOUT_MS = 60_000L

        /** Compression asks the model for a summary of the whole chat. */
        private const val COMPRESS_TIMEOUT_MS = 300_000L

        /** Rows per history page; Hermes caps a page at 500. */
        const val PAGE_SIZE = 120

        /** A resume reads the stored session and may wait on a busy profile database. */
        private const val OPEN_TIMEOUT_MS = 30_000L

        /** Hermes' "session not found": 4007 from `session.resume`, 4001 from `session.activate`. */
        private val SESSION_NOT_FOUND = setOf(4007, 4001)

        /** Hermes' refusal (4090) when Hermes Desktop or a terminal has the chat open. */
        private const val NOT_OWNED = "SESSION_NOT_OWNED"

        /** Hermes' "session busy", e.g. just after a stop while the turn winds down. */
        private const val SESSION_BUSY = 4009

        /** Hermes' answer to a parameter it doesn't know. */
        private const val INVALID_PARAMS = 4000

        /** Where a spoken turn comes from, as Hermes names it. */
        private const val SPOKEN_SURFACE = "voice-live"

        private const val BUSY_RETRY_FOR_MS = 6_000L
        private const val BUSY_RETRY_EVERY_MS = 150L

        /** An upload goes as one socket frame; a large photo over a slow link takes a while. */
        private const val UPLOAD_TIMEOUT_MS = 120_000L

        private val NOT_CONNECTED = ConnectionFailure.NetworkError("The live connection is not open")
        private val NO_SNAPSHOT = ConnectionFailure.UnexpectedResponse(null, "No session in the reply")
    }
}
