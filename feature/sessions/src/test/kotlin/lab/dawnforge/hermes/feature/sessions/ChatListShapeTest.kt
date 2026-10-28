package lab.dawnforge.hermes.feature.sessions

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import lab.dawnforge.hermes.core.model.LiveStatus
import lab.dawnforge.hermes.core.model.SessionSummary

class ChatListShapeTest {
    private val zone = ZoneId.of("Europe/London")

    /** Wednesday 22 October 2026, 10:00 in London. */
    private val now = at(2026, 10, 22, 10, 0)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun row(
        id: String,
        lastActive: Long? = now,
        source: String? = "desktop",
        pinned: Boolean = false,
        unread: Boolean = false,
    ) = SessionSummary(
        id = id,
        title = id,
        preview = null,
        model = null,
        source = source,
        startedAtMs = lastActive,
        lastActiveAtMs = lastActive,
        messageCount = 2,
        archived = false,
        pinned = pinned,
        unread = unread,
        profile = "default",
        parentSessionId = null,
    )

    @Test
    fun `days fall in Today, Yesterday, Last 7 days and Older by the calendar`() {
        fun section(time: Long) = ChatListSection.dateSection(time, now, zone)
        assertEquals(ChatListSection.Today, section(at(2026, 10, 22, 0, 1)))
        assertEquals(ChatListSection.Yesterday, section(at(2026, 10, 21, 23, 59)))
        assertEquals(ChatListSection.Yesterday, section(at(2026, 10, 21, 0, 0)))
        assertEquals(ChatListSection.LastSevenDays, section(at(2026, 10, 20, 23, 0)))
        // Six days back is still the week; seven is older.
        assertEquals(ChatListSection.LastSevenDays, section(at(2026, 10, 16, 0, 0)))
        assertEquals(ChatListSection.Older, section(at(2026, 10, 15, 23, 59)))
    }

    @Test
    fun `sections put pinned first, leave empty days out, and file a timeless row with the one before`() {
        val sections = chatSections(
            pinned = listOf(row("p")),
            recent = listOf(
                row("today", at(2026, 10, 22, 9, 0)),
                row("old", at(2026, 9, 1, 9, 0)),
                row("no time", lastActive = null),
            ),
            nowMs = now,
            zone = zone,
        )
        assertEquals(
            listOf(ChatListSection.Pinned, ChatListSection.Today, ChatListSection.Older),
            sections.map(ChatSection::section),
        )
        assertEquals(listOf("old", "no time"), sections.last().rows.map(SessionSummary::id))
        assertEquals(
            listOf("first"),
            chatSections(emptyList(), listOf(row("first", lastActive = null)), now, zone).single().rows.map { it.id },
        )
    }

    @Test
    fun `statuses and sources each list any of theirs, and Starred narrows both`() {
        val unread = row("unread", unread = true)
        val telegram = row("telegram", source = "telegram")
        val working = row("working")
        val live = mapOf("working" to LiveStatus.Working)
        fun listed(filters: ChatFilters, starred: Set<String> = emptySet()) = listOf(unread, telegram, working).filter {
            filters.matches(it, it.id in starred, live[it.id])
        }.map { it.id }

        assertEquals(listOf("unread", "telegram", "working"), listed(ChatFilters()))
        val statuses = ChatFilters(setOf(ChatFilter.Unread, ChatFilter.Running))
        assertEquals(listOf("unread", "working"), listed(statuses))
        assertEquals(listOf("telegram"), listed(ChatFilters(setOf(ChatFilter.Source("telegram")))))
        assertEquals(emptyList(), listed(statuses.with(ChatFilter.Source("telegram"), on = true)))
        assertEquals(
            listOf("working"),
            listed(statuses.with(ChatFilter.Starred, on = true), starred = setOf("working")),
        )
        assertEquals(emptyList(), listed(ChatFilters(setOf(ChatFilter.NeedsApproval))))
    }

    @Test
    fun `scheduled runs show only under Scheduled, unless pinned`() {
        val run = row("run", source = "cron")
        val pinnedRun = row("pinned run", source = "cron", pinned = true)
        assertFalse(ChatFilters().matches(run, starred = false, live = null))
        assertTrue(ChatFilters().matches(pinnedRun, starred = false, live = null))
        val scheduled = ChatFilters(setOf(ChatFilter.Scheduled))
        assertTrue(scheduled.wantsScheduled)
        assertTrue(scheduled.matches(run, starred = false, live = null))
        assertFalse(scheduled.matches(row("chat"), starred = false, live = null))
    }

    @Test
    fun `a facet swaps its group and keeps the rest`() {
        val chosen = ChatFilters(setOf(ChatFilter.Starred, ChatFilter.Unread, ChatFilter.Source("telegram")))
        assertEquals(
            setOf(ChatFilter.Starred, ChatFilter.Running, ChatFilter.Source("telegram")),
            chosen.facet(ChatFilter.Running).chosen,
        )
        assertEquals(
            setOf(ChatFilter.Starred, ChatFilter.Unread, ChatFilter.Scheduled),
            chosen.facet(ChatFilter.Scheduled).chosen,
        )
        assertEquals(chosen.chosen, chosen.facet(ChatFilter.Starred).chosen)
    }

    @Test
    fun `filters are saved by key and read back, unknown keys dropped`() {
        val filters = ChatFilters(setOf(ChatFilter.Starred, ChatFilter.NeedsApproval, ChatFilter.Source("discord")))
        assertEquals(filters, ChatFilters.fromKeys(filters.keys()))
        assertEquals(ChatFilters(), ChatFilters.fromKeys(setOf("voice", "source.")))
    }

    @Test
    fun `source filters are the messaging platforms and the API, by name`() {
        val rows = listOf(
            row("a", source = "telegram"),
            row("b", source = "desktop"),
            row("c", source = "api"),
            row("d", source = "cron"),
            row("e", source = "discord"),
            row("f", source = "telegram"),
            row("g", source = null),
        )
        assertEquals(listOf("api", "discord", "telegram"), ChatFilters.sources(rows).map { it.source })
    }
}
