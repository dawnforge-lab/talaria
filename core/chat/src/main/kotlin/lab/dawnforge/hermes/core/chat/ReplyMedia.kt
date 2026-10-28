package lab.dawnforge.hermes.core.chat

/** What a file the agent names is, from its extension: how the chat shows it. */
enum class MediaKind { Image, Audio, Video, Page, File }

/**
 * A piece of a reply: Markdown; a file the agent named with a `MEDIA:` tag; or a block the chat
 * draws itself (a formula, a Mermaid diagram, a page to preview).
 */
sealed interface ReplyPart {
    data class Markdown(val text: String) : ReplyPart

    data class Media(val path: String, val label: String?, val kind: MediaKind) : ReplyPart

    /** LaTeX in display mode: `$$…$$`, or `\[ … \]` on lines of their own. */
    data class Math(val source: String) : ReplyPart

    /** A ```` ```mermaid ```` fence. */
    data class Diagram(val source: String) : ReplyPart

    /** `::preview{file="/path/page.html"}`: an HTML page on the host, drawn in place. */
    data class Page(val path: String) : ReplyPart
}

/** A file's kind, by its extension, as the iOS app and Hermes' gateway tell them apart. */
fun mediaKind(path: String): MediaKind {
    val ext = path.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase()
    return when (ext) {
        in IMAGE_EXT -> MediaKind.Image
        in AUDIO_EXT -> MediaKind.Audio
        in VIDEO_EXT -> MediaKind.Video
        "html", "htm", "xhtml" -> MediaKind.Page
        else -> MediaKind.File
    }
}

/**
 * Splits a reply around what the chat shows apart from its Markdown, as the iOS app does:
 * - `MEDIA:` tags, each a file on the Hermes host (`MEDIA:/path/x.png`, quoted when it has spaces,
 *   or `![label](MEDIA:/path)`);
 * - display formulas (`$$…$$`, or `$$` and `\[`/`\]` on lines of their own) and Mermaid fences;
 * - `::preview{file="…"}` pages.
 *
 * What is in code, inline code or a quote stays text. The voice and document directives Hermes adds
 * for messaging apps are dropped.
 */
fun splitReply(reply: String): List<ReplyPart> {
    if (BLOCK_HINTS.none { reply.contains(it, ignoreCase = true) }) return listOf(ReplyPart.Markdown(reply))
    val parts = mutableListOf<ReplyPart>()
    val text = StringBuilder()
    fun flush() {
        val chunk = text.toString().trimStart('\n').trimEnd()
        if (chunk.isNotBlank()) parts += ReplyPart.Markdown(chunk)
        text.clear()
    }
    var fence: String? = null
    // A formula or diagram being collected: how it ends, what it becomes, and its lines so far.
    var block: Pair<String, (String) -> ReplyPart>? = null
    val blockLines = mutableListOf<String>()
    for (line in reply.lines()) {
        val trimmed = line.trimStart()
        val open = block
        if (open != null) {
            if (trimmed.trimEnd() == open.first || (open.first == "```" && trimmed.startsWith("```"))) {
                parts += open.second(blockLines.joinToString("\n").trim())
                blockLines.clear()
                block = null
            } else {
                blockLines += line
            }
            continue
        }
        if (fence == null) {
            val blockStart = when {
                MERMAID_FENCE.matches(trimmed.trimEnd()) -> "```" to { source: String -> ReplyPart.Diagram(source) }
                trimmed.trimEnd() == "$$" -> "$$" to { source: String -> ReplyPart.Math(source) }
                trimmed.trimEnd() == "\\[" -> "\\]" to { source: String -> ReplyPart.Math(source) }
                else -> null
            }
            if (blockStart != null) {
                flush()
                block = blockStart
                continue
            }
            val oneLine = trimmed.trimEnd()
            if (oneLine.length > MIN_ONE_LINE_MATH && oneLine.startsWith("$$") && oneLine.endsWith("$$")) {
                flush()
                parts += ReplyPart.Math(oneLine.removeSurrounding("$$").trim())
                continue
            }
            val preview = PREVIEW.find(line)
            val file = preview?.let {
                PREVIEW_FILE.find(it.groupValues[1])
            }?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            if (preview != null && file != null && !trimmed.startsWith(">") &&
                outsideInlineCode(line, preview.range.first)
            ) {
                text.append(line.substring(0, preview.range.first)).append('\n')
                flush()
                parts += if (mediaKind(file) == MediaKind.Page) {
                    ReplyPart.Page(file)
                } else {
                    ReplyPart.Media(file, null, mediaKind(file))
                }
                val after = line.substring(preview.range.last + 1)
                if (after.isNotBlank()) text.append(after).append('\n')
                continue
            }
        }
        val opener = FENCE.find(trimmed)?.value
        if (fence == null && opener != null) {
            fence = opener.take(3)
        } else if (fence != null && trimmed.startsWith(fence)) {
            fence = null
            text.append(line).append('\n')
            continue
        }
        if (fence != null || trimmed.startsWith(">") || !line.contains("MEDIA:", ignoreCase = true)) {
            val kept = if (fence == null) line.replace(DIRECTIVES, "") else line
            if (fence != null || kept.isNotBlank() || line.isBlank()) text.append(kept).append('\n')
            continue
        }
        var rest = line.replace(DIRECTIVES, "")
        var found = false
        while (true) {
            val match = findTag(rest) ?: break
            found = true
            text.append(rest.substring(0, match.start)).append('\n')
            flush()
            parts += ReplyPart.Media(match.path, match.label, mediaKind(match.path))
            rest = rest.substring(match.end)
        }
        if (!found || rest.isNotBlank()) text.append(rest).append('\n')
    }
    // A block never closed is the text it was.
    block?.let { open ->
        text.append(
            if (open.first == "```") {
                "```mermaid\n"
            } else if (open.first == "$$") {
                "$$\n"
            } else {
                "\\[\n"
            },
        )
        blockLines.forEach { text.append(it).append('\n') }
    }
    flush()
    return parts.ifEmpty { listOf(ReplyPart.Markdown(reply)) }
}

private fun outsideInlineCode(line: String, at: Int) = line.substring(0, at).count { it == '`' } % 2 == 0

private class Tag(val start: Int, val end: Int, val path: String, val label: String?)

/** The first tag on [line] outside inline code, the Markdown-wrapped form first. */
private fun findTag(line: String): Tag? {
    // Counted up to the tag's own MEDIA:, since a match may start at a backtick of its own.
    fun outsideCode(match: MatchResult): Boolean {
        val at = line.indexOf("MEDIA:", match.range.first, ignoreCase = true).takeIf { it >= 0 } ?: match.range.first
        return line.substring(0, at).count { it == '`' } % 2 == 0
    }
    WRAPPED.findAll(line).firstOrNull(::outsideCode)?.let { match ->
        return Tag(
            match.range.first,
            match.range.last + 1,
            cleanPath(match.groupValues[2]),
            match.groupValues[1].ifBlank {
                null
            },
        )
    }
    TAG.findAll(line).firstOrNull(::outsideCode)?.let { match ->
        return Tag(match.range.first, match.range.last + 1, cleanPath(match.groupValues[1]), null)
    }
    WHOLE_LINE.find(line)?.let { match ->
        val path = cleanPath(match.groupValues[1])
        if (!path.contains(' ') ||
            LAST_NAME_EXT.containsMatchIn(path)
        ) {
            return Tag(match.range.first, match.range.last + 1, path, null)
        }
    }
    return null
}

private fun cleanPath(raw: String): String {
    var path = raw.trim()
    val quotes = setOf('`', '"', '\'')
    if (path.length >= 2 && path.first() in quotes &&
        path.last() == path.first()
    ) {
        path = path.substring(1, path.length - 1)
    }
    return path.trimStart('`', '"', '\'').trimEnd('`', '"', '\'', ',', '.', ';', ':', ')', '}', ']').trim()
}

/**
 * Hermes' references to other chats, `@session:<profile>/<id>` or `@session:<id>`, as Markdown
 * links to [SESSION_LINK_SCHEME] (`hermes-session://open?id=…&profile=…`) labelled [label]. Code
 * is left alone.
 */
fun linkSessions(markdown: String, label: String): String {
    if (!markdown.contains("@session:")) return markdown
    var fence = false
    return markdown.lines().joinToString("\n") { line ->
        if (FENCE.containsMatchIn(line.trimStart())) {
            fence = !fence
            return@joinToString line
        }
        if (fence) return@joinToString line
        val asTarget = SESSION_TARGET.replace(line) { match ->
            "](${sessionLink(match.groupValues[1], match.groupValues[2])})"
        }
        SESSION_REF.replace(asTarget) { match ->
            if (asTarget.substring(0, match.range.first).count { it == '`' } % 2 == 1) {
                match.value
            } else {
                "[$label](${sessionLink(match.groupValues[1], match.groupValues[2])})"
            }
        }
    }
}

private fun sessionLink(profile: String, id: String): String =
    "$SESSION_LINK_SCHEME://open?id=$id" + if (profile.isNotEmpty()) "&profile=$profile" else ""

/** The scheme of the links [linkSessions] writes; the chat screen opens them itself. */
const val SESSION_LINK_SCHEME = "hermes-session"

/** A session link's chat: its id, and its profile when the link names one. */
fun parseSessionLink(uri: String): Pair<String?, String>? {
    if (!uri.startsWith("$SESSION_LINK_SCHEME://open?")) return null
    val params = uri.substringAfter('?').split('&').mapNotNull { pair ->
        pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }.toMap()
    val id = params["id"]?.takeIf(String::isNotEmpty) ?: return null
    return params["profile"] to id
}

/** `💾 Self-improvement review: Memory ➕ … · Skill ✏️ …`: what the review changed, one line each. */
fun reviewText(summary: String): String =
    summary.substringAfter("review:", summary).trim().split(" · ").map(String::trim).filter(String::isNotEmpty)
        .joinToString("\n") { "- $it" }

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "tif", "heic", "heif")
private val AUDIO_EXT = setOf("mp3", "m2a", "wav", "ogg", "oga", "opus", "m4a", "aac", "flac", "caf", "aiff", "aif")
private val VIDEO_EXT = setOf("mp4", "mov", "m4v", "avi", "mkv", "webm", "3gp")
private val DOC_EXT = listOf(
    "pdf", "docx", "doc", "odt", "rtf", "txt", "md", "epub", "xlsx", "xls", "ods", "csv", "tsv", "json", "xml",
    "yaml", "yml", "kmz", "kml", "geojson", "gpx", "pptx", "ppt", "odp", "key", "pages", "numbers", "zip", "tar",
    "gz", "tgz", "bz2", "xz", "7z", "rar", "apk", "ipa", "html", "htm", "svg",
)
private val EXTENSIONS = (IMAGE_EXT + AUDIO_EXT + VIDEO_EXT + DOC_EXT).sortedByDescending(String::length)
    .joinToString("|") { Regex.escape(it) }

/** A path: quoted, or absolute (`/`, `~/`, `C:\`) and ending in a known extension. */
private val TAG_PATH =
    """(`[^`\n]+?`|"[^"\n]+?"|'[^'\n]+?'|(?:~/|/|[A-Za-z]:[/\\])\S+?(?:[^\S\n]+\S+?)*?\.(?:$EXTENSIONS))"""
private const val TAG_END = """(?=[\s`"'*_,;:)\]}\[]|MEDIA:|\.(?:\s|$)|$)[`"'*_]{0,3}\.?"""
private val TAG = Regex("""[`"'*_]{0,3}MEDIA:[^\S\n]*""" + TAG_PATH + TAG_END, RegexOption.IGNORE_CASE)
private val WRAPPED = Regex(
    """!?\[([^\]]*)\]\([^\S\n]*MEDIA:[^\S\n]*([^)]+?)""" + """(?:[^\S\n]+(?:"[^"]*"|'[^']*'))?[^\S\n]*\)""",
    RegexOption.IGNORE_CASE,
)
private val WHOLE_LINE = Regex(
    """^[`"'*_]{0,3}MEDIA:[^\S\n]*((?:~/|/|[A-Za-z]:[/\\]).*?)[`"'*_]{0,3}$""",
    RegexOption.IGNORE_CASE,
)
private val LAST_NAME_EXT = Regex("""\.[A-Za-z0-9]{1,10}$""")

// Android's regex engine (ICU) wants literal brackets and braces escaped, where the JVM's doesn't.
private val DIRECTIVES = Regex("""\[\[(?:audio_as_voice|as_document)\]\]""")
private val FENCE = Regex("^(```|~~~)")
private val MERMAID_FENCE = Regex("^```\\s*mermaid\\s*$", RegexOption.IGNORE_CASE)
private val PREVIEW = Regex("""(?:^|(?<=\s))::preview\{([^{}\n]{0,1024})\}""")
private val PREVIEW_FILE = Regex("""(?:^|\s)file=(?:"([^"]*)"|'([^']*)')""")

/** A display formula on one line needs more than its `$$` pairs. */
private const val MIN_ONE_LINE_MATH = 4

/** What a reply must hold for [splitReply] to have anything to split. */
private val BLOCK_HINTS = listOf("MEDIA:", "[[", "$$", "\\[", "```mermaid", "::preview")
private val SESSION_REF = Regex(
    """(?<![\w/`@])@session:(?:([A-Za-z0-9][A-Za-z0-9_-]{0,63})/)?([A-Za-z0-9_](?:[A-Za-z0-9_.:-]{0,126}[A-Za-z0-9_])?)""",
)
private val SESSION_TARGET = Regex(
    """\]\(@session:(?:([A-Za-z0-9][A-Za-z0-9_-]{0,63})/)?([A-Za-z0-9_](?:[A-Za-z0-9_.:-]{0,126}[A-Za-z0-9_])?)\)""",
)
