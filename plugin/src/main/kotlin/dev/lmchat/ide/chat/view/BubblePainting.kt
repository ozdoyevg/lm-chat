package dev.lmchat.ide.chat.view

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import javax.swing.text.AbstractDocument
import javax.swing.text.Element
import javax.swing.text.ParagraphView
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledEditorKit
import javax.swing.text.ViewFactory
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Rectangle
import javax.swing.Icon
import javax.swing.text.AttributeSet

internal object BubbleMetrics {
    const val PADDING = 12            // text padding inside a bubble
    const val EDGE = 4                // space between a bubble and the window edge
    const val CODE_INSET = 10         // distance from the bubble edge to the code block fill
    const val REQUEST_MIN_GAP = 40    // minimum free space left of a request bubble
    const val WIDTH_SLACK = 6         // extra width so a request does not wrap by a pixel
}

/** Which part of a rounded bubble a paragraph paints. */
internal object BubbleSlice {
    const val SINGLE = 0
    const val TOP = 1
    const val MIDDLE = 2
    const val BOTTOM = 3
}

/** Paragraph attribute keys. Swing only paints backgrounds behind characters, so paragraphs paint themselves. */
internal object BubbleKeys {
    val BACKGROUND = Any()
    val SLICE = Any()
    val MARGIN_LEFT = Any()
    val MARGIN_RIGHT = Any()
    val INNER_FILL = Any()
    val INNER_FILL_INSET = Any()
    val COPY_BUTTON = Any()      // marks a code header paragraph that paints a copy button
}

internal const val COPIED_TEXT = "Copied"

/** The copy button of a code block header. The paragraph paints it, so it is not part of the text. */
internal class CopyButton(
    val source: CharSequence,
    private val icon: Icon,
    private val copiedFont: Font,
    private val copiedColor: Color
) {
    var copied = false

    fun size(component: Component): Dimension =
        if (copied) {
            val metrics = component.getFontMetrics(copiedFont)
            Dimension(metrics.stringWidth(COPIED_TEXT), metrics.height)
        } else {
            Dimension(icon.iconWidth, icon.iconHeight)
        }

    fun paint(component: Component, g: Graphics2D, x: Int, y: Int) {
        if (copied) {
            g.font = copiedFont
            g.color = copiedColor
            g.drawString(COPIED_TEXT, x, y + g.fontMetrics.ascent)
        } else {
            icon.paintIcon(component, g, x, y)
        }
    }
}

internal object CopyButtonGeometry {
    private const val RIGHT_GAP = 6

    /** Right-aligned inside the code block fill, vertically centered on the header line. */
    fun bounds(
        paragraphX: Int, paragraphWidth: Int, marginRight: Int,
        lineY: Int, lineHeight: Int, size: Dimension
    ): Rectangle {
        val right = paragraphX + paragraphWidth - marginRight - BubbleMetrics.CODE_INSET - RIGHT_GAP
        return Rectangle(right - size.width, lineY + (lineHeight - size.height) / 2, size.width, size.height)
    }
}

internal fun paragraphStyle(
    background: Color?, slice: Int, marginLeft: Int, marginRight: Int, paddingLeft: Int, paddingRight: Int,
    spaceAbove: Float = 0f, spaceBelow: Float = 0f, innerFill: Color? = null, innerFillInset: Int = 0,
    alignment: Int = StyleConstants.ALIGN_LEFT
): SimpleAttributeSet {
    val style = SimpleAttributeSet()
    StyleConstants.setLeftIndent(style, (marginLeft + paddingLeft).toFloat())
    StyleConstants.setRightIndent(style, (marginRight + paddingRight).toFloat())
    StyleConstants.setSpaceAbove(style, spaceAbove)
    StyleConstants.setSpaceBelow(style, spaceBelow)
    StyleConstants.setLineSpacing(style, 0.12f)
    StyleConstants.setAlignment(style, alignment)
    if (background != null) {
        style.addAttribute(BubbleKeys.BACKGROUND, background)
        style.addAttribute(BubbleKeys.SLICE, slice)
        style.addAttribute(BubbleKeys.MARGIN_LEFT, marginLeft)
        style.addAttribute(BubbleKeys.MARGIN_RIGHT, marginRight)
        if (innerFill != null) {
            style.addAttribute(BubbleKeys.INNER_FILL, innerFill)
            style.addAttribute(BubbleKeys.INNER_FILL_INSET, innerFillInset)
        }
    }
    return style
}

/** A paragraph that paints its slice of a rounded bubble (and optionally an inner code fill) behind its text. */
/** A paragraph that paints its slice of a rounded bubble, an optional inner code fill and an optional copy button. */
private class BubbleParagraphView(element: Element) : ParagraphView(element) {
    override fun paint(g: Graphics, allocation: Shape) {
        val attributes = element.attributes
        val bounds = allocation.bounds
        val marginLeft = attributes.getAttribute(BubbleKeys.MARGIN_LEFT) as? Int ?: 0
        val marginRight = attributes.getAttribute(BubbleKeys.MARGIN_RIGHT) as? Int ?: 0

        val background = attributes.getAttribute(BubbleKeys.BACKGROUND) as? Color
        if (background != null) paintBubble(g, bounds, attributes, background, marginLeft, marginRight)

        super.paint(g, allocation)

        val button = attributes.getAttribute(BubbleKeys.COPY_BUTTON) as? CopyButton
        val component = container
        if (button != null && component != null) {
            val area = CopyButtonGeometry.bounds(
                bounds.x, bounds.width, marginRight, bounds.y, bounds.height, button.size(component)
            )
            val g2 = g.create() as Graphics2D
            try {
                UIUtil.applyRenderingHints(g2)
                button.paint(component, g2, area.x, area.y)
            } finally {
                g2.dispose()
            }
        }
    }

    private fun paintBubble(
        g: Graphics, bounds: Rectangle, attributes: AttributeSet, background: Color,
        marginLeft: Int, marginRight: Int
    ) {
        val x = bounds.x + marginLeft
        val width = bounds.width - marginLeft - marginRight
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.clipRect(bounds.x, bounds.y, bounds.width, bounds.height)
            val arc = JBUI.scale(12)
            g2.color = background
            when (attributes.getAttribute(BubbleKeys.SLICE) as? Int ?: BubbleSlice.SINGLE) {
                BubbleSlice.TOP -> g2.fillRoundRect(x, bounds.y, width, bounds.height + arc, arc, arc)
                BubbleSlice.MIDDLE -> g2.fillRect(x, bounds.y, width, bounds.height)
                BubbleSlice.BOTTOM -> g2.fillRoundRect(x, bounds.y - arc, width, bounds.height + arc, arc, arc)
                else -> g2.fillRoundRect(x, bounds.y, width, bounds.height, arc, arc)
            }
            val innerFill = attributes.getAttribute(BubbleKeys.INNER_FILL) as? Color
            if (innerFill != null) {
                val inset = attributes.getAttribute(BubbleKeys.INNER_FILL_INSET) as? Int ?: 0
                g2.color = innerFill
                g2.fillRect(x + inset, bounds.y, width - 2 * inset, bounds.height)
            }
        } finally {
            g2.dispose()
        }
    }
}

internal class BubbleEditorKit : StyledEditorKit() {
    override fun getViewFactory(): ViewFactory {
        val standard = super.getViewFactory()
        return ViewFactory { element ->
            if (element.name == AbstractDocument.ParagraphElementName) BubbleParagraphView(element) else standard.create(
                element
            )
        }
    }
}