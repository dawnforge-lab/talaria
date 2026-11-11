package lab.dawnforge.hermes.feature.chat

import androidx.compose.ui.text.buildAnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

class ChatMarkdownTest {
    private fun tree(text: CharSequence) = parser.buildMarkdownTreeFromString(text)

    private val parser = MarkdownParser(GFMFlavourDescriptor(), cancellationToken = CancellationToken.NonCancellable)

    private fun ASTNode.all(): Sequence<ASTNode> = sequenceOf(this) + children.asSequence().flatMap { it.all() }

    /** What [annotateChat] does with [node]: whether it took the node, and the text it wrote. */
    private fun annotated(text: String, node: ASTNode): Pair<Boolean, String> {
        var handled = false
        val written = buildAnnotatedString { handled = annotateChat(text, node) }.text
        return handled to written
    }

    @Test
    fun `a tag inside inline code is written as code`() {
        val text = "Use `List<String>` here."
        val tag = tree(text).all().first {
            it.type == MarkdownTokenTypes.HTML_TAG && it.parent?.type == MarkdownElementTypes.CODE_SPAN
        }
        assertEquals(true to "<String>", annotated(text, tag))
    }

    @Test
    fun `the indent after a line break is not shown, other spaces are`() {
        val text = "first line\n   second line"
        val spaces = tree(text).all().filter { it.type == MarkdownTokenTypes.WHITE_SPACE }.toList()
        val afterBreak = spaces.single { space ->
            val siblings = space.parent!!.children
            siblings.getOrNull(siblings.indexOf(space) - 1)?.type == MarkdownTokenTypes.EOL
        }
        assertEquals(true to "", annotated(text, afterBreak))
        (spaces - afterBreak).forEach { assertFalse(annotated(text, it).first) }
    }

    @Test
    fun `everything else is left to the renderer`() {
        val text = "Plain **bold** text"
        tree(text).all().filter { it.parent != null }.forEach { node ->
            assertFalse(annotated(text, node).first, node.type.name)
        }
    }
}
