package dev.lmchat.ide.chat.view

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.lmchat.ide.theme.ChatTheme
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JTextPane

/** Text pane that shows a hint while the chat is empty. */
internal class EmptyHintTextPane : JTextPane() {
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (document.length != 0) return
        val g2 = g.create() as Graphics2D
        try {
            UIUtil.applyRenderingHints(g2)
            val bodyFont = UIUtil.getLabelFont()
            val titleFont = bodyFont.deriveFont(Font.BOLD, bodyFont.size2D + 2f)
            val titleMetrics = g2.getFontMetrics(titleFont)
            val bodyMetrics = g2.getFontMetrics(bodyFont)
            val title = "Chat is empty"
            val lines = listOf("Pick a model below and write a message.", "Enter to send · Shift+Enter for a new line")
            val gap = JBUI.scale(6)
            val totalHeight = titleMetrics.height + gap + bodyMetrics.height * lines.size
            var y = (height - totalHeight) / 2 + titleMetrics.ascent

            g2.font = titleFont
            g2.color = ChatTheme.color("muted")
            g2.drawString(title, (width - titleMetrics.stringWidth(title)) / 2, y)

            y += titleMetrics.descent + gap + bodyMetrics.ascent
            g2.font = bodyFont
            for (line in lines) {
                g2.drawString(line, (width - bodyMetrics.stringWidth(line)) / 2, y)
                y += bodyMetrics.height
            }
        } finally {
            g2.dispose()
        }
    }
}