package lab.dawnforge.hermes.core.data.bots

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The bots' rules, with the cases iOS's tests check (BotManagementTests, BotMentionTests, BotModeTests). */
class BotsTest {
    private fun bot(
        name: String,
        title: String? = null,
        displayName: String? = null,
        hidden: Boolean = false,
        previous: List<String> = emptyList(),
        canonical: String? = null,
    ) = BotProfile(
        name, title, displayName, "", false, false, hidden, null,
        canonical?.let { BotSession(it, null, null, null) }, null, null, previous, name == "default",
        JsonObject(emptyMap()), null,
    )

    @Test
    fun `a typed name becomes a profile id`() {
        assertEquals("ada-lovelace", BotSlug.of("Ada Lovelace"))
        assertEquals("hi-there__", BotSlug.of("  --Hi!! there__ "))
        assertEquals("resume", BotSlug.of("Résumé"))
        assertEquals("u5c0f-u52a9-u624b", BotSlug.of("小助手"))
        assertEquals("", BotSlug.of("!!!"))
        assertTrue(BotSlug.isValid("ada-lovelace"))
        assertFalse(BotSlug.isValid("-ada"))
        assertFalse(BotSlug.isValid(""))
        assertTrue(BotSlug.of("a".repeat(80)).length <= BotSlug.MAX_LENGTH)
        assertEquals("", BotSlug.title("atlas"))
        assertEquals("Ada Lovelace", BotSlug.title(" Ada Lovelace "))
    }

    @Test
    fun `a new bot's personality is its own identity, with the messaging section only where the host lacks it`() {
        val soul = BotSoul.compose("atlas", "Atlas", "Maps things", "", protocolInjected = true, roster = emptyList())
        assertEquals(
            "# Atlas\n\n**Mission:** Maps things\n\nYou are Atlas, a persistent named agent (profile `atlas`) on " +
                "this machine.\n" +
                "You keep your own memory, skills, and conversation history across sessions.",
            soul,
        )
        val old = BotSoul.compose("atlas", "", "", "", protocolInjected = false, roster = listOf(bot("scout")))
        assertTrue(BotSoul.PROTOCOL_HEADING in old)
        assertTrue("- `scout`" in old)
        val own = BotSoul.compose("atlas", "", "", " Mine. ", protocolInjected = true, roster = emptyList())
        assertEquals("Mine.", own)
    }

    @Test
    fun `colours follow Desktop's swatches and its hue from the name`() {
        // JS: h = (h * 31 + charCode) >>> 0 over "atlas" = 93144203; % 360 = 323.
        assertEquals(BotColor.hsl(323.0, 0.68, 0.58), BotColor.fallback("atlas"))
        assertEquals(0x8b5cf6, BotColor.fallback("default"))
        assertEquals(0x8b5cf6, BotColor.rgb("#8b5cf6"))
        assertEquals(0x88aaff, BotColor.rgb("#8af"))
        assertEquals(BotColor.hsl(30.0, 0.68, 0.58), BotColor.rgb("hsl(30 68% 58%)"))
        assertEquals(BotColor.hsl(30.0, 0.68, 0.58), BotColor.rgb("hsla(30, 68%, 58%, 1)"))
        assertEquals(12, BotColor.SWATCHES.size)
        assertNull(BotColor.rgb("not a colour"))
    }

    @Test
    fun `the roster parses a row's look, chat and revision`() {
        val roster = BotRoster.parse(
            Json.parseToJsonElement(
                """{"bot_mode_protocol":true,"profiles":[{"name":"atlas","display_name":"Atlas","has_avatar":true,
                   "ui_meta_revisions":{"hermes-bots":3},"ui_meta":{"hermes-bots":{"title":"Atlas Prime","pinned":true,
                   "color":"hsl(30 68% 58%)","groups":["x"]}},"canonical_session":{"id":"s1","resolved_id":"s2",
                   "last_active":1700000000.0,"preview":"hi"},"previous_names":["old-atlas"]},
                   {"name":"ghost","ui_meta":{"hermes-bots":{"hidden":true}}},{"display_name":"no name"}]}""",
            ),
        )!!
        assertTrue(roster.protocolInjected)
        assertEquals(listOf("atlas", "ghost"), roster.bots.map { it.name })
        val atlas = roster.bots.first()
        assertEquals("Atlas Prime", atlas.label)
        assertEquals(3, atlas.metaRevision)
        assertEquals("s2", atlas.canonicalSession?.resolvedId)
        assertEquals(listOf("atlas"), roster.visible.map { it.name })
        assertEquals(atlas, roster.find("OLD-ATLAS"))
        assertNull(roster.bots[1].metaRevision)
    }

    @Test
    fun `a look write keeps other clients' fields and drops what never travels`() {
        val existing = Json.parseToJsonElement(
            """{"title":"A","groups":["g"],"image":"data:…","pinned":false}""",
        ).jsonObject
        val merged = BotMeta.merged(existing, mapOf("pinned" to JsonPrimitive(true), "title" to null))
        assertEquals(setOf("groups", "pinned"), merged.keys)
        assertEquals(JsonPrimitive(true), merged["pinned"])
        assertEquals(setOf("x"), BotMeta.merged(JsonObject(emptyMap()), mapOf("x" to JsonNull)).keys)
    }

    @Test
    fun `a bot's chat is found by its title, the roster's one first, and never forked`() {
        val rows = listOf(
            BotChatRow("a", null, "Bot Chat", null),
            BotChatRow("b", "b2", "Bot Chat", null),
            BotChatRow("c", null, "Other", null),
        )
        assertEquals(BotChatResolution.Existing("b", "b2"), BotChatResolver.resolve(rows, rosterCanonicalId = "b2"))
        assertEquals(BotChatResolution.Existing("a", "a"), BotChatResolver.resolve(rows, rosterCanonicalId = null))
        val renamed = listOf(BotChatRow("r", null, "x", "Bot Chat"))
        assertEquals(BotChatResolution.Existing("r", "r"), BotChatResolver.resolve(renamed, null))
        assertEquals(BotChatResolution.Create, BotChatResolver.resolve(listOf(rows[2]), null))
        assertEquals(BotChatResolution.Unconfirmed, BotChatResolver.resolve(emptyList(), rosterCanonicalId = "gone"))
    }

    @Test
    fun `mentions resolve by handle, friendly forms and old names, never ambiguously`() {
        val roster = listOf(
            bot("researcher", title = "Research Buddy"),
            bot("atlas", displayName = "Atlas Prime"),
            bot("default"),
            bot("twin-a", title = "Twin"),
            bot("twin-b", title = "Twin"),
            bot("scout", previous = listOf("finder")),
        )
        fun names(text: String, listener: String? = null) = BotMentions.resolve(text, roster, listener).map { it.name }

        assertEquals(listOf("researcher"), names("@researcher have a look"))
        assertEquals(listOf("researcher"), names("@research-buddy please"))
        assertEquals(listOf("researcher"), names("@researchbuddy please"))
        assertEquals(listOf("atlas"), names("@atlas-prime go"))
        assertEquals(listOf("default"), names("ask @hermes"))
        assertEquals(emptyList(), names("@twin hi"))
        assertEquals(listOf("scout"), names("@finder there?"))
        assertEquals(emptyList(), names("mail me at user@researcher.io"))
        assertEquals(emptyList(), names("`@researcher` and ```\n@atlas\n```"))
        assertEquals(emptyList(), names("@researcher hi", listener = "researcher"))
        assertEquals(emptyList(), names("@researcher@desk hi"))
    }

    @Test
    fun `the note names each bot, and quoted lines don't count`() {
        val roster = listOf(bot("researcher", title = "Research Buddy"), bot("atlas"))
        val note = BotMentions.annotation("@research-buddy and @atlas, look", roster, listener = null)!!
        assertTrue(
            note.startsWith(
                "@research-buddy and @atlas, look\n\n[@mentions resolved from the Bot Mode roster — the user is " +
                    "referring to: " +
                    "@researcher = agent profile \"researcher\" (\"Research Buddy\"); @atlas = agent profile " +
                    "\"atlas\". ",
            ),
        )
        assertTrue(note.endsWith("say so.]"))
        assertNull(BotMentions.annotation("> @atlas said so\nok", roster, listener = null))
        assertNull(BotMentions.annotation("nothing here", roster, listener = null))
    }

    @Test
    fun `tags and name forms follow upstream`() {
        assertEquals("research-buddy", BotMentions.tag(bot("a", title = "Research Buddy")))
        assertEquals("researcher", BotMentions.tag(bot("researcher")))
        assertEquals("hermes", BotMentions.handle(bot("default")))
        assertEquals(emptyList(), BotMentions.nameForms("All"))
        assertEquals(emptyList(), BotMentions.nameForms("Everyone"))
        assertEquals(emptyList(), BotMentions.nameForms("Hermes!"))
        assertEquals(emptyList(), BotMentions.nameForms("   "))
    }

    @Test
    fun `the composer completes the @ being typed with the other bots`() {
        assertEquals("", MentionAutocomplete.query("hey @"))
        assertEquals("res", MentionAutocomplete.query("@Res"))
        assertNull(MentionAutocomplete.query("mail@res"))
        assertNull(MentionAutocomplete.query("@res there"))
        assertNull(MentionAutocomplete.query("no at"))

        val roster = listOf(
            bot("researcher", title = "Research Buddy"),
            bot("atlas"),
            bot("hidden", hidden = true),
            bot("twin-a", title = "Twin"),
        )
        val candidates = MentionAutocomplete.bots(roster, listener = "atlas")
        assertEquals(listOf("research-buddy", "twin"), candidates.map { it.tag })
        assertEquals(listOf("research-buddy"), MentionAutocomplete.filter(candidates, "bud").map { it.tag })
        assertEquals("ask @research-buddy ", MentionAutocomplete.complete("ask @re", candidates.first()))
    }
}
