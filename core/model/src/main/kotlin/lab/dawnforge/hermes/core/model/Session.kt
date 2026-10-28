package lab.dawnforge.hermes.core.model

/** A Hermes profile: a separate home with its own sessions, config and model. */
data class Profile(
    /** The canonical id (lowercase, or "default"); use it for every request. */
    val name: String,
    /** A presentation-only name, if the user set one. */
    val displayName: String?,
    val isDefault: Boolean,
    val model: String?,
    val provider: String?,
) {
    val label: String get() = displayName?.trim()?.takeIf(String::isNotEmpty) ?: name

    companion object {
        const val DEFAULT = "default"
    }
}

/** One row of a profile's session list. */
data class SessionSummary(
    /** The stored id (for a compressed conversation, its latest part); used for every REST call. */
    val id: String,
    val title: String?,
    /** The first user message, shortened. */
    val preview: String?,
    val model: String?,
    /** Where the session came from: desktop, tui, cli, telegram, discord, cron… */
    val source: String?,
    /** Epoch milliseconds. */
    val startedAtMs: Long?,
    /** Epoch milliseconds of the last message or activity. */
    val lastActiveAtMs: Long?,
    val messageCount: Int,
    val archived: Boolean,
    val pinned: Boolean,
    val unread: Boolean,
    /** The profile the session belongs to. */
    val profile: String?,
    /** Set for a branch (`/branch`) or a reset (`/new`) of another session. */
    val parentSessionId: String?,
    /** Marked unread on purpose ("Mark as unread"), not just active since it was read. */
    val markedUnread: Boolean = false,
) {
    /** The title, else the first message, else the id: what a list row shows. */
    val displayTitle: String get() = title?.takeIf(String::isNotBlank) ?: preview?.takeIf(String::isNotBlank) ?: id

    /** Sources shown with a badge, because they are not the user's own chats. */
    val isMessagingSource: Boolean get() = source != null && source !in OWN_SOURCES

    private companion object {
        val OWN_SOURCES =
            setOf("desktop", "tui", "cli", "web", "dashboard", "mobile", "local", "gateway", "chat", "api")
    }
}

/** What a chat is doing now, from Hermes' live sessions (`session.active_list`). */
enum class LiveStatus {
    /** A turn is running. */
    Working,

    /** An approval or a question is waiting on the user. */
    NeedsInput,
}

/** One search result; [snippet] marks matches with `>>>` and `<<<`. */
data class SessionSearchHit(
    val sessionId: String,
    val title: String?,
    val preview: String?,
    val snippet: String?,
    val lastActiveAtMs: Long?,
    val archived: Boolean,
    val source: String?,
) {
    val displayTitle: String get() = title?.takeIf(String::isNotBlank) ?: preview?.takeIf(String::isNotBlank)
        ?: sessionId
}

/** A chat started on the gateway; it has no stored row until its first message. */
data class NewChat(
    /** The live session id on the gateway. */
    val runtimeId: String,
    /** The id its stored row will have. */
    val storedId: String?,
    val profile: String,
)
