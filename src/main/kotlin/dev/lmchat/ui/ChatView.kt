package dev.lmchat.ui

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Graphics
import java.awt.Shape
import javax.swing.JTextPane
import javax.swing.SwingUtilities
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
// Paragraph backgrounds: Swing only paints backgrounds behind characters, so we paint full-width
// blocks ourselves for paragraphs that carry BG_KEY.
// ---------------------------------------------------------------------------------------------

private val BG_KEY = Any()

private class BlockParagraphView(elem: Element) : ParagraphView(elem) {
    override fun paint(g: Graphics, a: Shape) {
        val bg = element.attributes.getAttribute(BG_KEY) as? Color
        if (bg != null) {
            val r = a.bounds
            g.color = bg
            g.fillRect(r.x, r.y, r.width, r.height)
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

private object Palette {
    val userHead = JBColor(Color(0x1F6FEB), Color(0x58A6FF))
    val userBg = JBColor(Color(0xEAF2FF), Color(0x26303F))
    val userText = JBColor(Color(0x0B2545), Color(0xD6E4F5))
    val botHead = JBColor(Color(0x1A7F37), Color(0x56D364))
    val toolHead = JBColor(Color(0xB45309), Color(0xE3B341))
    val toolBg = JBColor(Color(0xFFF6E0), Color(0x372F1D))
    val toolMuted = JBColor(Color(0x7A5B1E), Color(0xB8A36A))
    val codeBg = JBColor(Color(0xF3F4F6), Color(0x25282C))
    val codeLabel = JBColor(Color(0x6B7280), Color(0x8B949E))
    val inlineBg = JBColor(Color(0xE7E9EC), Color(0x373B40))
    val muted = JBColor(Color(0x6B7280), Color(0x8B949E))
    val error = JBColor(Color(0xCF222E), Color(0xF85149))
}

private fun schemeColor(key: TextAttributesKey, fallback: Color): Color =
    EditorColorsManager.getInstance().globalScheme.getAttributes(key)?.foregroundColor ?: fallback

// ---------------------------------------------------------------------------------------------
// Tiny line-based syntax highlighter (good enough for chat snippets)
// ---------------------------------------------------------------------------------------------

enum class Kind { PLAIN, COMMENT, STRING, VAR, NUMBER, KEYWORD, CALL }

class CodeHighlighter(lang: String) {
    private val ci = lang == "php" || lang == "sql"
    private val keywords = KEYWORDS[ALIASES[lang] ?: lang] ?: GENERIC
    private val re = if (lang in HASH_LANGS) RE_HASH else RE_PLAIN
    private var inBlock = false

    fun line(s: String): List<Pair<String, Kind>> {
        val out = ArrayList<Pair<String, Kind>>()
        var i = 0
        if (inBlock) {
            val end = s.indexOf("*/")
            if (end < 0) return listOf(s to Kind.COMMENT)
            out.add(s.substring(0, end + 2) to Kind.COMMENT)
            inBlock = false
            i = end + 2
        }
        for (m in re.findAll(s, i)) {
            if (m.range.first > i) out.add(s.substring(i, m.range.first) to Kind.PLAIN)
            val t = m.value
            val kind = when {
                m.groups[1] != null -> {
                    if (t.startsWith("/*") && !t.endsWith("*/")) inBlock = true
                    Kind.COMMENT
                }

                m.groups[2] != null -> Kind.STRING
                m.groups[3] != null -> Kind.VAR
                m.groups[4] != null -> Kind.NUMBER
                (if (ci) t.lowercase() else t) in keywords -> Kind.KEYWORD
                s.getOrNull(m.range.last + 1) == '(' -> Kind.CALL
                else -> Kind.PLAIN
            }
            out.add(t to kind)
            i = m.range.last + 1
        }
        if (i < s.length) out.add(s.substring(i) to Kind.PLAIN)
        return out
    }

    companion object {
        private const val STR = """("(?:\\.|[^"\\])*"?|'(?:\\.|[^'\\])*'?|`(?:\\.|[^`\\])*`?)"""
        private const val REST = """|(\$[A-Za-z_]\w*)|(\b\d[\d_]*(?:\.\d+)?\b)|(\b[A-Za-z_]\w*\b)"""
        private val RE_PLAIN = Regex("""(/\*.*?\*/|/\*.*|//.*)|$STR$REST""")
        private val RE_HASH = Regex("""(/\*.*?\*/|/\*.*|//.*|#.*)|$STR$REST""")

        private fun words(s: String) = s.split(' ').filter { it.isNotEmpty() }.toSet()

        private val GENERIC =
            words("if else for while do return function class new true false null break continue switch case default try catch finally throw import export")
        private val KEYWORDS = mapOf(
            "php" to words("abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile enum extends final finally fn for foreach function global goto if implements include include_once instanceof insteadof interface isset list match namespace new or print private protected public readonly require require_once return static switch throw trait try unset use var while xor yield null true false self parent"),
            "js" to words("async await break case catch class const continue debugger default delete do else enum export extends false finally for from function if implements import in instanceof interface let new null of package private protected public return static super switch this throw true try type typeof undefined var void while with yield"),
            "py" to words("and as assert async await break class continue def del elif else except False finally for from global if import in is lambda None nonlocal not or pass raise return True try while with yield self"),
            "java" to words("abstract boolean break byte case catch char class const continue default do double else enum extends false final finally float for fun if implements import in instanceof int interface is long new null object open override package private protected public return short static super switch this throw true try val var void when while"),
            "sql" to words("select from where insert into values update set delete join left right inner outer on group by order having limit offset create table alter drop index primary key foreign references distinct union all as and or not null like in is between exists case when then else end asc desc"),
            "sh" to words("if then else elif fi for while do done case esac function in return export local echo cd exit")
        )
        private val ALIASES = mapOf(
            "javascript" to "js", "jsx" to "js", "ts" to "js", "typescript" to "js", "tsx" to "js",
            "python" to "py", "kotlin" to "java", "kt" to "java", "csharp" to "java", "cs" to "java",
            "bash" to "sh", "shell" to "sh", "zsh" to "sh", "phtml" to "php"
        )
        private val HASH_LANGS = setOf(
            "php", "phtml", "py", "python", "sh", "bash", "shell", "zsh", "yaml", "yml",
            "ruby", "rb", "ini", "conf", "toml", "dockerfile", "makefile"
        )
    }
}

// ---------------------------------------------------------------------------------------------
// The chat view. All methods must be called on the EDT.
// ---------------------------------------------------------------------------------------------

class ChatView private constructor(private val pane: JTextPane) : JBScrollPane(pane) {
    constructor() : this(JTextPane())

    private enum class Mode { NONE, TEXT, CODE }

    // fonts
    private val uiFont = UIUtil.getLabelFont()
    private val scheme = EditorColorsManager.getInstance().globalScheme
    private val monoFamily: String = scheme.editorFontName
    private val textSize = uiFont.size
    private val monoSize = scheme.editorFontSize

    private fun chars(
        fg: Color, bold: Boolean = false, italic: Boolean = false, mono: Boolean = false,
        bg: Color? = null, size: Int = if (mono) monoSize else textSize
    ): SimpleAttributeSet {
        val a = SimpleAttributeSet()
        StyleConstants.setFontFamily(a, if (mono) monoFamily else uiFont.family)
        StyleConstants.setFontSize(a, size)
        StyleConstants.setForeground(a, fg)
        StyleConstants.setBold(a, bold)
        StyleConstants.setItalic(a, italic)
        if (bg != null) StyleConstants.setBackground(a, bg)
        return a
    }

    private fun para(bg: Color?, left: Float, right: Float, above: Float = 0f, below: Float = 0f): SimpleAttributeSet {
        val a = SimpleAttributeSet()
        StyleConstants.setLeftIndent(a, left)
        StyleConstants.setRightIndent(a, right)
        StyleConstants.setSpaceAbove(a, above)
        StyleConstants.setSpaceBelow(a, below)
        StyleConstants.setLineSpacing(a, 0.12f)
        if (bg != null) a.addAttribute(BG_KEY, bg)
        return a
    }

    // paragraph styles
    private val pNormal = para(null, 4f, 4f, 0f, 1f)
    private val pGap = para(null, 0f, 0f)
    private val pUser = para(Palette.userBg, 10f, 10f)
    private val pUserHead = para(Palette.userBg, 10f, 10f, 2f, 0f)
    private val pCode = para(Palette.codeBg, 14f, 10f)
    private val pCodeHead = para(Palette.codeBg, 14f, 10f, 3f, 1f)
    private val pTool = para(Palette.toolBg, 10f, 10f)

    // character styles
    private val aText = chars(JBColor.foreground())
    private val aBold = chars(JBColor.foreground(), bold = true)
    private val aInline = chars(JBColor.foreground(), mono = true, bg = Palette.inlineBg)
    private val aUserHead = chars(Palette.userHead, bold = true)
    private val aUser = chars(Palette.userText)
    private val aBotHead = chars(Palette.botHead, bold = true)
    private val aToolIcon = chars(Palette.toolHead, bold = true)
    private val aToolName = chars(Palette.toolHead, bold = true, mono = true)
    private val aToolArgs = chars(Palette.toolMuted, mono = true)
    private val aToolOk = chars(Palette.muted, mono = true, size = textSize - 1)
    private val aToolErr = chars(Palette.error, mono = true, size = textSize - 1)
    private val aMuted = chars(Palette.muted, italic = true)
    private val aError = chars(Palette.error)
    private val aCodeLabel = chars(Palette.codeLabel, mono = true, size = monoSize - 2)
    private val tiny = chars(JBColor.foreground(), size = 4)

    private val aCode: Map<Kind, SimpleAttributeSet> = mapOf(
        Kind.PLAIN to chars(
            schemeColor(
                DefaultLanguageHighlighterColors.IDENTIFIER,
                JBColor(Color(0x24292F), Color(0xD4D4D4))
            ), mono = true
        ),
        Kind.COMMENT to chars(
            schemeColor(DefaultLanguageHighlighterColors.LINE_COMMENT, Palette.codeLabel),
            mono = true, italic = true
        ),
        Kind.STRING to chars(
            schemeColor(
                DefaultLanguageHighlighterColors.STRING,
                JBColor(Color(0x067D17), Color(0x6A8759))
            ), mono = true
        ),
        Kind.VAR to chars(
            schemeColor(
                DefaultLanguageHighlighterColors.LOCAL_VARIABLE,
                JBColor(Color(0x0F6E8C), Color(0x7EC3E6))
            ), mono = true
        ),
        Kind.NUMBER to chars(
            schemeColor(
                DefaultLanguageHighlighterColors.NUMBER,
                JBColor(Color(0x1750EB), Color(0x6897BB))
            ), mono = true
        ),
        Kind.KEYWORD to chars(
            schemeColor(
                DefaultLanguageHighlighterColors.KEYWORD,
                JBColor(Color(0x0033B3), Color(0xCC7832))
            ), mono = true
        ),
        Kind.CALL to chars(
            schemeColor(
                DefaultLanguageHighlighterColors.FUNCTION_CALL,
                JBColor(Color(0x00627A), Color(0xFFC66D))
            ), mono = true
        )
    )

    // streaming state
    private var mode = Mode.NONE
    private var atLineStart = true
    private val held = StringBuilder()        // backticks at line start (possible code fence)
    private var inFence = false               // reading the info string after ```
    private val fenceInfo = StringBuilder()
    private val codeLine = StringBuilder()
    private var highlighter: CodeHighlighter? = null
    private val buf = StringBuilder()         // pending run of text
    private var inline = false
    private var bold = false
    private var pendingStar = false
    private var thinkingShown = false

    init {
        pane.editorKit = BlockEditorKit()
        pane.isEditable = false
        pane.background = UIUtil.getEditorPaneBackground()
        pane.border = JBUI.Borders.empty(6, 4)
        (pane.caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
        border = JBUI.Borders.empty()
    }

    private val doc: StyledDocument get() = pane.styledDocument

    // ---- public API ----

    fun userMessage(text: String) {
        endStream(); ensureNewline(); gap()
        emit("You\n", aUserHead, pUserHead)
        emit(text.trimEnd() + "\n", aUser, pUser)
        pad(pUser)
        scrollToBottom()
    }

    fun beginAssistant() {
        endStream(); ensureNewline(); gap()
        thinkingShown = false
        emit("Assistant\n", aBotHead, pNormal)
        scrollToBottom()
    }

    fun assistantText(chunk: String) {
        val follow = isAtBottom()
        for (c in chunk) feed(c)
        flushRun()
        if (follow) scrollToBottom()
    }

    fun thinking() {
        if (thinkingShown) return
        thinkingShown = true
        flushRun(); ensureNewline()
        emit("thinking…\n", aMuted, pNormal)
    }

    fun toolCall(name: String, args: String) {
        endStream(); ensureNewline()
        pad(pTool)
        emit("⚙ ", aToolIcon, pTool)
        emit(name, aToolName, pTool)
        if (args.isNotEmpty()) emit("  $args", aToolArgs, pTool)
        emit("\n", aToolArgs, pTool)
        scrollToBottom()
    }

    fun toolResult(summary: String, failed: Boolean) {
        emit("↳ $summary\n", if (failed) aToolErr else aToolOk, pTool)
        pad(pTool); pad(pGap)
        thinkingShown = false
        scrollToBottom()
    }

    fun info(text: String) = block { emit("$text\n", aMuted, pNormal) }
    fun error(text: String) = block { emit("$text\n", aError, pNormal) }

    fun endAssistant() {
        endStream(); ensureNewline()
    }

    fun clear() {
        doc.remove(0, doc.length)
        mode = Mode.NONE; atLineStart = true
        held.setLength(0); fenceInfo.setLength(0); codeLine.setLength(0); buf.setLength(0)
        inFence = false; inline = false; bold = false; pendingStar = false; highlighter = null
    }

    // ---- helpers ----

    private fun block(body: () -> Unit) {
        endStream(); ensureNewline(); gap()
        body()
        scrollToBottom()
    }

    private fun emit(text: String, ch: AttributeSet, p: AttributeSet) {
        if (text.isEmpty()) return
        val start = doc.length
        doc.insertString(start, text, ch)
        doc.setParagraphAttributes(start, text.length, p, true)
    }

    private fun pad(p: AttributeSet) = emit("\n", tiny, p)
    private fun gap() {
        if (doc.length > 0) pad(pGap)
    }

    private fun ensureNewline() {
        if (doc.length > 0 && doc.getText(doc.length - 1, 1) != "\n") emit("\n", aText, pNormal)
    }

    private fun isAtBottom(): Boolean {
        val sb = verticalScrollBar
        return sb.value + sb.visibleAmount >= sb.maximum - JBUI.scale(24)
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater { verticalScrollBar.value = verticalScrollBar.maximum }
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
        atLineStart = false
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
        if (mode == Mode.NONE) mode = Mode.TEXT
        if (pendingStar && c != '*') {
            buf.append('*'); pendingStar = false
        }
        when {
            c == '\n' -> {
                flushRun(); emit("\n", aText, pNormal)
                inline = false; bold = false; atLineStart = true
            }

            c == '`' -> {
                atLineStart = false; flushRun(); inline = !inline
            }

            c == '*' && !inline -> {
                atLineStart = false
                if (pendingStar) {
                    flushRun(); bold = !bold; pendingStar = false
                } else pendingStar = true
            }

            else -> {
                atLineStart = false; buf.append(c)
            }
        }
    }

    private fun flushRun() {
        if (buf.isEmpty()) return
        val attrs = when {
            inline -> aInline; bold -> aBold; else -> aText
        }
        emit(buf.toString(), attrs, pNormal)
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
            pad(pCode); pad(pGap)
            mode = Mode.TEXT
            highlighter = null
        } else {                                       // opening fence
            flushRun()
            pad(pGap)
            mode = Mode.CODE
            highlighter = CodeHighlighter(lang)
            codeLine.setLength(0)
            emit(lang.ifEmpty { "code" } + "\n", aCodeLabel, pCodeHead)
        }
    }

    private fun emitCodeLine(line: String) {
        val h = highlighter ?: CodeHighlighter("").also { highlighter = it }
        for ((text, kind) in h.line(line.replace("\t", "    "))) emit(text, aCode.getValue(kind), pCode)
        emit("\n", aCode.getValue(Kind.PLAIN), pCode)
    }

    /** Finishes whatever is being streamed (open code block, held backticks, pending text). */
    private fun endStream() {
        if (inFence) finishFenceLine()
        if (held.isNotEmpty()) release()
        if (mode == Mode.CODE) {
            if (codeLine.isNotEmpty()) {
                emitCodeLine(codeLine.toString()); codeLine.setLength(0)
            }
            pad(pCode); pad(pGap)
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
    }
}