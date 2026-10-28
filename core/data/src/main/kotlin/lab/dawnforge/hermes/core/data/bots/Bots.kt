package lab.dawnforge.hermes.core.data.bots

import java.text.Normalizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** A bot's one ongoing conversation (the session titled "Bot Chat"), as the roster reports it. */
data class BotSession(
    val id: String,
    /** The tip after compression: what to open. */
    val resolvedId: String?,
    val lastActive: Double?,
    val preview: String?,
)

/**
 * A bot: a Hermes profile with its look in `ui_meta['hermes-bots']`, its picture in the profile's
 * `avatar` asset and its personality in SOUL.md, as Hermes Desktop and iOS keep them.
 */
data class BotProfile(
    val name: String,
    val title: String?,
    val displayName: String?,
    val description: String,
    val hasAvatar: Boolean,
    val pinned: Boolean,
    /** Hidden from the roster by another client; still a bot. */
    val hidden: Boolean,
    /** A CSS colour string (`hsl(…)`, `#rrggbb`). */
    val color: String?,
    val canonicalSession: BotSession?,
    val lastActive: Double?,
    val lastPreview: String?,
    val previousNames: List<String>,
    val isDefault: Boolean,
    /** The whole `hermes-bots` object, carried over on every write. */
    val meta: JsonObject,
    /** Its compare-and-swap revision; null on a host without one. */
    val metaRevision: Int?,
) {
    /** The name shown: the bot's title, else the profile's display name, else its id. */
    val label: String
        get() = title?.trim()?.takeIf(String::isNotEmpty) ?: displayName?.trim()?.takeIf(String::isNotEmpty) ?: name

    val isDeletable: Boolean get() = !isDefault && !name.equals(DEFAULT, ignoreCase = true)

    /** When it last spoke: its Bot Chat, else its newest chat. */
    val activity: Double? get() = canonicalSession?.lastActive ?: lastActive

    companion object {
        const val DEFAULT = "default"
        const val META_KEY = "hermes-bots"

        /** The wire title of a bot's one conversation; never translated. */
        const val CHAT_TITLE = "Bot Chat"

        fun parse(row: JsonObject): BotProfile? {
            val name = row.text("name")?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val meta = ((row["ui_meta"] as? JsonObject)?.get(META_KEY) as? JsonObject) ?: JsonObject(emptyMap())
            val revisions = row["ui_meta_revisions"] as? JsonObject
            val canonical = (row["canonical_session"] as? JsonObject)?.let { session ->
                session.text("id")?.let { id ->
                    BotSession(id, session.text("resolved_id"), session.number("last_active"), session.text("preview"))
                }
            }
            val last = row["last_session"] as? JsonObject
            return BotProfile(
                name = name,
                title = meta.text("title"),
                displayName = row.text("display_name"),
                description = row.text("description").orEmpty(),
                hasAvatar = row.flag("has_avatar") == true,
                pinned = meta.flag("pinned") == true,
                hidden = meta.flag("hidden") == true,
                color = meta.text("color"),
                canonicalSession = canonical,
                lastActive = last?.number("last_active"),
                lastPreview = last?.text("preview"),
                previousNames = (row["previous_names"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) },
                isDefault = row.flag("is_default") == true,
                meta = meta,
                metaRevision = revisions?.let { (it[META_KEY] as? JsonPrimitive)?.intOrNull ?: 0 },
            )
        }
    }
}

/** The bots on a host, and whether it adds the teammate-messaging protocol to every session itself. */
data class BotRoster(val bots: List<BotProfile>, val protocolInjected: Boolean) {
    /** What the roster shows: pinned first, then the most recently active, then by name. */
    val visible: List<BotProfile>
        get() = bots.filterNot(BotProfile::hidden).sortedWith(
            compareByDescending<BotProfile> { it.pinned }
                .thenByDescending { it.activity?.let(::seconds) ?: Double.MIN_VALUE }
                .thenBy { it.name },
        )

    fun find(name: String): BotProfile? = bots.firstOrNull { it.name == name }
        ?: bots.firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: bots.filter { bot -> bot.previousNames.any { it.equals(name, ignoreCase = true) } }.singleOrNull()

    companion object {
        fun parse(json: JsonElement): BotRoster? {
            val reply = json as? JsonObject ?: return null
            val rows = (reply["profiles"] as? JsonArray ?: return null).mapNotNull { it as? JsonObject }
            return BotRoster(
                rows.mapNotNull(BotProfile::parse),
                protocolInjected = reply.flag("bot_mode_protocol") == true,
            )
        }

        /** Activity times are seconds, but some hosts send milliseconds. */
        private fun seconds(value: Double) = if (value > MILLIS_THRESHOLD) value / MILLIS else value
        private const val MILLIS_THRESHOLD = 1e10
        private const val MILLIS = 1000.0
    }
}

/**
 * The profile id a bot's typed name becomes, as iOS makes it (`BotProfileSlug`): accented Latin
 * folds to its base letters, any other letter or digit becomes a `u<hex>` token (so a CJK name
 * still has an id), then lowercase `[a-z0-9_-]`, at most 64 characters.
 */
object BotSlug {
    const val MAX_LENGTH = 64
    private val VALID = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

    fun of(raw: String): String {
        val source = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
        val folded = StringBuilder()
        var index = 0
        while (index < source.length) {
            val point = source.codePointAt(index)
            index += Character.charCount(point)
            if (!Character.isLetterOrDigit(point)) {
                folded.appendCodePoint(point)
                continue
            }
            val base = Normalizer.normalize(String(Character.toChars(point)), Normalizer.Form.NFKD)
                .filter { Character.getType(it).toByte() !in MARKS }
            if (base.isNotEmpty() && base.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) {
                folded.append(base)
            } else {
                folded.append("-u").append(point.toString(HEX)).append('-')
            }
        }
        val slug = StringBuilder()
        var pendingDash = false
        for (char in folded.toString().lowercase()) {
            if (char in 'a'..'z' || char in '0'..'9' || char == '_' || char == '-') {
                if (pendingDash && slug.isNotEmpty()) slug.append('-')
                pendingDash = false
                slug.append(char)
            } else {
                pendingDash = true
            }
        }
        val collapsed = slug.toString().replace(Regex("-{2,}"), "-").trim('-')
        if (collapsed.length <= MAX_LENGTH) return collapsed
        // Cut at the last token boundary, so no `u<hex>` token is split.
        if (collapsed[MAX_LENGTH] == '-') return collapsed.take(MAX_LENGTH).trim('-')
        val cut = collapsed.take(MAX_LENGTH)
        val boundary = cut.lastIndexOf('-')
        return if (boundary > 0) cut.substring(0, boundary).trim('-') else cut
    }

    fun isValid(slug: String): Boolean = VALID.matches(slug)

    /** The title a name implies: what was typed, unless it already reads exactly like the id. */
    fun title(raw: String): String {
        val name = raw.trim()
        return if (name == of(name)) "" else name
    }

    private const val HEX = 16
    private val MARKS = setOf(Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK)
}

/**
 * The SOUL.md a new bot starts with, as iOS composes it (`BotSoul`): the user's own, or a short
 * identity from its name and description. A host that adds the teammate-messaging protocol to every
 * session itself (`bot_mode_protocol`) doesn't get it in SOUL.md as well.
 */
object BotSoul {
    const val PROTOCOL_HEADING = "## Messaging other agents"

    fun compose(
        name: String,
        title: String,
        description: String,
        customSoul: String,
        protocolInjected: Boolean,
        roster: List<BotProfile>,
    ): String {
        val custom = customSoul.trim()
        if (custom.isNotEmpty()) {
            if (protocolInjected || PROTOCOL_HEADING in custom) return custom
            return custom + "\n\n" + protocol(name, roster)
        }
        val display = title.trim().ifEmpty { name }
        val mission = description.trim()
        val lines = mutableListOf("# $display", "")
        if (mission.isNotEmpty()) lines += listOf("**Mission:** $mission", "")
        lines += listOf(
            "You are $display, a persistent named agent (profile `$name`) on this machine.",
            "You keep your own memory, skills, and conversation history across sessions.",
        )
        val identity = lines.joinToString("\n")
        return if (protocolInjected) identity else identity + "\n\n" + protocol(name, roster)
    }

    /** Hermes Desktop's messaging section, for a host that doesn't add it itself. */
    fun protocol(name: String, roster: List<BotProfile>): String {
        val teammates = roster.filter { it.name != name && !it.hidden }
        val lines = mutableListOf(
            PROTOCOL_HEADING,
            "",
            "You work alongside other named agents. Every agent (including you) has",
            "ONE canonical conversation titled \"Bot Chat\" — created with the agent,",
            "so it always exists. Agent-to-agent messages are delivered straight",
            "into it, like a DM. To message a teammate, run:",
            "",
            "```",
            "hermes -p <agent-name> chat --in ~ -c \"Bot Chat\" --create-if-missing -Q -q \"Message from 🤖 $name " +
                "(@$name): your message\"",
            "",
            "Run the send with background=true and notify_on_complete=true on the",
            "terminal tool, then finish your turn — the reply arrives later as a",
            "background process notification. Never block waiting for it.",
            "```",
            "",
            "(`--in ~ -c \"Bot Chat\" --create-if-missing` resumes their canonical",
            "conversation in the home workspace, creating it if the target has no",
            "\"Bot Chat\" yet. `-Q` keeps output clean. Always open with the",
            "\"Message from 🤖 $name (@$name):\" prefix so they know",
            "who is talking (the @handle lets the app show your avatar to them).",
            "Their reply prints to stdout — relay the relevant part back to the",
            "user, and say which agent it came from.)",
            "",
            "If a message in YOUR chat starts with \"Message from 🤖 <name>\", it is",
            "a teammate messaging you, not the user. Answer it directly — your reply",
            "reaches them via their own delivery — and use the same command if you",
            "need to start a conversation yourself.",
            "",
            "When the user writes @<agent-name> or says \"ask <name> to ...\" /",
            "\"tell <name> ...\", that is a handoff: message that agent, wait for the",
            "reply, and report back.",
            "",
            "The roster grows over time — run `hermes profile list` for the LIVE",
            "teammate list before a handoff. Teammates when you were created:",
        )
        if (teammates.isEmpty()) {
            lines += "- (none yet)"
        } else {
            lines += teammates.map { bot ->
                val detail = bot.description.trim()
                if (detail.isEmpty()) "- `${bot.name}`" else "- `${bot.name}` — $detail"
            }
        }
        return lines.joinToString("\n")
    }
}

/** A bot's colour, as Desktop reads and writes it: CSS strings, its twelve swatches, a hue from the name. */
object BotColor {
    /** Desktop's swatches: twelve hues at the profile palette's saturation and lightness. */
    val SWATCHES: List<String> = (0 until SWATCH_COUNT).map { "hsl(${it * HUE_STEP} 68% 58%)" }

    /** Desktop's fixed look for the primary profile. */
    const val PRIMARY = "#8b5cf6"

    /** An `0xRRGGBB` colour from a CSS string; null for one it can't read. */
    fun rgb(css: String?): Int? {
        val value = css?.trim()?.lowercase() ?: return null
        if (value.startsWith("#")) return hex(value.drop(1))
        NAMED[value]?.let { return it }
        if (!value.startsWith("hsl")) return null
        val open = value.indexOf('(')
        val close = value.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        val parts = value.substring(open + 1, close).split(' ', ',', '/')
            .map { it.trim().trimEnd('%').removeSuffix("deg") }
            .filter(String::isNotEmpty)
        if (parts.size < HSL_PARTS) return null
        val hue = parts[0].toDoubleOrNull() ?: return null
        val saturation = parts[1].toDoubleOrNull() ?: return null
        val lightness = parts[2].toDoubleOrNull() ?: return null
        return hsl(hue, saturation / PERCENT, lightness / PERCENT)
    }

    /** Desktop's `profileColor(name)`: a hue from the name's hash; `default` is a fixed violet. */
    fun fallback(name: String): Int {
        val key = name.trim()
        if (key.isEmpty() || key == BotProfile.DEFAULT) return hex(PRIMARY.drop(1))!!
        var hash = 0
        for (unit in key) hash = hash * HASH_MULTIPLIER + unit.code
        return hsl((hash.toLong() and UINT_MASK).rem(FULL_CIRCLE), SATURATION, LIGHTNESS)
    }

    /** The colour a bot shows: its own, else its name's. */
    fun of(bot: BotProfile): Int = rgb(bot.color) ?: fallback(bot.name)

    fun hsl(hue: Double, saturation: Double, lightness: Double): Int {
        val h = ((hue % FULL_CIRCLE) + FULL_CIRCLE) % FULL_CIRCLE / FULL_CIRCLE
        val s = saturation.coerceIn(0.0, 1.0)
        val l = lightness.coerceIn(0.0, 1.0)
        if (s == 0.0) return pack(l, l, l)
        val q = if (l < HALF) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun channel(t0: Double): Double {
            var t = t0
            if (t < 0) t += 1
            if (t > 1) t -= 1
            return when {
                t < 1.0 / SIXTH -> p + (q - p) * SIXTH * t
                t < HALF -> q
                t < 2.0 / THIRD -> p + (q - p) * (2.0 / THIRD - t) * SIXTH
                else -> p
            }
        }
        return pack(channel(h + 1.0 / THIRD), channel(h), channel(h - 1.0 / THIRD))
    }

    private fun pack(red: Double, green: Double, blue: Double): Int =
        (byte(red) shl RED_SHIFT) or (byte(green) shl GREEN_SHIFT) or byte(blue)

    private fun byte(channel: Double) = (channel * MAX_CHANNEL + ROUND).toInt()

    private fun hex(hex: String): Int? {
        val digits = if (hex.length == SHORT_HEX) hex.map { "$it$it" }.joinToString("") else hex
        if (digits.length != LONG_HEX) return null
        return digits.toIntOrNull(HEX)
    }

    private val NAMED = mapOf(
        "blue" to 0x0A84FF, "brown" to 0xAC8E68, "cyan" to 0x64D2FF, "green" to 0x30D158,
        "indigo" to 0x5E5CE6, "mint" to 0x63E6E2, "orange" to 0xFF9F0A, "pink" to 0xFF375F,
        "purple" to 0xBF5AF2, "red" to 0xFF453A, "teal" to 0x40C8E0, "yellow" to 0xFFD60A,
    )
    private const val SWATCH_COUNT = 12
    private const val HUE_STEP = 30
    private const val HSL_PARTS = 3
    private const val PERCENT = 100.0
    private const val HASH_MULTIPLIER = 31
    private const val UINT_MASK = 0xFFFFFFFFL
    private const val FULL_CIRCLE = 360.0
    private const val SATURATION = 0.68
    private const val LIGHTNESS = 0.58
    private const val HALF = 0.5
    private const val THIRD = 3.0
    private const val SIXTH = 6.0
    private const val MAX_CHANNEL = 255.0
    private const val ROUND = 0.5
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val SHORT_HEX = 3
    private const val LONG_HEX = 6
    private const val HEX = 16
}

/** Where a bot's one chat is: an existing session to open, or none yet (create it). */
sealed interface BotChatResolution {
    data class Existing(val id: String, val resumeId: String) : BotChatResolution

    data object Create : BotChatResolution

    /** The roster says it had one but the lookup found none: never make a second. */
    data object Unconfirmed : BotChatResolution
}

/** A session row from the "Bot Chat" lookup. */
data class BotChatRow(val id: String, val resolvedId: String?, val title: String?, val rootTitle: String?) {
    val isCanonical: Boolean get() = (rootTitle ?: title) == BotProfile.CHAT_TITLE
}

/** Upstream's `findExistingCanonicalChat`: which chat a bot's chat is, never forking it. */
object BotChatResolver {
    fun resolve(rows: List<BotChatRow>, rosterCanonicalId: String?): BotChatResolution {
        val canonical = rows.filter(BotChatRow::isCanonical)
        val confirmed = rosterCanonicalId?.trim()?.takeIf(String::isNotEmpty)
        if (canonical.isNotEmpty()) {
            val pinned = confirmed?.let { id -> canonical.firstOrNull { it.id == id || it.resolvedId == id } }
            val match = pinned ?: canonical.first()
            return BotChatResolution.Existing(match.id, match.resolvedId ?: match.id)
        }
        return if (confirmed != null) BotChatResolution.Unconfirmed else BotChatResolution.Create
    }
}

/** The whole `hermes-bots` value to write: the gateway replaces the key, so other clients' fields are kept. */
object BotMeta {
    private val STRIPPED = setOf("image", "pet", "chat")

    fun merged(existing: JsonObject, patch: Map<String, JsonElement?>): JsonObject {
        val result = existing.toMutableMap()
        for ((key, value) in patch) if (value == null) result.remove(key) else result[key] = value
        STRIPPED.forEach(result::remove)
        return JsonObject(result)
    }
}

/**
 * Bot @mentions in a chat, ported from Hermes Desktop's composer middleware (as iOS has it): the
 * text goes unchanged plus a note telling the chat's agent which teammate each `@tag` means; the
 * agent messages them itself (`message_agent`). Unknown or ambiguous tags and e-mail addresses stay
 * plain text.
 */
object BotMentions {
    const val MAX_ANNOTATED = 16
    private val RESERVED = setOf("all", "everyone", "user", "default", "hermes")
    private val SCAN = Regex(
        "(^|\\s)@([a-z0-9][a-z0-9_-]*)(?:@([a-z0-9][a-z0-9_-]*))?",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val FENCED = Regex("```[\\s\\S]*?```")
    private val INLINE = Regex("`[^`\\n]*`")

    private sealed interface Owner {
        data class Bot(val bot: BotProfile) : Owner

        data object Ambiguous : Owner
    }

    /** The text to send: with the note when a tag resolves, else null (send it as typed). */
    fun annotation(text: String, roster: List<BotProfile>, listener: String?): String? {
        // Quoted lines are someone else's words.
        val own = text.replace("\r\n", "\n").lines()
            .filterNot { it.trimStart(' ', '\t').startsWith(">") }
            .joinToString("\n")
        if (roster.isEmpty() || !SCAN.containsMatchIn(own)) return null
        val mentioned = resolve(own, roster, listener)
        if (mentioned.isEmpty()) return null
        return annotated(text, mentioned)
    }

    fun resolve(text: String, roster: List<BotProfile>, listener: String?): List<BotProfile> {
        val prose = INLINE.replace(FENCED.replace(text, " "), " ")
        val owners = HashMap<String, Owner>()
        val others = roster.filterNot { isListener(it, listener) }
        for (bot in others) forms(bot).forEach { claim(it, bot, owners) }
        // Old names fill gaps only: a live name outranks another bot's rename history.
        val live = roster.flatMapTo(HashSet()) { forms(it) }
        for (bot in others) {
            for (previous in bot.previousNames) {
                nameForms(previous).filterNot(live::contains).forEach { claim(it, bot, owners) }
            }
        }
        val mentioned = mutableListOf<BotProfile>()
        for (match in SCAN.findAll(prose)) {
            if (match.groups[3] != null) continue
            val token = match.groupValues[2].lowercase()
            val bot = (owners[token] as? Owner.Bot)?.bot ?: continue
            if (mentioned.none { it.name == bot.name }) mentioned += bot
            if (mentioned.size >= MAX_ANNOTATED) break
        }
        return mentioned
    }

    private fun claim(form: String, bot: BotProfile, owners: MutableMap<String, Owner>) {
        when (val existing = owners[form]) {
            null -> owners[form] = Owner.Bot(bot)
            is Owner.Bot -> if (existing.bot.name != bot.name) owners[form] = Owner.Ambiguous
            Owner.Ambiguous -> Unit
        }
    }

    fun annotated(text: String, mentions: List<BotProfile>): String {
        if (mentions.isEmpty()) return text
        val lines = mentions.joinToString("; ") { bot ->
            val title = bot.title?.trim().orEmpty()
            "@${handle(bot)} = agent profile \"${bot.name}\"" + if (title.isEmpty()) "" else " (\"$title\")"
        }
        return text + "\n\n[@mentions resolved from the Bot Mode roster — the user is referring to: " + lines +
            ". If they want one of these agents contacted, compose your own message and send it with your " +
            "message_agent " +
            "tool (agents on other connected machines are reachable too — the Desktop relays it); never forward the " +
            "user’s text verbatim. If this session has no message_agent tool, agent messaging is unavailable here — " +
            "say so.]"
    }

    /** The profile name, except the primary profile, which answers to `@hermes`. */
    fun handle(bot: BotProfile): String {
        val name = bot.name.trim()
        return if (name.equals(BotProfile.DEFAULT, ignoreCase = true)) "hermes" else name
    }

    /** The tag the autocomplete inserts: the first friendly form, else the handle. */
    fun tag(bot: BotProfile): String =
        listOf(bot.title, bot.displayName).firstNotNullOfOrNull { nameForms(it).firstOrNull() } ?: handle(bot)

    fun forms(bot: BotProfile): Set<String> = buildSet {
        add(handle(bot).lowercase())
        add(bot.name.trim().lowercase())
        listOf(bot.title, bot.displayName).forEach { addAll(nameForms(it)) }
    }

    /** A friendly name's taggable forms: its slug ("research-buddy") and collapsed form ("researchbuddy"). */
    fun nameForms(value: String?): List<String> {
        val name = value?.trim()?.lowercase().orEmpty()
        if (name.isEmpty()) return emptyList()
        val slug = StringBuilder()
        var inRun = false
        for (char in name) {
            if (isToken(char)) {
                slug.append(char)
                inRun = false
            } else if (!inRun) {
                slug.append('-')
                inRun = true
            }
        }
        val collapsed = name.filter(::isToken)
        return listOf(slug.toString().trim('-'), collapsed)
            .filter { isValidToken(it) && it !in RESERVED }
            .distinct()
    }

    /** A tag Hermes can route: `[a-z0-9_-]`, not starting with `_` or `-`. */
    fun isValidToken(value: String): Boolean {
        val first = value.firstOrNull() ?: return false
        return first != '_' && first != '-' && value.all(::isToken)
    }

    private fun isToken(char: Char) = char in 'a'..'z' || char in '0'..'9' || char == '_' || char == '-'

    private fun isListener(bot: BotProfile, listener: String?): Boolean =
        !listener.isNullOrBlank() && bot.name.trim().equals(listener.trim(), ignoreCase = true)
}

/** An @mention the composer can complete: the tag inserted, and who it is. */
data class MentionCandidate(val tag: String, val title: String)

/** The `@query` being typed at the end of a draft, and what it completes to (as iOS's picker). */
object MentionAutocomplete {
    private fun isAsciiAlphanumeric(char: Char) = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9'

    /** The partial tag after a trailing `@` at the start or after a space, lowercased; null when there's none. */
    fun query(text: String): String? {
        val at = text.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0 && !text[at - 1].isWhitespace()) return null
        val query = text.substring(at + 1)
        if (query.isNotEmpty() && !isAsciiAlphanumeric(query[0])) return null
        if (!query.all { isAsciiAlphanumeric(it) || it in "._:-" }) return null
        return query.lowercase()
    }

    fun filter(candidates: List<MentionCandidate>, query: String): List<MentionCandidate> {
        if (query.isEmpty()) return candidates
        return candidates.filter { candidate ->
            candidate.tag.lowercase().startsWith(query) ||
                candidate.title.lowercase().split(Regex("\\s+")).any { it.startsWith(query) }
        }
    }

    /** The draft with its trailing `@query` replaced by `@tag `. */
    fun complete(text: String, candidate: MentionCandidate): String {
        if (query(text) == null) return text
        return text.substring(0, text.lastIndexOf('@')) + "@" + candidate.tag + " "
    }

    /** The roster's bots other than the chat's own, each with a tag only it answers to. */
    fun bots(roster: List<BotProfile>, listener: String?): List<MentionCandidate> {
        val others = roster.filterNot { bot ->
            !listener.isNullOrBlank() && bot.name.trim().equals(listener.trim(), ignoreCase = true)
        }
        val claims = HashMap<String, Int>()
        others.forEach { bot -> BotMentions.forms(bot).forEach { claims[it] = (claims[it] ?: 0) + 1 } }
        val seen = HashSet<String>()
        return others.mapNotNull { bot ->
            if (bot.hidden) return@mapNotNull null
            val tag = listOf(BotMentions.tag(bot), BotMentions.handle(bot)).firstOrNull { claims[it.lowercase()] == 1 }
                ?: return@mapNotNull null
            if (!seen.add(tag.lowercase())) return@mapNotNull null
            MentionCandidate(tag, bot.label)
        }
    }
}

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
