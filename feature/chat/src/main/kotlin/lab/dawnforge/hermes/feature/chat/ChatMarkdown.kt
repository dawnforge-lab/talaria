package lab.dawnforge.hermes.feature.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import lab.dawnforge.hermes.core.chat.prepareMarkdown
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode

/**
 * The agent's Markdown (GitHub-flavoured, as Hermes asks of the model), with highlighted code.
 *
 * A single line break in prose starts a new line, as Hermes Desktop shows it (its `remarkSoftBreaks`),
 * where plain Markdown would join the lines. A settled reply is first made ready for this renderer
 * ([prepareMarkdown]: HTML, entities, text in columns), and tables wrap their cells ([ChatTable]).
 *
 * A reply being written uses the renderer's streaming state, which re-parses only the unfinished
 * tail as text is appended. Hermes sometimes rewrites a reply instead of extending it (the stored
 * copy of a segment replaces the streamed one); the state then starts over.
 */
@Composable
internal fun ChatMarkdown(text: String, streaming: Boolean, modifier: Modifier = Modifier) {
    val components = markdownComponents(
        // The header holds the language and a copy button. Plain text (`text`) isn't coloured as code.
        codeFence = {
            MarkdownCodeFence(it.content, it.node, it.typography.code) { code, language, style ->
                if (language?.lowercase() in PLAIN_LANGUAGES) {
                    PlainCode(code, language, style)
                } else {
                    MarkdownHighlightedCode(code, language, style, showHeader = true)
                }
            }
        },
        codeBlock = { MarkdownHighlightedCodeBlock(it.content, it.node, it.typography.code, showHeader = true) },
        checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
        table = { ChatTable(it.content, it.node, it.typography.table) },
    )
    val annotator = remember {
        markdownAnnotator(config = markdownAnnotatorConfig(eolAsNewLine = true)) { content, child ->
            annotateChat(content, child)
        }
    }
    // The renderer's default headings are display-sized; a chat reply wants them nearer its body text.
    val type = MaterialTheme.typography
    val typography = markdownTypography(
        h1 = type.headlineSmall,
        h2 = type.titleLarge,
        h3 = type.titleMedium,
        h4 = type.titleSmall,
        h5 = type.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        h6 = type.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    if (!streaming) {
        // Keeps the previous rendering while a changed text is parsed, so a settled reply never flashes.
        val shown = remember(text) { prepareMarkdown(text) }
        val state = rememberMarkdownState(shown, retainState = true)
        Markdown(
            state,
            typography = typography,
            annotator = annotator,
            components = components,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    var generation by remember { mutableIntStateOf(0) }
    key(generation) {
        val state = rememberStreamingMarkdownState()
        val latest by rememberUpdatedState(text)
        LaunchedEffect(state) {
            var shown = ""
            snapshotFlow { latest }.collect { current ->
                if (current.startsWith(shown)) {
                    if (current.length > shown.length) state.append(current.substring(shown.length))
                    shown = current
                } else {
                    // Rewritten, not extended: a new state for the new text.
                    generation++
                }
            }
        }
        Markdown(
            streamingMarkdownState = state,
            typography = typography,
            annotator = annotator,
            components = components,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

/**
 * Where this chat reads Markdown differently from the renderer: a tag inside inline code is code
 * (the renderer drops it, so `List<String>` showed as "List"), and the indent of a line after a line
 * break is not shown, as Hermes Desktop trims it.
 */
internal fun AnnotatedString.Builder.annotateChat(content: String, child: ASTNode): Boolean {
    val parent = child.parent ?: return false
    return when {
        child.type == MarkdownTokenTypes.HTML_TAG && parent.type == MarkdownElementTypes.CODE_SPAN -> {
            append(child.getTextInNode(content))
            true
        }

        child.type == MarkdownTokenTypes.WHITE_SPACE && parent.type != MarkdownElementTypes.CODE_SPAN ->
            parent.children.getOrNull(parent.children.indexOf(child) - 1)?.type == MarkdownTokenTypes.EOL

        else -> false
    }
}

/** A code block of plain text, with the same frame and header as highlighted code but no colours. */
@Composable
private fun PlainCode(code: String, language: String?, style: TextStyle) {
    MarkdownCodeBackground(
        color = LocalMarkdownColors.current.codeBackground,
        shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        showHeader = true,
        language = language,
        code = code,
    ) {
        Text(
            text = code,
            style = style,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(LocalMarkdownPadding.current.codeBlock),
        )
    }
}

private val PLAIN_LANGUAGES = setOf("text", "txt", "plain", "plaintext")
