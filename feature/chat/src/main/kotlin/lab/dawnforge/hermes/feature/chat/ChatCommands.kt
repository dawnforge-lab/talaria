package lab.dawnforge.hermes.feature.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.chat.CompressOutcome
import lab.dawnforge.hermes.core.chat.SlashCommand
import lab.dawnforge.hermes.core.chat.SlashResult
import lab.dawnforge.hermes.core.data.chat.ChatRepository
import lab.dawnforge.hermes.core.data.session.SessionRepository
import lab.dawnforge.hermes.core.model.NoteKind
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.feature.chat.navigation.ChatRoute

/**
 * Slash commands typed in the composer, as the iOS and desktop apps run them. A few the app runs
 * itself: `/new`, `/branch`, `/model`, `/yolo`, `/bg` and `/btw`, `/compress`. The rest go to
 * Hermes (`slash.exec`), whose answer is shown as a note, sent as a message or put back in the
 * composer.
 */
internal class ChatCommands(
    private val scope: CoroutineScope,
    private val chats: ChatRepository,
    private val sessions: SessionRepository,
    private val profile: String,
    private val host: Host,
    /** A bot's one ongoing chat: `/new` and `/reset` compact it instead, as on iOS. */
    private val botChat: Boolean = false,
) {
    /** What the commands need from the chat they run in. */
    interface Host {
        /** The chat's live session, once attached. */
        val runtimeId: String?

        fun note(kind: NoteKind, title: String?, text: String)

        /** Sends [text] as an ordinary message. */
        fun sendText(text: String)

        /** Puts [text] in the composer for the user to finish. */
        fun prefill(text: String)

        fun openModels()

        fun toggleYolo()

        fun openChat(route: ChatRoute)

        /** Loads the stored transcript again, after Hermes rewrote it. */
        fun reload()

        /** The command running, shown while it works; null when done. */
        fun working(command: String?)

        fun tell(message: ChatMessage)
    }

    private var catalog: List<SlashCommand>? = null
    private var loadingCatalog = false

    /** Called when the catalog arrives, so the suggestions can be shown. */
    var onCatalog: () -> Unit = {}

    /**
     * The commands that complete what is typed: only while the draft is a slash and a name, with
     * no space yet. Loads Hermes' list the first time.
     */
    fun suggestions(draft: String): List<SlashCommand> {
        if (!draft.startsWith("/") || draft.contains(' ') || draft.contains('\n')) return emptyList()
        val known = catalog
        if (known == null) {
            loadCatalog()
            return emptyList()
        }
        val prefix = draft.removePrefix("/")
        return known.filter { it.matches(prefix) && it.name != prefix }.take(MAX_SUGGESTIONS)
    }

    private fun loadCatalog() {
        if (loadingCatalog) return
        loadingCatalog = true
        scope.launch {
            val loaded = chats.slashCommands(host.runtimeId)
            loadingCatalog = false
            if (loaded is NetResult.Success) {
                catalog = loaded.value
                onCatalog()
            }
        }
    }

    /** Whether [text] is a command for this class rather than a message. */
    fun isCommand(text: String): Boolean = text.startsWith("/") && text.length > 1 && !text.startsWith("//")

    fun run(text: String) {
        val body = text.trim().removePrefix("/").trim()
        val name = body.substringBefore(' ').lowercase()
        val arg = body.substringAfter(' ', "").trim()
        when (name) {
            "new", "reset" -> if (botChat && arg.isEmpty()) {
                host.tell(ChatMessage.BotChatNeverResets)
                compress("")
            } else {
                newChat()
            }

            "branch", "fork" -> branch()

            "model" -> if (arg.isEmpty()) host.openModels() else exec(body, name)

            "yolo" -> if (arg.isEmpty()) host.toggleYolo() else exec(body, name)

            "bg", "background", "btw" -> if (arg.isEmpty()) exec(body, name) else side(arg, background = name != "btw")

            "compress", "compact" -> compress(arg)

            else -> exec(body, name)
        }
    }

    private fun newChat() = launchWorking("new") {
        when (val chat = sessions.startChat(profile)) {
            is NetResult.Success -> host.openChat(ChatRoute(chat.value.profile, null, chat.value.runtimeId))
            is NetResult.Failure -> failed("new", chat.failure)
        }
    }

    private fun branch() {
        val runtime = host.runtimeId ?: return host.tell(ChatMessage.NotConnected)
        launchWorking("branch") {
            when (val branch = chats.branch(runtime)) {
                is NetResult.Success -> host.openChat(
                    ChatRoute(profile, branch.value.storedId, branch.value.runtimeId, branch.value.title),
                )

                is NetResult.Failure -> failed("branch", branch.failure)
            }
        }
    }

    private fun side(text: String, background: Boolean) {
        val runtime = host.runtimeId ?: return host.tell(ChatMessage.NotConnected)
        scope.launch {
            when (val started = chats.startSideAgent(runtime, text, background)) {
                is NetResult.Success -> host.tell(ChatMessage.SideAgentStarted(background))
                is NetResult.Failure -> failed(if (background) "bg" else "btw", started.failure)
            }
        }
    }

    private fun compress(focus: String) {
        val runtime = host.runtimeId ?: return host.tell(ChatMessage.NotConnected)
        launchWorking("compress") {
            when (val result = chats.compress(runtime, focus)) {
                is NetResult.Success -> when (val outcome = result.value) {
                    is CompressOutcome.Done -> {
                        if (outcome.text.isNotBlank()) {
                            host.note(NoteKind.Command, "/compress", outcome.text)
                        } else {
                            host.tell(ChatMessage.Compressed)
                        }
                        host.reload()
                    }

                    is CompressOutcome.Unchanged ->
                        outcome.text?.let { host.note(NoteKind.Command, "/compress", it) }
                            ?: host.tell(ChatMessage.NothingToCompress)

                    is CompressOutcome.Pending ->
                        outcome.text?.let { host.note(NoteKind.Command, "/compress", it) }
                            ?: host.tell(ChatMessage.CompressPending)
                }

                is NetResult.Failure -> failed("compress", result.failure)
            }
        }
    }

    /** Runs [command] on Hermes; an alias runs its target, a few levels deep at most. */
    private fun exec(command: String, name: String, depth: Int = 0) {
        val runtime = host.runtimeId ?: return host.tell(ChatMessage.NotConnected)
        launchWorking(name) {
            when (val result = chats.runSlash(runtime, command)) {
                is NetResult.Success -> when (val answer = result.value) {
                    is SlashResult.Output -> {
                        val text = listOfNotNull(answer.text.takeIf(String::isNotBlank), answer.warning)
                            .joinToString("\n\n")
                        if (text.isNotEmpty()) host.note(NoteKind.Command, "/$command", text)
                    }

                    is SlashResult.Send -> host.sendText(answer.message)

                    is SlashResult.Prefill -> {
                        answer.notice?.let { host.note(NoteKind.Command, "/$command", it) }
                        host.prefill(answer.message)
                    }

                    is SlashResult.Alias -> if (depth < MAX_ALIAS_DEPTH) {
                        val arg = command.substringAfter(' ', "").trim()
                        val target = answer.target.removePrefix("/").trim()
                        exec(listOf(target, arg).filter(String::isNotEmpty).joinToString(" "), name, depth + 1)
                    }
                }

                is NetResult.Failure -> failed(name, result.failure)
            }
        }
    }

    /** A refusal Hermes worded ("session busy…") is shown as a note; anything else as a failure. */
    private fun failed(command: String, failure: ConnectionFailure) {
        val detail = (failure as? ConnectionFailure.UnexpectedResponse)?.detail?.takeIf(String::isNotBlank)
            ?: (failure as? ConnectionFailure.NotFound)?.detail?.takeIf(String::isNotBlank)
        if (detail != null) {
            host.note(NoteKind.Command, "/$command", detail)
        } else {
            host.tell(ChatMessage.CommandFailed(command, failure))
        }
    }

    private fun launchWorking(command: String, block: suspend () -> Unit) {
        host.working(command)
        scope.launch {
            try {
                block()
            } finally {
                host.working(null)
            }
        }
    }

    private companion object {
        const val MAX_SUGGESTIONS = 6
        const val MAX_ALIAS_DEPTH = 4
    }
}
