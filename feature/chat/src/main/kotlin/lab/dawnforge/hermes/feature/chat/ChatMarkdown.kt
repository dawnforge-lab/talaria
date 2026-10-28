package lab.dawnforge.hermes.feature.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.rememberStreamingMarkdownState

/**
 * The agent's Markdown (GitHub-flavoured, as Hermes asks of the model), with highlighted code.
 *
 * A reply being written uses the renderer's streaming state, which re-parses only the unfinished
 * tail as text is appended. Hermes sometimes rewrites a reply instead of extending it (the stored
 * copy of a segment replaces the streamed one); the state then starts over.
 */
@Composable
internal fun ChatMarkdown(text: String, streaming: Boolean, modifier: Modifier = Modifier) {
    val components = markdownComponents(
        codeFence = { MarkdownHighlightedCodeFence(it.content, it.node, it.typography.code, showHeader = true) },
        // The header holds the language and a copy button.
        codeBlock = { MarkdownHighlightedCodeBlock(it.content, it.node, it.typography.code, showHeader = true) },
        checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
    )
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
        val state = rememberMarkdownState(text, retainState = true)
        Markdown(state, typography = typography, components = components, modifier = modifier.fillMaxWidth())
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
            components = components,
            modifier = modifier.fillMaxWidth(),
        )
    }
}
