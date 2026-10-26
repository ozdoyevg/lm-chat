package dev.lmchat.ui

import com.google.gson.JsonParser
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.lmchat.api.str
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
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
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DefaultCaret
import javax.swing.text.Element
import javax.swing.text.ParagraphView
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument
import javax.swing.text.StyledEditorKit
import javax.swing.text.ViewFactory

// ---------------------------------------------------------------------------------------------
// Bubble painting. Every paragraph of a bubble paints its own slice of a rounded rectangle:
//   POS_KEY: 1 = top, 2 = middle, 3 = bottom, 0 = single
//   ML/MR  : outer margins (not painted), INNER_KEY: optional inner fill (code blocks)
// ---------------------------------------------------------------------------------------------

private val BG_KEY = Any()
private val POS_KEY = Any()
private val ML_KEY = Any()
private val MR_KEY = Any()
private val INNER_KEY = Any()
private val INNER_INSET_KEY = Any()

private const val PAD = 12          // text padding inside a bubble
private const val EDGE = 4          // space between a bubble and the window edge
private const val CODE_INSET = 10   // distance from the bubble edge to the code block fill
private const val USER_GAP = 40     // minimum free space left of a request bubble
private const val SLACK = 6         // extra width so a request does not wrap by a pixel

// both labels must have the same length: the label is replaced in place
private const val COPY_LABEL = "Copy  "
private const val COPIED_LABEL = "Copied"

private class BlockParagraphView(elem: Element) : ParagraphView(elem) {
    override fun paint(g: Graphics, a: Shape) {
        val at = element.attributes
        val bg = at.getAttribute(BG_KEY) as? Color
        if (bg != null) {
            val r = a.bounds
            val ml = at.getAttribute(ML_KEY) as? Int ?: 0
            val mr = at.getAttribute(MR_KEY) as? Int ?: 0
            val x = r.x + ml
            val w = r.width - ml - mr
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.clipRect(r.x, r.y, r.width, r.height)
                val arc = JBUI.scale(12)
                g2.color = bg
                when (at.getAttribute(POS_KEY) as? Int ?: 0) {
                    1 -> g2.fillRoundRect(x, r.y, w, r.height + arc, arc, arc)
                    2 -> g2.fillRect(x, r.y, w, r.height)
                    3 -> g2.fillRoundRect(x, r.y - arc, w, r.height + arc, arc, arc)
                    else -> g2.fillRoundRect(x, r.y, w, r.height, arc, arc)
                }
                val inner = at.getAttribute(INNER_KEY) as? Color
                if (inner != null) {
                    val ins = at.getAttribute(INNER_INSET_KEY) as? Int ?: 0
                    g2.color = inner
                    g2.fillRect(x + ins, r.y, w - 2 * ins, r.height)
                }
            } finally {
                g2.dispose()
            }
        }
        super.paint(g, a)
    }
}

private class BlockEditorKit : StyledEditorKit() {
    override fun getViewFactory(): ViewFactory {
        val base = super.getViewFactory()
        return ViewFactory { elem ->
            if (elem.name == AbstractDocument.ParagraphElementName) BlockParagraphView(elem) else base.create(elem)
        }
    }
}

private fun para(
    bg: Color?, pos: Int, ml: Int, mr: Int, padL: Int, padR: Int,
    above: Float = 0f, below: Float = 0f, inner: Color? = null, innerInset: Int = 0,
    align: Int = StyleConstants.ALIGN_LEFT
): SimpleAttributeSet {
    val a = SimpleAttributeSet()
    StyleConstants.setLeftIndent(a, (ml + padL).toFloat())
    StyleConstants.setRightIndent(a, (mr + padR).toFloat())
    StyleConstants.setSpaceAbove(a, above)
    StyleConstants.setSpaceBelow(a, below)
    StyleConstants.setLineSpacing(a, 0.12f)
    StyleConstants.setAlignment(a, align)
    if (bg != null) {
        a.addAttribute(BG_KEY, bg)
        a.addAttribute(POS_KEY, pos)
        a.addAttribute(ML_KEY, ml)
        a.addAttribute(MR_KEY, mr)
        if (inner != null) {
            a.addAttribute(INNER_KEY, inner)
            a.addAttribute(INNER_INSET_KEY, innerInset)
        }
    }
    return a
}

/** The paragraph styles for one bubble. */
private class BubbleStyles(private val bg: Color, private val ml: Int, private val mr: Int) {
    val top = para(bg, 1, ml, mr, PAD, PAD, above = 4f)
    val mid = para(bg, 2, ml, mr, PAD, PAD, below = 1f)
    val time = para(bg, 2, ml, mr, PAD, PAD, below = 1f, align = StyleConstants.ALIGN_RIGHT)
    val bot = para(bg, 3, ml, mr, PAD, PAD, below = 4f)
    val gap = para(bg, 2, ml, mr, PAD, PAD)

    fun codeHead(inner: Color, align: Int = StyleConstants.ALIGN_LEFT) =
        para(
            bg, 2, ml, mr, CODE_INSET + 10, CODE_INSET + 10,
            above = 3f, below = 1f, inner = inner, innerInset = CODE_INSET, align = align
        )

    fun code(inner: Color) = para(
        bg, 2, ml, mr, CODE_INSET + 10, CODE_INSET + 10,
        inner = inner, innerInset = CODE_INSET
    )
}

/** All character and paragraph styles, built from the current settings. */
private class Styles {
    val f = ChatTheme.fonts()
    private fun c(key: String) = ChatTheme.color(key)

    fun chars(
        fg: Color, bold: Boolean = false, italic: Boolean = false, mono: Boolean = false,
        bg: Color? = null, size: Int = if (mono) f.codeSize else f.chatSize
    ): SimpleAttributeSet {
        val a = SimpleAttributeSet()
        StyleConstants.setFontFamily(a, if (mono) f.codeFamily else f.chatFamily)
        StyleConstants.setFontSize(a, size)
        StyleConstants.setForeground(a, fg)
        StyleConstants.setBold(a, bold)
        StyleConstants.setItalic(a, italic)
        if (bg != null) StyleConstants.setBackground(a, bg)
        return a
    }

    val userBg = c("userBg")
    val assistant = BubbleStyles(c("assistantBg"), EDGE, EDGE)
    val tool = BubbleStyles(c("toolBg"), EDGE, EDGE)
    val plain = para(null, 0, EDGE, EDGE, 4, 4, below = 1f)
    val gapPara = para(null, 0, 0, 0, 0, 0)
    val codeBg = c("codeBg")
    val codeBlock = assistant.code(codeBg)
    val codeHead = assistant.codeHead(codeBg)
    val codeHeadRight = assistant.codeHead(codeBg, StyleConstants.ALIGN_RIGHT)
    val diffOldBg = c("diffOldBg")
    val diffNewBg = c("diffNewBg")

    val aText = chars(c("assistantText"))
    val aBold = chars(c("assistantText"), bold = true)
    val aInline = chars(c("assistantText"), mono = true, bg = c("inlineBg"))
    val aUser = chars(c("userText"))
    val aTime = chars(c("time"), size = f.smallSize)
    val aMuted = chars(c("muted"), italic = true)
    val aError = chars(c("error"))
    val aToolIcon = chars(c("toolHead"), bold = true)
    val aToolName = chars(c("toolHead"), bold = true, mono = true)
    val aToolArgs = chars(c("toolMuted"), mono = true)
    val aToolOk = chars(c("muted"), mono = true, size = f.smallSize)
    val aToolErr = chars(c("error"), mono = true, size = f.smallSize)
    val aCodeLabel = chars(c("codeLabel"), mono = true, size = f.smallSize)
    val aCopy = chars(c("accent"), size = f.smallSize).also { StyleConstants.setUnderline(it, true) }
    val aCopyDone = chars(c("ok"), size = f.smallSize)
    val aFoldGlyph = chars(c("toolHead"), bold = true, mono = true)
    val aAsk = chars(c("toolHead"))
    val aAllow = chars(c("ok"), bold = true).also { StyleConstants.setUnderline(it, true) }
    val aDeny = chars(c("error"), bold = true).also { StyleConstants.setUnderline(it, true) }
    val aAllowDone = chars(c("ok"), bold = true)
    val aDenyDone = chars(c("error"), bold = true)
    val tiny = chars(c("assistantText"), size = 4)
    val gapChar = chars(c("assistantText"), size = 6)

    val aCode: Map<Kind, SimpleAttributeSet> = mapOf(
        Kind.PLAIN to chars(c("synPlain"), mono = true),
        Kind.COMMENT to chars(c("synComment"), mono = true, italic = true),
        Kind.STRING to chars(c("synString"), mono = true),
        Kind.VAR to chars(c("synVar"), mono = true),
        Kind.NUMBER to chars(c("synNumber"), mono = true),
        Kind.KEYWORD to chars(c("synKeyword"), mono = true),
        Kind.CALL to chars(c("synCall"), mono = true)
    )
}

// ---------------------------------------------------------------------------------------------
// Text pane that shows a hint while the chat is empty
// ---------------------------------------------------------------------------------------------

private class ChatPane : JTextPane() {
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (document.length != 0) return
        val g2 = g.create() as Graphics2D
        try {
            UIUtil.applyRenderingHints(g2)
            val base = UIUtil.getLabelFont()
            val titleFont = base.deriveFont(Font.BOLD, base.size2D + 2f)
            val titleFm = g2.getFontMetrics(titleFont)
            val bodyFm = g2.getFontMetrics(base)
            val title = "Chat is empty"
            val lines = listOf("Pick a model below and write a message.", "Enter to send · Shift+Enter for a new line")
            val gap = JBUI.scale(6)
            val total = titleFm.height + gap + bodyFm.height * lines.size
            var y = (height - total) / 2 + titleFm.ascent

            g2.font = titleFont
            g2.color = ChatTheme.color("muted")
            g2.drawString(title, (width - titleFm.stringWidth(title)) / 2, y)

            y += titleFm.descent + gap + bodyFm.ascent
            g2.font = base
            for (line in lines) {
                g2.drawString(line, (width - bodyFm.stringWidth(line)) / 2, y)
                y += bodyFm.height
            }
        } finally {
            g2.dispose()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The chat view. All methods must be called on the EDT.
//
// While an answer bubble or tool card is open, the document always ends with
//     ... content ... | placeholder line "\n" | bottom padding "\n"
// and new text is inserted just before the placeholder (see end()). That keeps the bubble closed,
// padded and rounded at the bottom while it is still growing.
// ---------------------------------------------------------------------------------------------

class ChatView private constructor(private val pane: JTextPane) : JBScrollPane(pane) {
    constructor() : this(ChatPane())

    private enum class Mode { NONE, TEXT, CODE }

    private class Region(var start: Int, var end: Int, val onClick: () -> Unit)

    private class Fold(
        val lines: List<String>, val lang: String,
        val head: SimpleAttributeSet, val body: SimpleAttributeSet
    ) {
        lateinit var region: Region
        var bodyStart = 0
        var bodyLen = 0
        var expanded = true
    }

    private class UserBubble(var start: Int, var end: Int, val textW: Int)

    private var S = Styles()

    private val timeFmt = DateTimeFormatter.ofPattern("d MMM HH:mm")
    private fun now(): String = LocalDateTime.now().format(timeFmt)

    private val codeKeys = setOf("content", "old_text", "new_text")

    // streaming state
    private var mode = Mode.NONE
    private var atLineStart = true
    private val held = StringBuilder()        // backticks at line start (possible code fence)
    private var inFence = false               // reading the info string after ```
    private val fenceInfo = StringBuilder()
    private val codeLine = StringBuilder()
    private var codeData: StringBuilder? = null   // full text of the code block being streamed (for Copy)
    private var highlighter: CodeHighlighter? = null
    private val buf = StringBuilder()         // pending run of text
    private var inline = false
    private var bold = false
    private var pendingStar = false
    private var blankPending = false          // an empty line was seen; emitted only if more text follows
    private var needGap = false               // a code block just ended; spacing only if more text follows

    // bubble state
    private var trailer = 0                   // characters after the insertion point (placeholder + padding)
    private var assistantOpen = false
    private var hasContent = false
    private var bubbleStart = 0
    private var toolOpen = false
    private var thinkingShown = false
    private var thinkStart = 0
    private var thinkLen = 0

    // clickable ranges, folds, request bubbles
    private val regions = mutableListOf<Region>()
    private val folds = mutableListOf<Fold>()
    private val userBubbles = mutableListOf<UserBubble>()
    private var pending: ((Boolean) -> Unit)? = null
    private var lastWidth = -1

    private val doc: StyledDocument get() = pane.styledDocument

    /** Where new content goes: before the placeholder line and bottom padding of an open bubble. */
    private fun end(): Int = doc.length - trailer

    init {
        pane.editorKit = BlockEditorKit()
        pane.isEditable = false
        pane.background = ChatTheme.color("chatBg")
        pane.border = JBUI.Borders.empty(6, 2)
        (pane.caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
        border = JBUI.Borders.empty()

        // repaint so the "chat is empty" hint appears and disappears immediately
        doc.addDocumentListener(object : DocumentListener {
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
                if (SwingUtilities.isLeftMouseButton(e)) regionAt(e)?.onClick?.invoke()
            }
        })
        pane.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                pane.cursor = Cursor.getPredefinedCursor(
                    if (regionAt(e) != null) Cursor.HAND_CURSOR else Cursor.TEXT_CURSOR
                )
            }
        })
        pane.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (pane.width != lastWidth) {
                    lastWidth = pane.width; relayoutUserBubbles()
                }
            }
        })
    }

    /** Re-reads colors and fonts from the settings. Existing messages keep their old look. */
    fun applyTheme() {
        S = Styles()
        pane.background = ChatTheme.color("chatBg")
        pane.repaint()
    }

    // ---- public API ----

    fun userMessage(text: String) {
        closeBlocks(); gap()
        val body = text.trimEnd()
        val stamp = now()
        val textW = measure(body, stamp)
        val ub = BubbleStyles(S.userBg, userMargin(textW), EDGE)
        val start = doc.length
        pad(ub.top)
        emit(body + "\n", S.aUser, ub.mid)
        emit(stamp + "\n", S.aTime, ub.time)
        pad(ub.bot)
        userBubbles.add(UserBubble(start, doc.length, textW))
        scrollToBottom()
    }

    /** Starts a new answer. The bubble itself is created when the first text arrives. */
    fun beginAssistant() {
        closeBlocks()
        thinkingShown = false
    }

    fun assistantText(chunk: String) {
        val follow = isAtBottom()
        hideThinking()
        openAssistant()
        for (c in chunk) feed(c)
        flushRun()
        if (follow) scrollToBottom()
    }

    fun thinking() {
        if (thinkingShown) return
        thinkingShown = true
        openAssistant()
        flushRun()
        if (!placeholderEmpty()) emit("\n", S.aText, S.assistant.mid)
        atLineStart = true
        val at = end()
        emit("thinking…\n", S.aMuted, S.assistant.mid)
        thinkStart = at
        thinkLen = end() - at
        resetPlaceholder()
        scrollToBottom()
    }

    fun toolCall(name: String, argsJson: String) {
        closeBlocks()
        openToolCard()

        val obj = try {
            JsonParser.parseString(argsJson).asJsonObject
        } catch (e: Exception) {
            null
        }
        val simple = StringBuilder()
        val codeArgs = ArrayList<Pair<String, String>>()
        obj?.entrySet()?.forEach { (k, v) ->
            val s = if (v.isJsonPrimitive) v.asString else v.toString()
            if (k in codeKeys || s.contains('\n') || s.length > 120) codeArgs.add(k to s)
            else simple.append("  ").append(k).append('=').append(s)
        }
        if (obj == null && argsJson.isNotBlank()) simple.append("  ").append(argsJson.take(200))

        emit("⚙ ", S.aToolIcon, S.tool.mid)
        emit(name, S.aToolName, S.tool.mid)
        if (simple.isNotEmpty()) emit(simple.toString(), S.aToolArgs, S.tool.mid)
        emit("\n", S.aToolArgs, S.tool.mid)
        resetPlaceholder()

        val lang = langFromPath(obj?.str("path"))
        for ((label, text) in codeArgs) {
            val bg = when (label) {
                "old_text" -> S.diffOldBg
                "new_text" -> S.diffNewBg
                else -> S.codeBg
            }
            foldSection(label, text, lang, bg)
        }
        scrollToBottom()
    }

    fun toolResult(summary: String, failed: Boolean) {
        emit("↳ $summary\n", if (failed) S.aToolErr else S.aToolOk, S.tool.mid)
        resetPlaceholder()
        closeTool()
        thinkingShown = false
        scrollToBottom()
    }

    fun info(text: String) = block { emit("$text\n", S.aMuted, S.plain) }
    fun error(text: String) = block { emit("$text\n", S.aError, S.plain) }

    fun endAssistant() {
        closeBlocks()
    }

    fun clear() {
        doc.remove(0, doc.length)
        resetTail()
        mode = Mode.NONE; atLineStart = true
        held.setLength(0); fenceInfo.setLength(0); codeLine.setLength(0); buf.setLength(0)
        inFence = false; inline = false; bold = false; pendingStar = false; highlighter = null; codeData = null
        blankPending = false; needGap = false
        trailer = 0; assistantOpen = false; hasContent = false; toolOpen = false; bubbleStart = 0
        thinkLen = 0; thinkingShown = false
        regions.clear(); folds.clear(); userBubbles.clear(); pending = null
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

    // ---- request bubble width ----

    private fun measure(body: String, stamp: String): Int {
        val fm = pane.getFontMetrics(Font(S.f.chatFamily, Font.PLAIN, S.f.chatSize))
        val fs = pane.getFontMetrics(Font(S.f.chatFamily, Font.PLAIN, S.f.smallSize))
        val widest = body.split("\n").maxOf { fm.stringWidth(it.replace("\t", "    ")) }
        return maxOf(widest, fs.stringWidth(stamp))
    }

    private fun availWidth(): Int {
        val w = if (pane.width > 0) pane.width else viewport.width
        return (if (w > 0) w else 420) - 4
    }

    /** Left margin that makes the bubble as narrow as its text, but never wider than the window minus a gap. */
    private fun userMargin(textW: Int): Int {
        val avail = availWidth()
        val bubble = minOf(textW + 2 * PAD + SLACK, avail - USER_GAP - EDGE)
        return (avail - EDGE - bubble).coerceAtLeast(USER_GAP)
    }

    private fun relayoutUserBubbles() {
        for (b in userBubbles) {
            val ml = userMargin(b.textW)
            val a = SimpleAttributeSet()
            StyleConstants.setLeftIndent(a, (ml + PAD).toFloat())
            StyleConstants.setRightIndent(a, (EDGE + PAD).toFloat())
            a.addAttribute(ML_KEY, ml)
            doc.setParagraphAttributes(b.start, b.end - b.start, a, false)
        }
    }

    // ---- bubbles and cards ----

    private fun openAssistant() {
        if (assistantOpen) return
        bubbleStart = doc.length
        gap()
        pad(S.assistant.top)
        emit("\n", S.tiny, S.assistant.mid)    // placeholder: the line being written
        emit("\n", S.tiny, S.assistant.bot)    // bottom padding, kept at the end while the bubble is open
        trailer = 2
        assistantOpen = true
        hasContent = false
        blankPending = false
        needGap = false
    }

    private fun closeAssistant() {
        assistantOpen = false
        if (!hasContent) {
            doc.remove(bubbleStart, doc.length - bubbleStart)
            trailer = 0
            resetTail()
            return
        }
        dropEmptyPlaceholder(S.assistant.bot)
        trailer = 0
    }

    private fun openToolCard() {
        gap()
        pad(S.tool.top)
        emit("\n", S.tiny, S.tool.mid)
        emit("\n", S.tiny, S.tool.bot)
        trailer = 2
        toolOpen = true
    }

    private fun closeTool() {
        if (!toolOpen) return
        toolOpen = false
        dropEmptyPlaceholder(S.tool.bot)
        trailer = 0
    }

    /** True when the line being written (the placeholder paragraph) has no text yet. */
    private fun placeholderEmpty(): Boolean = doc.length >= 3 && doc.getText(doc.length - 3, 1) == "\n"

    /** Removes the unused placeholder line so no blank row is left above the bottom padding. */
    private fun dropEmptyPlaceholder(botStyle: AttributeSet) {
        if (placeholderEmpty()) {
            doc.remove(doc.length - 2, 1)
            doc.setParagraphAttributes(doc.length - 1, 1, botStyle, true)
        }
    }

    /** After a multi-part line the placeholder inherits that line's style; make it neutral again. */
    private fun resetPlaceholder() {
        if (trailer == 2) {
            doc.setParagraphAttributes(end(), 1, if (toolOpen) S.tool.mid else S.assistant.mid, true)
        }
    }

    private fun hideThinking() {
        if (thinkLen == 0) return
        val s = thinkStart
        val len = thinkLen
        thinkLen = 0
        doc.remove(s, len)
        shift(s + len, -len)
    }

    /** Finishes streaming text and closes any open bubble or card. A bubble without content is deleted. */
    private fun closeBlocks() {
        hideThinking()
        endStream()
        if (assistantOpen) closeAssistant()
        if (toolOpen) closeTool()
    }

    private fun block(body: () -> Unit) {
        closeBlocks(); gap()
        body()
        scrollToBottom()
    }

    /**
     * The document always ends with an invisible empty paragraph. Text inserted into it leaves a new empty
     * paragraph behind that inherits the style, which showed up as a blank row. Keep it plain.
     */
    private fun resetTail() {
        doc.setParagraphAttributes(doc.length, 1, S.gapPara, true)
    }

    // ---- low-level helpers ----

    private fun emit(text: String, ch: AttributeSet, p: AttributeSet, at: Int = end()): Int {
        if (text.isEmpty()) return at
        doc.insertString(at, text, ch)
        doc.setParagraphAttributes(at, text.length, p, true)
        if (at + text.length >= doc.length - trailer) resetTail()
        return at + text.length
    }

    private fun pad(p: AttributeSet) = emit("\n", S.tiny, p)
    private fun gap() {
        if (end() > 0) emit("\n", S.gapChar, S.gapPara)
    }

    private fun isAtBottom(): Boolean {
        val sb = verticalScrollBar
        return sb.value + sb.visibleAmount >= sb.maximum - JBUI.scale(24)
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater { verticalScrollBar.value = verticalScrollBar.maximum }
    }

    // ---- foldable code sections (tool cards) ----

    private fun foldSection(label: String, text: String, lang: String, bg: Color) {
        val lines = text.replace("\r", "").replace("\t", "    ").split("\n").let {
            if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it
        }
        val f = Fold(lines, lang, S.tool.codeHead(bg), S.tool.code(bg))
        val count = "${lines.size} line${if (lines.size == 1) "" else "s"}"
        val title = " $label · $count" + if (lang.isNotEmpty()) " · $lang" else ""

        val hs = end()
        emit("▾", S.aFoldGlyph, f.head)
        emit(title, S.aCodeLabel, f.head)
        f.region = Region(hs, end()) { toggle(f) }
        emit("\n", S.aCodeLabel, f.head)

        f.bodyStart = end()
        f.bodyLen = writeBody(f, f.bodyStart) - f.bodyStart
        regions.add(f.region)
        folds.add(f)
        resetPlaceholder()
    }

    private fun writeBody(f: Fold, at: Int): Int {
        var pos = at
        val h = CodeHighlighter(f.lang)
        for (line in f.lines) {
            for ((t, kind) in h.line(line)) pos = emit(t, S.aCode.getValue(kind), f.body, pos)
            pos = emit("\n", S.aCode.getValue(Kind.PLAIN), f.body, pos)
        }
        return pos
    }

    private fun toggle(f: Fold) {
        if (f.expanded) {
            doc.remove(f.bodyStart, f.bodyLen)
            shift(f.bodyStart + f.bodyLen, -f.bodyLen, skip = f)
            f.bodyLen = 0
            f.expanded = false
        } else {
            val len = writeBody(f, f.bodyStart) - f.bodyStart
            shift(f.bodyStart, len, skip = f)
            f.bodyLen = len
            f.expanded = true
        }
        (doc as AbstractDocument).replace(f.region.start, 1, if (f.expanded) "▾" else "▸", S.aFoldGlyph)
        doc.setParagraphAttributes(f.region.start, 1, f.head, true)
    }

    /** After text was inserted or removed in the middle, move everything that sits at or after [from]. */
    private fun shift(from: Int, delta: Int, skip: Fold? = null) {
        for (r in regions) if (r.start >= from) {
            r.start += delta; r.end += delta
        }
        for (f in folds) if (f !== skip && f.bodyStart >= from) f.bodyStart += delta
        for (b in userBubbles) if (b.start >= from) {
            b.start += delta; b.end += delta
        }
        if (thinkLen > 0 && thinkStart >= from) thinkStart += delta
        if (assistantOpen && bubbleStart >= from) bubbleStart += delta
    }

    private fun regionAt(e: MouseEvent): Region? {
        val off = pane.viewToModel2D(e.point)
        val r = regions.firstOrNull { off >= it.start && off <= it.end } ?: return null
        val rect = try {
            pane.modelToView2D(off)
        } catch (ex: Exception) {
            null
        } ?: return null
        return if (e.y >= rect.y && e.y <= rect.y + rect.height) r else null
    }

    private fun langFromPath(path: String?): String {
        val ext = path?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (ext) {
            "php", "phtml", "inc" -> "php"
            "js", "mjs", "jsx" -> "js"
            "ts", "tsx" -> "ts"
            "py" -> "py"
            "kt", "kts" -> "kt"
            "sh", "bash" -> "sh"
            "yml", "yaml" -> "yaml"
            else -> ext
        }
    }

    // ---- copy button of code blocks ----

    private fun copyCode(data: CharSequence, reg: Region) {
        CopyPasteManager.getInstance().setContents(StringSelection(data.toString()))
        setCopyLabel(reg, COPIED_LABEL, S.aCopyDone)
        val t = Timer(1500) { setCopyLabel(reg, COPY_LABEL, S.aCopy) }
        t.isRepeats = false
        t.start()
    }

    private fun setCopyLabel(reg: Region, label: String, attrs: AttributeSet) {
        if (reg !in regions || reg.end - reg.start != label.length) return
        try {
            (doc as AbstractDocument).replace(reg.start, label.length, label, attrs)
        } catch (e: Exception) {
            // the document changed meanwhile; nothing to restore
        }
    }

    // ---- inline approval ----

    fun requestApproval(prompt: String, onDecision: (Boolean) -> Unit) {
        val ls = end()
        emit("$prompt?  ", S.aAsk, S.tool.mid)
        val a0 = end()
        emit("[ Allow ]", S.aAllow, S.tool.mid)
        val a1 = end()
        emit("   ", S.aAsk, S.tool.mid)
        val d0 = end()
        emit("[ Deny ]", S.aDeny, S.tool.mid)
        val d1 = end()
        emit("\n", S.aAsk, S.tool.mid)
        resetPlaceholder()

        var done = false
        lateinit var allowR: Region
        lateinit var denyR: Region
        fun decide(ok: Boolean) {
            if (done) return
            done = true
            pending = null
            regions.remove(allowR); regions.remove(denyR)
            val oldLen = d1 - ls
            val text = if (ok) "✓ Allowed: $prompt" else "✗ Denied: $prompt"
            (doc as AbstractDocument).replace(ls, oldLen, text, if (ok) S.aAllowDone else S.aDenyDone)
            doc.setParagraphAttributes(ls, text.length, S.tool.mid, true)
            shift(ls + oldLen, text.length - oldLen)
            onDecision(ok)
        }
        allowR = Region(a0, a1) { decide(true) }
        denyR = Region(d0, d1) { decide(false) }
        regions.add(allowR); regions.add(denyR)
        pending = { ok -> decide(ok) }
        scrollToBottom()
    }

    /** Called when the user presses Stop or the panel closes while a change is awaiting approval. */
    fun denyPending() {
        pending?.invoke(false)
    }

    // ---- streaming markdown (fences, `inline code`, **bold**) ----

    private fun feed(c: Char) {
        if (inFence) {
            if (c == '\n') finishFenceLine() else fenceInfo.append(c)
            return
        }
        if (atLineStart && (held.isNotEmpty() || c == '`')) {
            if (c == '`') {
                held.append(c)
                if (held.length == 3) {
                    held.setLength(0); inFence = true; fenceInfo.setLength(0)
                }
                return
            }
            release()
        }
        normal(c)
    }

    private fun release() {
        val h = held.toString()
        held.setLength(0)
        h.forEach { normal(it) }
    }

    private fun normal(c: Char) {
        if (mode == Mode.CODE) {
            if (c == '\n') {
                emitCodeLine(codeLine.toString()); codeLine.setLength(0); atLineStart = true
            } else {
                codeLine.append(c); atLineStart = false
            }
            return
        }
        if (pendingStar && c != '*') {
            buf.append('*'); pendingStar = false
        }
        if (c == '\n') {
            flushRun()
            if (atLineStart) {
                if (hasContent) blankPending = true       // a blank line: only drawn if more text follows
            } else {
                emit("\n", S.aText, S.assistant.mid)
                inline = false; bold = false
            }
            atLineStart = true
            return
        }
        if (mode == Mode.NONE) mode = Mode.TEXT
        if (atLineStart) startTextLine()
        atLineStart = false
        when {
            c == '`' -> {
                flushRun(); inline = !inline
            }

            c == '*' && !inline -> {
                if (pendingStar) {
                    flushRun(); bold = !bold; pendingStar = false
                } else pendingStar = true
            }

            else -> buf.append(c)
        }
    }

    private fun startTextLine() {
        if (needGap) {
            pad(S.assistant.gap); needGap = false; blankPending = false
        } else if (blankPending) {
            emit("\n", S.gapChar, S.assistant.mid); blankPending = false
        }
        hasContent = true
    }

    private fun flushRun() {
        if (buf.isEmpty()) return
        val attrs = when {
            inline -> S.aInline; bold -> S.aBold; else -> S.aText
        }
        emit(buf.toString(), attrs, S.assistant.mid)
        buf.setLength(0)
    }

    private fun finishFenceLine() {
        inFence = false
        atLineStart = true
        val lang = fenceInfo.toString().trim().lowercase().substringBefore(' ')
        fenceInfo.setLength(0)
        if (mode == Mode.CODE) {                       // closing fence
            if (codeLine.isNotEmpty()) {
                emitCodeLine(codeLine.toString()); codeLine.setLength(0)
            }
            pad(S.codeBlock)
            needGap = true
            mode = Mode.TEXT
            highlighter = null
            codeData = null
        } else {                                       // opening fence
            flushRun()
            if (hasContent) pad(S.assistant.gap)
            hasContent = true
            needGap = false
            blankPending = false
            mode = Mode.CODE
            highlighter = CodeHighlighter(lang)
            codeLine.setLength(0)

            val data = StringBuilder()
            codeData = data
            emit(lang.ifEmpty { "code" } + "   ", S.aCodeLabel, S.codeHeadRight)
            val c0 = end()
            emit(COPY_LABEL, S.aCopy, S.codeHeadRight)
            lateinit var reg: Region
            reg = Region(c0, end()) { copyCode(data, reg) }
            regions.add(reg)
            emit("\n", S.aCodeLabel, S.codeHeadRight)
            resetPlaceholder()
        }
    }

    private fun emitCodeLine(line: String) {
        codeData?.append(line)?.append('\n')
        val h = highlighter ?: CodeHighlighter("").also { highlighter = it }
        for ((text, kind) in h.line(line.replace("\t", "    "))) emit(text, S.aCode.getValue(kind), S.codeBlock)
        emit("\n", S.aCode.getValue(Kind.PLAIN), S.codeBlock)
        resetPlaceholder()
    }

    /** Finishes whatever is being streamed (open code block, held backticks, pending text). */
    private fun endStream() {
        if (inFence) finishFenceLine()
        if (held.isNotEmpty()) release()
        if (mode == Mode.CODE) {
            if (codeLine.isNotEmpty()) {
                emitCodeLine(codeLine.toString()); codeLine.setLength(0)
            }
            pad(S.codeBlock)
        } else {
            if (pendingStar) {
                buf.append('*'); pendingStar = false
            }
            flushRun()
        }
        mode = Mode.NONE
        atLineStart = true
        inline = false
        bold = false
        highlighter = null
        codeData = null
        blankPending = false
        needGap = false
    }
}