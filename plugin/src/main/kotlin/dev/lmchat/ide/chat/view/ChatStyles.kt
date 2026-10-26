package dev.lmchat.ide.chat.view

import dev.lmchat.core.markdown.TokenKind
import dev.lmchat.ide.theme.ChatTheme
import java.awt.Color
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import com.intellij.icons.AllIcons
import java.awt.Font
import javax.swing.Icon

/** The paragraph styles of one bubble or card. */
internal class BubbleStyles(
    private val background: Color,
    private val marginLeft: Int,
    private val marginRight: Int
) {
    private val padding = BubbleMetrics.PADDING
    private val codePadding = BubbleMetrics.CODE_INSET + 10

    val top = paragraphStyle(background, BubbleSlice.TOP, marginLeft, marginRight, padding, padding, spaceAbove = 4f)
    val middle =
        paragraphStyle(background, BubbleSlice.MIDDLE, marginLeft, marginRight, padding, padding, spaceBelow = 1f)
    val timestamp = paragraphStyle(
        background, BubbleSlice.MIDDLE, marginLeft, marginRight, padding, padding,
        spaceBelow = 1f, alignment = StyleConstants.ALIGN_RIGHT
    )
    val bottom =
        paragraphStyle(background, BubbleSlice.BOTTOM, marginLeft, marginRight, padding, padding, spaceBelow = 4f)

    fun codeHeader(codeBackground: Color, alignment: Int = StyleConstants.ALIGN_LEFT) = paragraphStyle(
        background, BubbleSlice.MIDDLE, marginLeft, marginRight, codePadding, codePadding,
        spaceAbove = 3f, spaceBelow = 1f, innerFill = codeBackground, innerFillInset = BubbleMetrics.CODE_INSET,
        alignment = alignment
    )

    fun codeBody(codeBackground: Color) = paragraphStyle(
        background, BubbleSlice.MIDDLE, marginLeft, marginRight, codePadding, codePadding,
        innerFill = codeBackground, innerFillInset = BubbleMetrics.CODE_INSET
    )
}

/** Every character and paragraph style the chat uses, built from the current settings. */
internal class ChatStyles {
    val fonts = ChatTheme.fonts()
    private fun color(key: String) = ChatTheme.color(key)

    fun characters(
        foreground: Color, bold: Boolean = false, italic: Boolean = false, monospaced: Boolean = false,
        background: Color? = null, size: Int = if (monospaced) fonts.codeSize else fonts.chatSize
    ): SimpleAttributeSet {
        val style = SimpleAttributeSet()
        StyleConstants.setFontFamily(style, if (monospaced) fonts.codeFamily else fonts.chatFamily)
        StyleConstants.setFontSize(style, size)
        StyleConstants.setForeground(style, foreground)
        StyleConstants.setBold(style, bold)
        StyleConstants.setItalic(style, italic)
        if (background != null) StyleConstants.setBackground(style, background)
        return style
    }

    // backgrounds
    val requestBackground = color("userBg")
    val codeBackground = color("codeBg")
    val removedBackground = color("diffOldBg")
    val addedBackground = color("diffNewBg")

    // paragraphs
    val assistant = BubbleStyles(color("assistantBg"), BubbleMetrics.EDGE, BubbleMetrics.EDGE)
    val tool = BubbleStyles(color("toolBg"), BubbleMetrics.EDGE, BubbleMetrics.EDGE)
    val plain = paragraphStyle(null, BubbleSlice.SINGLE, BubbleMetrics.EDGE, BubbleMetrics.EDGE, 4, 4, spaceBelow = 1f)
    val noBackground = paragraphStyle(null, BubbleSlice.SINGLE, 0, 0, 0, 0)
    val codeBlock = assistant.codeBody(codeBackground)
    val codeHeader = assistant.codeHeader(codeBackground)

    // characters
    val text = characters(color("assistantText"))
    val boldText = characters(color("assistantText"), bold = true)
    val inlineCode = characters(color("assistantText"), monospaced = true, background = color("inlineBg"))
    val requestText = characters(color("userText"))
    val timestampText = characters(color("time"), size = fonts.smallSize)
    val mutedText = characters(color("muted"), italic = true)
    val errorText = characters(color("error"))
    val toolIcon = characters(color("toolHead"), bold = true)
    val toolName = characters(color("toolHead"), bold = true, monospaced = true)
    val toolArguments = characters(color("toolMuted"), monospaced = true)
    val toolResultOk = characters(color("muted"), monospaced = true, size = fonts.smallSize)
    val toolResultError = characters(color("error"), monospaced = true, size = fonts.smallSize)
    val codeLabel = characters(color("codeLabel"), monospaced = true, size = fonts.smallSize)
    val foldGlyph = characters(color("toolHead"), bold = true, monospaced = true)
    val tinyBreak = characters(color("assistantText"), size = 4)
    val spacerBreak = characters(color("assistantText"), size = 6)

    // approval: dim button-like backgrounds behind normal text
    val approvalPrompt = characters(color("toolHead"))
    val allowButton = characters(color("assistantText"), background = color("allowBg"))
    val denyButton = characters(color("assistantText"), background = color("denyBg"))
    val decisionText = characters(color("muted"))

    // copy button of code blocks
    val copyIcon: Icon = AllIcons.Actions.Copy
    val copiedFont = Font(fonts.chatFamily, Font.PLAIN, fonts.smallSize)
    val copiedColor: Color = color("muted")

    val syntax: Map<TokenKind, SimpleAttributeSet> = mapOf(
        TokenKind.PLAIN to characters(color("synPlain"), monospaced = true),
        TokenKind.COMMENT to characters(color("synComment"), monospaced = true, italic = true),
        TokenKind.STRING to characters(color("synString"), monospaced = true),
        TokenKind.VARIABLE to characters(color("synVar"), monospaced = true),
        TokenKind.NUMBER to characters(color("synNumber"), monospaced = true),
        TokenKind.KEYWORD to characters(color("synKeyword"), monospaced = true),
        TokenKind.CALL to characters(color("synCall"), monospaced = true)
    )
}