package dev.lmchat.core.markdown

enum class InlineStyle { PLAIN, BOLD, CODE }

/** Receives the structure found in streamed markdown. Rendering is up to the implementation. */
interface MarkdownListener {
    /** A piece of text inside the current line. May arrive in several parts. */
    fun onText(text: String, style: InlineStyle)

    /** The current text line is finished. */
    fun onLineEnd()

    /** Vertical space between blocks. Only sent when more content follows, never at the start or end. */
    fun onSpacing()
    fun onCodeBlockStart(language: String)
    fun onCodeLine(line: String)
    fun onCodeBlockEnd()
}

/**
 * Incremental parser for the markdown subset chat answers use: code fences, `inline code`, **bold**
 * and blank lines. Feed it chunks as they stream in; call [finish] when the message is complete.
 */
class MarkdownStreamParser(private val listener: MarkdownListener) {

    private enum class Mode { NONE, TEXT, CODE }

    private var mode = Mode.NONE
    private var atLineStart = true
    private val heldBackticks = StringBuilder()    // backticks at line start: maybe a code fence
    private var readingFenceInfo = false           // after ```, reading the language
    private val fenceInfoText = StringBuilder()
    private val currentCodeLine = StringBuilder()
    private val pendingText = StringBuilder()
    private var insideInlineCode = false
    private var insideBold = false
    private var pendingStar = false
    private var blankLinePending = false
    private var spacingAfterCodePending = false
    private var hasContent = false

    fun feed(chunk: String) {
        for (char in chunk) feedChar(char)
        flushText()
    }

    /** Ends the current text line if one is in progress (used before inserting something between lines). */
    fun endLine() {
        if (mode != Mode.TEXT || atLineStart) return
        flushText()
        listener.onLineEnd()
        atLineStart = true
        insideInlineCode = false
        insideBold = false
    }

    /** Flushes pending text, closes an open code block and resets for the next message. */
    fun finish() {
        if (readingFenceInfo) finishFenceLine()
        if (heldBackticks.isNotEmpty()) releaseHeldBackticks()
        if (mode == Mode.CODE) {
            closeCodeBlock()
        } else {
            if (pendingStar) {
                pendingText.append('*'); pendingStar = false
            }
            flushText()
            if (mode == Mode.TEXT && !atLineStart) listener.onLineEnd()
        }
        reset()
    }

    fun reset() {
        mode = Mode.NONE
        atLineStart = true
        heldBackticks.setLength(0)
        readingFenceInfo = false
        fenceInfoText.setLength(0)
        currentCodeLine.setLength(0)
        pendingText.setLength(0)
        insideInlineCode = false
        insideBold = false
        pendingStar = false
        blankLinePending = false
        spacingAfterCodePending = false
        hasContent = false
    }

    private fun feedChar(char: Char) {
        if (readingFenceInfo) {
            if (char == '\n') finishFenceLine() else fenceInfoText.append(char)
            return
        }
        if (atLineStart && (heldBackticks.isNotEmpty() || char == '`')) {
            if (char == '`') {
                heldBackticks.append(char)
                if (heldBackticks.length == 3) {
                    heldBackticks.setLength(0)
                    readingFenceInfo = true
                    fenceInfoText.setLength(0)
                }
                return
            }
            releaseHeldBackticks()
        }
        processChar(char)
    }

    private fun releaseHeldBackticks() {
        val held = heldBackticks.toString()
        heldBackticks.setLength(0)
        held.forEach { processChar(it) }
    }

    private fun processChar(char: Char) {
        if (mode == Mode.CODE) {
            if (char == '\n') {
                listener.onCodeLine(currentCodeLine.toString())
                currentCodeLine.setLength(0)
                atLineStart = true
            } else {
                currentCodeLine.append(char)
                atLineStart = false
            }
            return
        }

        if (pendingStar && char != '*') {
            pendingText.append('*'); pendingStar = false
        }

        if (char == '\n') {
            flushText()
            if (atLineStart) {
                if (hasContent) blankLinePending = true       // only drawn if more content follows
            } else {
                listener.onLineEnd()
                insideInlineCode = false
                insideBold = false
            }
            atLineStart = true
            return
        }

        if (mode == Mode.NONE) mode = Mode.TEXT
        if (atLineStart) startTextLine()
        atLineStart = false
        when {
            char == '`' -> {
                flushText(); insideInlineCode = !insideInlineCode
            }

            char == '*' && !insideInlineCode -> {
                if (pendingStar) {
                    flushText(); insideBold = !insideBold; pendingStar = false
                } else pendingStar = true
            }

            else -> pendingText.append(char)
        }
    }

    private fun startTextLine() {
        if (spacingAfterCodePending || blankLinePending) listener.onSpacing()
        spacingAfterCodePending = false
        blankLinePending = false
        hasContent = true
    }

    private fun flushText() {
        if (pendingText.isEmpty()) return
        val style = when {
            insideInlineCode -> InlineStyle.CODE
            insideBold -> InlineStyle.BOLD
            else -> InlineStyle.PLAIN
        }
        listener.onText(pendingText.toString(), style)
        pendingText.setLength(0)
    }

    private fun finishFenceLine() {
        readingFenceInfo = false
        atLineStart = true
        val language = fenceInfoText.toString().trim().lowercase().substringBefore(' ')
        fenceInfoText.setLength(0)
        if (mode == Mode.CODE) {
            closeCodeBlock()
            mode = Mode.TEXT
        } else {
            flushText()
            if (hasContent) listener.onSpacing()
            hasContent = true
            spacingAfterCodePending = false
            blankLinePending = false
            mode = Mode.CODE
            currentCodeLine.setLength(0)
            listener.onCodeBlockStart(language)
        }
    }

    private fun closeCodeBlock() {
        if (currentCodeLine.isNotEmpty()) {
            listener.onCodeLine(currentCodeLine.toString())
            currentCodeLine.setLength(0)
        }
        listener.onCodeBlockEnd()
        spacingAfterCodePending = true
    }
}