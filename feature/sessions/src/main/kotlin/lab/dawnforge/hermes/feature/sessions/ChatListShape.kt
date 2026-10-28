package lab.dawnforge.hermes.feature.sessions

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import lab.dawnforge.hermes.core.model.LiveStatus
import lab.dawnforge.hermes.core.model.SessionSummary

/**
 * The chat list's collapsible sections: Pinned, then the recent chats by day, like Hermes
 * Desktop's sidebar (Today, Yesterday, Last 7 days, Older).
 */
enum class ChatListSection {
    Pinned,
    Today,
    Yesterday,
    LastSevenDays,
    Older,
    ;

    companion object {
        /** A first launch shows today's and yesterday's chats and closes the rest. */
        val defaultCollapsed: Set<ChatListSection> = setOf(LastSevenDays, Older)

        /**
         * The day section an activity time falls in. Today and Yesterday are calendar days; Last 7
         * days is the five days before those, so the three span a week.
         */
        fun dateSection(activityMs: Long, nowMs: Long, zone: ZoneId): ChatListSection {
            val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
            val day = LocalDate.ofInstant(Instant.ofEpochMilli(activityMs), zone)
            return when {
                !day.isBefore(today) -> Today
                !day.isBefore(today.minusDays(1)) -> Yesterday
                !day.isBefore(today.minusDays(WEEK_DAYS - 1)) -> LastSevenDays
                else -> Older
            }
        }

        fun fromName(name: String): ChatListSection? = entries.firstOrNull { it.name == name }

        private const val WEEK_DAYS = 7L
    }
}

data class ChatSection(val section: ChatListSection, val rows: List<SessionSummary>)

/**
 * [pinned] as one section, then [recent] (newest first) split by day, empty sections left out. A
 * row without a time follows the row before it; one at the very top goes in Today.
 */
fun chatSections(
    pinned: List<SessionSummary>,
    recent: List<SessionSummary>,
    nowMs: Long,
    zone: ZoneId,
): List<ChatSection> {
    val byDay = linkedMapOf<ChatListSection, MutableList<SessionSummary>>()
    var previous = ChatListSection.Today
    for (row in recent) {
        val section = row.lastActiveAtMs?.let { ChatListSection.dateSection(it, nowMs, zone) } ?: previous
        byDay.getOrPut(section) { mutableListOf() } += row
        previous = section
    }
    return buildList {
        if (pinned.isNotEmpty()) add(ChatSection(ChatListSection.Pinned, pinned))
        for (section in ChatListSection.entries) byDay[section]?.let { add(ChatSection(section, it)) }
    }
}

/** One filter of the chat list. Filters combine: see [ChatFilters]. */
sealed interface ChatFilter {
    /** How the filter is saved. */
    val key: String

    /** Narrows whatever else is chosen to starred chats. */
    data object Starred : ChatFilter {
        override val key = "starred"
    }

    data object Unread : ChatFilter {
        override val key = "unread"
    }

    /** An approval or a question waits on the user (live state). */
    data object NeedsApproval : ChatFilter {
        override val key = "needs_approval"
    }

    /** A turn is running (live state). */
    data object Running : ChatFilter {
        override val key = "running"
    }

    /** The runs of scheduled jobs, which the list otherwise leaves out. */
    data object Scheduled : ChatFilter {
        override val key = "scheduled"
    }

    /** Chats from one source, such as Telegram. */
    data class Source(val source: String) : ChatFilter {
        override val key = "source.$source"
    }

    val isStatus: Boolean get() = this == Unread || this == NeedsApproval || this == Running

    val isSource: Boolean get() = this == Scheduled || this is Source

    companion object {
        fun fromKey(key: String): ChatFilter? = when {
            key == Starred.key -> Starred
            key == Unread.key -> Unread
            key == NeedsApproval.key -> NeedsApproval
            key == Running.key -> Running
            key == Scheduled.key -> Scheduled
            key.startsWith("source.") -> key.removePrefix("source.").takeIf(String::isNotEmpty)?.let(::Source)
            else -> null
        }
    }
}

/**
 * The chosen filters, any number at once. A chat is listed when it has one of the chosen statuses,
 * comes from one of the chosen sources and, with Starred on, is starred; a group with nothing
 * chosen doesn't narrow.
 */
data class ChatFilters(val chosen: Set<ChatFilter> = emptySet()) {
    val isEmpty: Boolean get() = chosen.isEmpty()

    /** Scheduled runs are listed only when asked for. */
    val wantsScheduled: Boolean get() = ChatFilter.Scheduled in chosen

    fun with(filter: ChatFilter, on: Boolean) = ChatFilters(if (on) chosen + filter else chosen - filter)

    fun matches(row: SessionSummary, starred: Boolean, live: LiveStatus?): Boolean {
        if (ChatFilter.Starred in chosen && !starred) return false
        val statuses = chosen.filter(ChatFilter::isStatus)
        if (statuses.isNotEmpty() && statuses.none { it.matchesStatus(row, live) }) return false
        val sources = chosen.filter(ChatFilter::isSource)
        // Without a source chosen, scheduled runs show only when pinned.
        if (sources.isEmpty()) return row.source != SCHEDULED_SOURCE || row.pinned
        return sources.any { it.matchesSource(row) }
    }

    /**
     * The filters as they would be with [filter] chosen beside the others: in place of the rest of
     * its group, so the menu can say how many chats each choice lists.
     */
    fun facet(filter: ChatFilter): ChatFilters = ChatFilters(
        when {
            filter.isStatus -> chosen.filterNot(ChatFilter::isStatus).toSet() + filter
            filter.isSource -> chosen.filterNot(ChatFilter::isSource).toSet() + filter
            else -> chosen + filter
        },
    )

    fun keys(): Set<String> = chosen.map(ChatFilter::key).toSet()

    private fun ChatFilter.matchesStatus(row: SessionSummary, live: LiveStatus?) = when (this) {
        ChatFilter.Unread -> row.unread
        ChatFilter.NeedsApproval -> live == LiveStatus.NeedsInput
        ChatFilter.Running -> live == LiveStatus.Working
        else -> false
    }

    private fun ChatFilter.matchesSource(row: SessionSummary) = when (this) {
        ChatFilter.Scheduled -> row.source == SCHEDULED_SOURCE
        is ChatFilter.Source -> row.source == source
        else -> false
    }

    companion object {
        const val SCHEDULED_SOURCE = "cron"

        fun fromKeys(keys: Set<String>) = ChatFilters(keys.mapNotNull(ChatFilter::fromKey).toSet())

        /**
         * The sources worth a filter: messaging platforms and the API, which aren't the user's own
         * chats, by name. Scheduled runs have their own filter.
         */
        fun sources(rows: Collection<SessionSummary>): List<ChatFilter.Source> = rows
            .filter { it.isMessagingSource || it.source == API_SOURCE }
            .mapNotNull { it.source?.takeIf { source -> source != SCHEDULED_SOURCE } }
            .distinct()
            .sorted()
            .map(ChatFilter::Source)

        private const val API_SOURCE = "api"
    }
}
