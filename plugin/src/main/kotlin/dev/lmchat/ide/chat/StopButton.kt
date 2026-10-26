package dev.lmchat.ide.chat

import com.intellij.openapi.util.IconLoader
import com.intellij.util.ui.JBUI
import dev.lmchat.ide.theme.ChatTheme
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.Timer

/** Stop icon with a spinning ring around it. The animation only runs while the button is visible. */
class StopButton(private val onStop: () -> Unit) : JComponent() {
    private val icon = IconLoader.getIcon("/icons/stop.svg", StopButton::class.java)
    private var ringAngle = 0
    private val animation = Timer(30) { ringAngle = (ringAngle + 12) % 360; repaint() }

    init {
        val size = Dimension(JBUI.scale(BUTTON_SIZE), JBUI.scale(BUTTON_SIZE))
        preferredSize = size
        minimumSize = size
        toolTipText = "Stop"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onStop()
        })
    }

    override fun setVisible(visible: Boolean) {
        super.setVisible(visible)
        if (visible) animation.start() else animation.stop()
    }

    override fun removeNotify() {
        animation.stop(); super.removeNotify()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val centerX = width / 2
            val centerY = height / 2
            icon.paintIcon(this, g2, centerX - icon.iconWidth / 2, centerY - icon.iconHeight / 2)
            val radius = minOf(width, height) / 2 - JBUI.scale(2)
            g2.color = ChatTheme.color("accent")
            g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.drawArc(centerX - radius, centerY - radius, 2 * radius, 2 * radius, ringAngle, 100)
        } finally {
            g2.dispose()
        }
    }

    companion object {
        const val BUTTON_SIZE = 32
    }
}