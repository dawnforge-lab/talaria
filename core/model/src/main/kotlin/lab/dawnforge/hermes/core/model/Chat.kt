package lab.dawnforge.hermes.core.model

/**
 * One row of a chat transcript, as the chat screen shows it. Built from the stored history
 * (`/api/sessions/{id}/messages`) and from the live stream, and both must fold into the same rows:
 * a reply streamed on the phone reads the same as the one loaded from history afterwards.
 */
sealed interface ChatItem {
    /** Stable across recompositions; a tool call keeps its key from streamed to stored. */
    val key: String

    /** Something the user sent. [isSteer] marks a message sent into a turn that was running. */
    data class User(
        override val key: String,
        val text: String,
        val attachments: List<ChatAttachment> = emptyList(),
        val timestampMs: Long? = null,
        val isSteer: Boolean = false,
        /** Set while a message typed here is on its way; null once Hermes has it. */
        val delivery: Delivery? = null,
        /** The stored row's id, once Hermes has stored it. */
        val rowId: Long? = null,
        /** The agent's reaction, when it reacted to this message. */
        val reactions: List<Reaction> = emptyList(),
    ) : ChatItem

    /** The agent's words, Markdown; [streaming] while they are still being written. */
    data class Assistant(
        override val key: String,
        val text: String,
        val timestampMs: Long? = null,
        val streaming: Boolean = false,
        /** The stored row's id: what reactions and branches name. Null while the reply streams. */
        val rowId: Long? = null,
        val reactions: List<Reaction> = emptyList(),
    ) : ChatItem

    /** The model's reasoning ahead of a reply or a tool call. */
    data class Reasoning(override val key: String, val text: String, val streaming: Boolean = false) : ChatItem

    /** A tool the agent called, with what it was given and what it returned. */
    data class Tool(
        override val key: String,
        /** The call id, which pairs a call with its result; null for old rows that lack one. */
        val callId: String?,
        val name: String,
        /** One line saying what the call does (the command, the path…), for the collapsed card. */
        val summary: String?,
        /** The arguments, readable. */
        val input: String?,
        /** The result, readable. */
        val output: String?,
        val status: ToolStatus,
        val durationMs: Long? = null,
        /** What a `talaria_show` call showed: drawn as cards instead of the tool's card. */
        val cards: List<RichCard> = emptyList(),
    ) : ChatItem

    /** A timeline note: a model switch, a stopped or failed turn, a background job finishing. */
    data class Notice(
        override val key: String,
        val kind: NoticeKind,
        /** Hermes' own wording, when it has some; the screen words the rest from [kind]. */
        val text: String? = null,
    ) : ChatItem

    /**
     * Something only this phone shows, kept apart from the stored transcript: what a slash command
     * printed, or the answer to a side question or a background task. [title] is the command, the
     * question or the task.
     */
    data class Note(override val key: String, val kind: NoteKind, val title: String?, val text: String) : ChatItem
}

enum class NoteKind {
    /** A slash command's output, or a note about a command the app ran. */
    Command,

    /** `/btw`: answered from a snapshot of the chat, outside it. */
    SideQuestion,

    /** `/bg`: a task that ran beside the chat. */
    Background,

    /** Hermes' self-improvement review after a turn: what it saved to memory or its skills. */
    Review,
}

/** A reaction to a message: the user's, or the agent's (with its `react_to_message` tool). One each at most. */
data class Reaction(val emoji: String, val byAgent: Boolean)

/** Where a message typed in this app is, until Hermes has it. */
enum class Delivery {
    /** Being uploaded and sent. */
    Sending,

    /** Waiting in the app for the running turn to end. */
    Queued,

    /** Typed while the app could not reach Hermes; it goes when the connection is back. */
    Waiting,

    /** Not sent: the screen offers to try again. */
    Failed,
}

enum class ToolStatus {
    Running,
    Complete,
    Failed,

    /** The turn ended before the call returned (stopped, or Hermes restarted). */
    Stopped,
}

enum class NoticeKind {
    /** Hermes' own note, shown as it is. */
    Info,
    ModelSwitched,
    PersonalitySwitched,

    /** Hermes resumed a turn that was interrupted (e.g. by a restart). */
    Resumed,
    BackgroundAgentsFinished,
    BackgroundProcessFinished,

    /** The turn was stopped before it finished. */
    Stopped,

    /** The turn failed; [ChatItem.Notice.text] says why, when Hermes said. */
    Failed,
}

/** A file the user attached to a message, as the stored message refers to it. */
data class ChatAttachment(
    /** The file name, for the chip. */
    val name: String,
    /** The path on the Hermes host. */
    val path: String,
    val isImage: Boolean,
)
