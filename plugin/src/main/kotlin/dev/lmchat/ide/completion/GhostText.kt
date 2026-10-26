package dev.lmchat.ide.completion

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle

/** The gray suggestion shown at the cursor: the first line inline, further lines as a block below. */
object InlineGhost {
    private class GhostState(val offset: Int, val text: String, val inlays: List<Inlay<*>>)

    private val STATE = Key.create<GhostState>("lmchat.ghost")

    /** True while the plugin itself inserts an accepted suggestion, so that insertion does not trigger a new request. */
    @Volatile
    var accepting = false

    fun isShowing(editor: Editor) = editor.getUserData(STATE) != null

    fun show(editor: Editor, offset: Int, text: String) {
        clear(editor)
        val lines = text.split("\n")
        val inlays = mutableListOf<Inlay<*>>()
        if (lines.first().isNotEmpty()) {
            editor.inlayModel.addInlineElement(offset, true, GhostRenderer(listOf(lines.first())))
                ?.let { inlays.add(it) }
        }
        if (lines.size > 1) {
            editor.inlayModel.addBlockElement(offset, true, false, 0, GhostRenderer(lines.drop(1)))
                ?.let { inlays.add(it) }
        }
        if (inlays.isNotEmpty()) editor.putUserData(STATE, GhostState(offset, text, inlays))
    }

    fun clear(editor: Editor) {
        val state = editor.getUserData(STATE) ?: return
        editor.putUserData(STATE, null)
        state.inlays.forEach { Disposer.dispose(it) }
    }

    fun clearIfCaretMoved(editor: Editor) {
        val state = editor.getUserData(STATE) ?: return
        if (editor.caretModel.offset != state.offset) clear(editor)
    }

    /** Inserts the suggestion at the cursor. Returns false when nothing was showing. */
    fun accept(editor: Editor): Boolean {
        val state = editor.getUserData(STATE) ?: return false
        val project = editor.project ?: return false
        clear(editor)
        accepting = true
        try {
            WriteCommandAction.writeCommandAction(project).withName("LM Chat: accept completion").run<Throwable> {
                editor.document.insertString(state.offset, state.text)
                editor.caretModel.moveToOffset(state.offset + state.text.length)
            }
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        } finally {
            accepting = false
        }
        return true
    }
}

private class GhostRenderer(private val lines: List<String>) : EditorCustomElementRenderer {
    private fun font(editor: Editor): Font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
    private fun metrics(editor: Editor) = editor.contentComponent.getFontMetrics(font(editor))
    private fun visible(line: String) = line.replace("\t", "    ")

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val metrics = metrics(inlay.editor)
        return lines.maxOf { metrics.stringWidth(visible(it)) }.coerceAtLeast(1)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = inlay.editor.lineHeight * lines.size

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val metrics = metrics(editor)
        val lineHeight = editor.lineHeight
        g.color = JBColor.GRAY
        g.font = font(editor)
        lines.forEachIndexed { index, line ->
            val baseline = targetRegion.y + index * lineHeight + (lineHeight - metrics.height) / 2 + metrics.ascent
            g.drawString(visible(line), targetRegion.x, baseline)
        }
    }
}