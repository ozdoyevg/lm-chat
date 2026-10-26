package dev.lmchat.ide.chat.view

import com.google.gson.JsonParser
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.lmchat.core.llm.stringOrNull
import dev.lmchat.core.markdown.CodeHighlighter
import dev.lmchat.core.markdown.FileLanguages
import dev.lmchat.core.markdown.InlineStyle
import dev.lmchat.core.markdown.MarkdownListener
import dev.lmchat.core.markdown.MarkdownStreamParser
import dev.lmchat.core.markdown.TokenKind
import dev.lmchat.ide.theme.ChatTheme
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.AttributeSet
import javax.swing.text.DefaultCaret
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * The transcript: request bubbles, streaming answer bubbles, tool cards with foldable code and inline approval.
 * All methods must be called on the UI thread.
 */
class ChatView private constructor(private val pane: JTextPane) : JBScrollPane(pane) {
    constructor() : this(EmptyHintTextPane())

    private class ClickableRange(val start: Marker, val end: Marker, val onClick: () -> Unit)

    private class FoldableCode(
        val lines: List<String>, val language: String,
        val headerStyle: SimpleAttributeSet, val bodyStyle: SimpleAttributeSet
    ) {
        lateinit var header: ClickableRange
        lateinit var bodyStart: Marker
        var bodyLength = 0
        var expanded = true
    }

    private class RequestBubble(val start: Marker, val end: Marker, val textWidth: Int)
    private class ThinkingLine(val start: Marker, val length: Int)

    private var styles = ChatStyles()
    private val transcript = Transcript(pane, styles)
    private val markdown = MarkdownStreamParser(MarkdownRenderer())
    private val timestampFormat = DateTimeFormatter.ofPattern("d MMM HH:mm")

    // open answer bubble / tool card
    private var assistantBubbleOpen = false
    private var assistantBubbleStart: Marker? = null
    private var bubbleHasContent = false
    private var toolCardOpen = false
    private var thinkingAnnounced = false
    private var thinkingLine: ThinkingLine? = null

    // code block being streamed
    private var codeHighlighter = CodeHighlighter("")
    private var codeBlockSource: StringBuilder? = null

    // clickable things, foldable code, request bubbles
    private val clickables = mutableListOf<ClickableRange>()
    private val foldables = mutableListOf<FoldableCode>()
    private val requestBubbles = mutableListOf<RequestBubble>()
    private var pendingDecision: ((Boolean) -> Unit)? = null
    private var lastWidth = -1

    init {
        pane.editorKit = BubbleEditorKit()
        pane.isEditable = false
        pane.background = ChatTheme.color("chatBg")
        pane.border = JBUI.Borders.empty(6, 2)
        (pane.caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
        border = JBUI.Borders.empty()

        // repaint so the "chat is empty" hint appears and disappears immediately
        transcript.doc.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                if (e.document.length == e.length) pane.repaint()
            }

            override fun removeUpdate(e: DocumentEvent) {
                if (e.document.length == 0) pane.repaint()
            }

            override fun changedUpdate(e: DocumentEvent) {}
        })
        pane.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val copyButton = copyButtonAt(e)
                if (copyButton != null) copyToClipboard(copyButton) else clickableAt(e)?.onClick?.invoke()
            }
        })
        pane.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val overButton = copyButtonAt(e) != null || clickableAt(e) != null
                pane.cursor = Cursor.getPredefinedCursor(if (overButton) Cursor.HAND_CURSOR else Cursor.TEXT_CURSOR)
            }
        })
        pane.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (pane.width != lastWidth) {
                    lastWidth = pane.width; relayoutRequestBubbles()
                }
            }
        })
    }

    /** Re-reads colors and fonts from the settings. Existing messages keep their old look. */
    fun applyTheme() {
        styles = ChatStyles()
        transcript.styles = styles
        pane.background = ChatTheme.color("chatBg")
        pane.repaint()
    }

    // ---- public API ----

    fun userMessage(text: String) {
        closeOpenBlocks()
        transcript.addSeparator()
        val body = text.trimEnd()
        val timestamp = LocalDateTime.now().format(timestampFormat)
        val textWidth = measureRequestWidth(body, timestamp)
        val bubble = BubbleStyles(styles.requestBackground, requestMarginLeft(textWidth), BubbleMetrics.EDGE)

        val start = transcript.mark()
        transcript.addPadding(bubble.top)
        transcript.write(body + "\n", styles.requestText, bubble.middle)
        transcript.write(timestamp + "\n", styles.timestampText, bubble.timestamp)
        transcript.addPadding(bubble.bottom)
        requestBubbles.add(RequestBubble(start, transcript.mark(), textWidth))
        scrollToBottom()
    }

    /** Starts a new answer. The bubble itself is created when the first text arrives. */
    fun beginAssistant() {
        closeOpenBlocks()
        thinkingAnnounced = false
    }

    fun assistantText(chunk: String) {
        val follow = isScrolledToBottom()
        hideThinking()
        openAssistantBubble()
        markdown.feed(chunk)
        if (follow) scrollToBottom()
    }

    fun thinking() {
        if (thinkingAnnounced) return
        thinkingAnnounced = true
        openAssistantBubble()
        markdown.endLine()
        val start = transcript.mark()
        transcript.write("thinking…\n", styles.mutedText, styles.assistant.middle)
        thinkingLine = ThinkingLine(start, transcript.end() - start.offset)
        transcript.resetPlaceholder(styles.assistant.middle)
        scrollToBottom()
    }

    fun toolCall(name: String, argumentsJson: String) {
        closeOpenBlocks()
        transcript.addSeparator()
        transcript.openBlock(styles.tool.top, styles.tool.middle, styles.tool.bottom)
        toolCardOpen = true

        val arguments = try {
            JsonParser.parseString(argumentsJson).asJsonObject
        } catch (e: Exception) {
            null
        }
        val inlineArguments = StringBuilder()
        val codeArguments = ArrayList<Pair<String, String>>()
        arguments?.entrySet()?.forEach { (key, value) ->
            val text = if (value.isJsonPrimitive) value.asString else value.toString()
            if (key in CODE_ARGUMENTS || text.contains('\n') || text.length > 120) codeArguments.add(key to text)
            else inlineArguments.append("  ").append(key).append('=').append(text)
        }
        if (arguments == null && argumentsJson.isNotBlank()) inlineArguments.append("  ")
            .append(argumentsJson.take(200))

        transcript.write("⚙ ", styles.toolIcon, styles.tool.middle)
        transcript.write(name, styles.toolName, styles.tool.middle)
        if (inlineArguments.isNotEmpty()) transcript.write(
            inlineArguments.toString(),
            styles.toolArguments,
            styles.tool.middle
        )
        transcript.write("\n", styles.toolArguments, styles.tool.middle)
        transcript.resetPlaceholder(styles.tool.middle)

        val language = FileLanguages.fromPath(arguments?.stringOrNull("path"))
        for ((label, text) in codeArguments) {
            val background = when (label) {
                "old_text" -> styles.removedBackground
                "new_text" -> styles.addedBackground
                else -> styles.codeBackground
            }
            addFoldableCode(label, text, language, background)
        }
        scrollToBottom()
    }

    fun toolResult(summary: String, failed: Boolean) {
        transcript.write(
            "↳ $summary\n",
            if (failed) styles.toolResultError else styles.toolResultOk,
            styles.tool.middle
        )
        transcript.resetPlaceholder(styles.tool.middle)
        closeToolCard()
        thinkingAnnounced = false
        scrollToBottom()
    }

    fun info(text: String) = standaloneLine { transcript.write("$text\n", styles.mutedText, styles.plain) }
    fun error(text: String) = standaloneLine { transcript.write("$text\n", styles.errorText, styles.plain) }

    fun endAssistant() {
        closeOpenBlocks()
    }

    fun clear() {
        markdown.reset()
        transcript.clear()
        assistantBubbleOpen = false; assistantBubbleStart = null; bubbleHasContent = false
        toolCardOpen = false; thinkingLine = null; thinkingAnnounced = false
        codeBlockSource = null
        clickables.clear(); foldables.clear(); requestBubbles.clear(); pendingDecision = null
    }

    /** Shows Allow / Deny for a pending file change. [onDecision] is called once. */
    /** Shows Allow / Deny for a pending file change. [onDecision] is called once. */
    fun requestApproval(prompt: String, onDecision: (Boolean) -> Unit) {
        val middle = styles.tool.middle
        val lineStart = transcript.mark()
        transcript.write("$prompt?  ", styles.approvalPrompt, middle)
        val allowStart = transcript.mark()
        transcript.write("  Allow  ", styles.allowButton, middle)
        val allowEnd = transcript.mark()
        transcript.write("  ", styles.approvalPrompt, middle)
        val denyStart = transcript.mark()
        transcript.write("  Deny  ", styles.denyButton, middle)
        val denyEnd = transcript.mark()
        transcript.write("\n", styles.approvalPrompt, middle)
        transcript.resetPlaceholder(middle)

        var decided = false
        lateinit var allowRange: ClickableRange
        lateinit var denyRange: ClickableRange
        fun decide(allowed: Boolean) {
            if (decided) return
            decided = true
            pendingDecision = null
            clickables.remove(allowRange)
            clickables.remove(denyRange)
            val oldLength = denyEnd.offset - lineStart.offset
            val text = if (allowed) "✓ Allowed: $prompt" else "✗ Denied: $prompt"
            transcript.replace(lineStart.offset, oldLength, text, styles.decisionText, middle)
            listOf(lineStart, allowStart, allowEnd, denyStart, denyEnd).forEach { transcript.unmark(it) }
            onDecision(allowed)
        }
        allowRange = ClickableRange(allowStart, allowEnd) { decide(true) }
        denyRange = ClickableRange(denyStart, denyEnd) { decide(false) }
        clickables.add(allowRange)
        clickables.add(denyRange)
        pendingDecision = { allowed -> decide(allowed) }
        scrollToBottom()
    }

    /** Denies a change that is still waiting for a click (Stop pressed or window closed). */
    fun denyPending() {
        pendingDecision?.invoke(false)
    }

    /** Fills the view with sample content to check the styles without a server. Dev only. */
    fun demo() {
        clear()
        userMessage("Refactor this function to use a match expression.")
        beginAssistant()
        thinking()
        assistantText(
            "Sure. Here is the **refactored** version using `match`:\n\n" +
                    "```php\n<?php\nfunction label(int \$code): string {\n    // map codes to labels\n" +
                    "    return match (\$code) {\n        200 => 'OK',\n        404 => \"Not found\",\n" +
                    "        default => 'Unknown',\n    };\n}\n```\n\nNotes:\n- `match` is strict\n- no fallthrough\n\n\n"
        )
        toolCall("read_file", """{"path":"src/Status.php"}""")
        toolResult("1240 chars returned", false)
        toolCall(
            "replace_in_file",
            """{"path":"src/Status.php","old_text":"return 'OK';","new_text":"return 'Fine';\nreturn 'Fine';"}"""
        )
        requestApproval("Edit src/Status.php") { }
        endAssistant()
        userMessage("ok")
        info("Stopped.")
        error("OpenRouter: HTTP 429: rate limit exceeded")
    }

    // ---- answer bubble and tool card lifecycle ----

    private fun openAssistantBubble() {
        if (assistantBubbleOpen) return
        assistantBubbleStart = transcript.mark()
        transcript.addSeparator()
        transcript.openBlock(styles.assistant.top, styles.assistant.middle, styles.assistant.bottom)
        assistantBubbleOpen = true
        bubbleHasContent = false
    }

    private fun closeAssistantBubble() {
        assistantBubbleOpen = false
        val start = assistantBubbleStart
        assistantBubbleStart = null
        if (start == null) return
        if (bubbleHasContent) transcript.closeBlock(styles.assistant.bottom) else transcript.discardBlock(start.offset)
        transcript.unmark(start)
    }

    private fun closeToolCard() {
        if (!toolCardOpen) return
        toolCardOpen = false
        transcript.closeBlock(styles.tool.bottom)
    }

    /** Finishes streaming text and closes any open bubble or card. A bubble without content is deleted. */
    private fun closeOpenBlocks() {
        hideThinking()
        markdown.finish()
        if (assistantBubbleOpen) closeAssistantBubble()
        if (toolCardOpen) closeToolCard()
    }

    private fun standaloneLine(write: () -> Unit) {
        closeOpenBlocks()
        transcript.addSeparator()
        write()
        scrollToBottom()
    }

    private fun hideThinking() {
        val line = thinkingLine ?: return
        thinkingLine = null
        transcript.remove(line.start.offset, line.length)
        transcript.unmark(line.start)
    }

    private fun currentMiddleStyle(): AttributeSet = if (toolCardOpen) styles.tool.middle else styles.assistant.middle

    // ---- markdown rendering ----

    private inner class MarkdownRenderer : MarkdownListener {
        override fun onText(text: String, style: InlineStyle) {
            bubbleHasContent = true
            val characters = when (style) {
                InlineStyle.PLAIN -> styles.text
                InlineStyle.BOLD -> styles.boldText
                InlineStyle.CODE -> styles.inlineCode
            }
            transcript.write(text, characters, styles.assistant.middle)
        }

        override fun onLineEnd() {
            transcript.write("\n", styles.text, styles.assistant.middle)
        }

        override fun onSpacing() {
            transcript.write("\n", styles.spacerBreak, styles.assistant.middle)
        }

        override fun onCodeBlockStart(language: String) {
            bubbleHasContent = true
            codeHighlighter = CodeHighlighter(language)
            val source = StringBuilder()
            codeBlockSource = source

            val headerStart = transcript.end()
            transcript.write(language.ifEmpty { "code" } + "\n", styles.codeLabel, styles.codeHeader)
            val copyButton = SimpleAttributeSet().apply {
                addAttribute(
                    BubbleKeys.COPY_BUTTON,
                    CopyButton(source, styles.copyIcon, styles.copiedFont, styles.copiedColor)
                )
            }
            transcript.doc.setParagraphAttributes(headerStart, 1, copyButton, false)
            transcript.resetPlaceholder(currentMiddleStyle())
        }

        override fun onCodeLine(line: String) {
            codeBlockSource?.append(line)?.append('\n')
            for ((text, kind) in codeHighlighter.highlightLine(line.replace("\t", "    "))) {
                transcript.write(text, styles.syntax.getValue(kind), styles.codeBlock)
            }
            transcript.write("\n", styles.syntax.getValue(TokenKind.PLAIN), styles.codeBlock)
            transcript.resetPlaceholder(currentMiddleStyle())
        }

        override fun onCodeBlockEnd() {
            transcript.addPadding(styles.codeBlock)
            codeBlockSource = null
        }
    }

    private fun copyToClipboard(button: CopyButton) {
        CopyPasteManager.getInstance().setContents(StringSelection(button.source.toString()))
        button.copied = true
        pane.repaint()
        val restore = Timer(1500) { button.copied = false; pane.repaint() }
        restore.isRepeats = false
        restore.start()
    }

    /** The copy button under the mouse, found from the geometry it is painted with. */
    private fun copyButtonAt(event: MouseEvent): CopyButton? {
        val offset = pane.viewToModel2D(event.point)
        val paragraph = transcript.doc.getParagraphElement(offset)
        val attributes = paragraph.attributes
        val button = attributes.getAttribute(BubbleKeys.COPY_BUTTON) as? CopyButton ?: return null
        val line = try {
            pane.modelToView2D(paragraph.startOffset)
        } catch (e: Exception) {
            null
        } ?: return null
        val insets = pane.insets
        val marginRight = attributes.getAttribute(BubbleKeys.MARGIN_RIGHT) as? Int ?: 0
        val area = CopyButtonGeometry.bounds(
            insets.left, pane.width - insets.left - insets.right, marginRight,
            line.y.toInt(), line.height.toInt(), button.size(pane)
        )
        area.grow(4, 4)
        return if (area.contains(event.point)) button else null
    }

    // ---- foldable code in tool cards ----

    private fun addFoldableCode(label: String, text: String, language: String, background: Color) {
        val lines = text.replace("\r", "").replace("\t", "    ").split("\n").let {
            if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it
        }
        val fold = FoldableCode(lines, language, styles.tool.codeHeader(background), styles.tool.codeBody(background))
        val lineCount = "${lines.size} line${if (lines.size == 1) "" else "s"}"
        val title = " $label · $lineCount" + if (language.isNotEmpty()) " · $language" else ""

        val headerStart = transcript.mark()
        transcript.write("▾", styles.foldGlyph, fold.headerStyle)
        transcript.write(title, styles.codeLabel, fold.headerStyle)
        fold.header = ClickableRange(headerStart, transcript.mark()) { toggle(fold) }
        transcript.write("\n", styles.codeLabel, fold.headerStyle)

        fold.bodyStart = transcript.mark()
        fold.bodyLength = writeFoldBody(fold, fold.bodyStart.offset) - fold.bodyStart.offset
        clickables.add(fold.header)
        foldables.add(fold)
        transcript.resetPlaceholder(styles.tool.middle)
    }

    private fun writeFoldBody(fold: FoldableCode, at: Int): Int {
        var position = at
        val highlighter = CodeHighlighter(fold.language)
        for (line in fold.lines) {
            for ((text, kind) in highlighter.highlightLine(line)) {
                position = transcript.write(text, styles.syntax.getValue(kind), fold.bodyStyle, position)
            }
            position = transcript.write("\n", styles.syntax.getValue(TokenKind.PLAIN), fold.bodyStyle, position)
        }
        return position
    }

    private fun toggle(fold: FoldableCode) {
        if (fold.expanded) {
            transcript.remove(fold.bodyStart.offset, fold.bodyLength)
            fold.bodyLength = 0
        } else {
            val bodyStart = fold.bodyStart.offset
            val length = writeFoldBody(fold, bodyStart) - bodyStart
            transcript.shiftMarkers(from = bodyStart, delta = length, except = fold.bodyStart)
            fold.bodyLength = length
        }
        fold.expanded = !fold.expanded
        transcript.replace(
            fold.header.start.offset,
            1,
            if (fold.expanded) "▾" else "▸",
            styles.foldGlyph,
            fold.headerStyle
        )
    }

    // ---- request bubble width ----

    private fun measureRequestWidth(body: String, timestamp: String): Int {
        val textMetrics = pane.getFontMetrics(Font(styles.fonts.chatFamily, Font.PLAIN, styles.fonts.chatSize))
        val smallMetrics = pane.getFontMetrics(Font(styles.fonts.chatFamily, Font.PLAIN, styles.fonts.smallSize))
        val widestLine = body.split("\n").maxOf { textMetrics.stringWidth(it.replace("\t", "    ")) }
        return maxOf(widestLine, smallMetrics.stringWidth(timestamp))
    }

    private fun availableWidth(): Int {
        val width = if (pane.width > 0) pane.width else viewport.width
        return (if (width > 0) width else 420) - 4
    }

    /** Left margin that makes the bubble as narrow as its text, but never wider than the window minus a gap. */
    private fun requestMarginLeft(textWidth: Int): Int {
        val available = availableWidth()
        val bubbleWidth = minOf(
            textWidth + 2 * BubbleMetrics.PADDING + BubbleMetrics.WIDTH_SLACK,
            available - BubbleMetrics.REQUEST_MIN_GAP - BubbleMetrics.EDGE
        )
        return (available - BubbleMetrics.EDGE - bubbleWidth).coerceAtLeast(BubbleMetrics.REQUEST_MIN_GAP)
    }

    private fun relayoutRequestBubbles() {
        for (bubble in requestBubbles) {
            val marginLeft = requestMarginLeft(bubble.textWidth)
            val update = SimpleAttributeSet()
            StyleConstants.setLeftIndent(update, (marginLeft + BubbleMetrics.PADDING).toFloat())
            StyleConstants.setRightIndent(update, (BubbleMetrics.EDGE + BubbleMetrics.PADDING).toFloat())
            update.addAttribute(BubbleKeys.MARGIN_LEFT, marginLeft)
            transcript.doc.setParagraphAttributes(
                bubble.start.offset,
                bubble.end.offset - bubble.start.offset,
                update,
                false
            )
        }
    }

    // ---- hit testing and scrolling ----

    private fun clickableAt(event: MouseEvent): ClickableRange? {
        val offset = pane.viewToModel2D(event.point)
        val range = clickables.firstOrNull { offset >= it.start.offset && offset <= it.end.offset } ?: return null
        val rect = try {
            pane.modelToView2D(offset)
        } catch (e: Exception) {
            null
        } ?: return null
        return if (event.y >= rect.y && event.y <= rect.y + rect.height) range else null
    }

    private fun isScrolledToBottom(): Boolean {
        val bar = verticalScrollBar
        return bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(24)
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater { verticalScrollBar.value = verticalScrollBar.maximum }
    }
    
    private companion object {
        val CODE_ARGUMENTS = setOf("content", "old_text", "new_text")
    }
}