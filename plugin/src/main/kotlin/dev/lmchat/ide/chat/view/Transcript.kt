package dev.lmchat.ide.chat.view

import javax.swing.JTextPane
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.StyledDocument

/** A position in the document that follows later insertions and removals. */
internal class Marker(var offset: Int)

/**
 * Low-level editing of the chat document.
 *
 * While a bubble or card is open, the document always ends with
 *     ... content ... | placeholder line "\n" | bottom padding "\n"
 * and new text is inserted just before the placeholder (see [end]). That keeps the bubble closed,
 * padded and rounded at the bottom while it is still growing.
 */
internal class Transcript(private val pane: JTextPane, var styles: ChatStyles) {
    val doc: StyledDocument get() = pane.styledDocument

    private val markers = mutableListOf<Marker>()
    private var reservedTail = 0

    /** Where new content goes. */
    fun end(): Int = doc.length - reservedTail

    fun mark(offset: Int = end()): Marker = Marker(offset).also { markers.add(it) }
    fun unmark(marker: Marker) {
        markers.remove(marker)
    }

    /** Moves every marker at or after [from] by [delta], except [except]. */
    fun shiftMarkers(from: Int, delta: Int, except: Marker? = null) {
        for (marker in markers) if (marker !== except && marker.offset >= from) marker.offset += delta
    }

    fun write(text: String, characters: AttributeSet, paragraph: AttributeSet, at: Int = end()): Int {
        if (text.isEmpty()) return at
        doc.insertString(at, text, characters)
        doc.setParagraphAttributes(at, text.length, paragraph, true)
        if (at + text.length >= doc.length - reservedTail) resetTail()
        return at + text.length
    }

    fun addPadding(paragraph: AttributeSet) {
        write("\n", styles.tinyBreak, paragraph)
    }

    /** Space between two blocks. Nothing is added at the very top. */
    fun addSeparator() {
        if (end() > 0) write("\n", styles.spacerBreak, styles.noBackground)
    }

    fun remove(offset: Int, length: Int) {
        doc.remove(offset, length)
        shiftMarkers(from = offset + length, delta = -length)
    }

    fun replace(offset: Int, length: Int, text: String, characters: AttributeSet, paragraph: AttributeSet) {
        (doc as AbstractDocument).replace(offset, length, text, characters)
        doc.setParagraphAttributes(offset, text.length, paragraph, true)
        if (text.length != length) shiftMarkers(from = offset + length, delta = text.length - length)
    }

    // ---- open blocks ----

    /** Starts a bubble or card: top padding, a placeholder line and the bottom padding. */
    fun openBlock(top: AttributeSet, middle: AttributeSet, bottom: AttributeSet) {
        addPadding(top)
        write("\n", styles.tinyBreak, middle)       // the line being written
        write("\n", styles.tinyBreak, bottom)       // stays at the end while the block is open
        reservedTail = 2
    }

    /** Ends a block. An unused placeholder line is removed so no blank row is left. */
    fun closeBlock(bottom: AttributeSet) {
        if (placeholderIsEmpty()) {
            doc.remove(doc.length - 2, 1)
            doc.setParagraphAttributes(doc.length - 1, 1, bottom, true)
        }
        reservedTail = 0
    }

    /** Deletes everything from [from] to the end (an open block that never got content). */
    fun discardBlock(from: Int) {
        doc.remove(from, doc.length - from)
        reservedTail = 0
        resetTail()
    }

    /** After a multi-part line the placeholder inherits that line's style; make it neutral again. */
    fun resetPlaceholder(middle: AttributeSet) {
        if (reservedTail == 2) doc.setParagraphAttributes(end(), 1, middle, true)
    }

    private fun placeholderIsEmpty(): Boolean = doc.length >= 3 && doc.getText(doc.length - 3, 1) == "\n"

    /**
     * The document always ends with an invisible empty paragraph. Text inserted into it leaves a new empty
     * paragraph behind that inherits the style, which showed up as a blank row. Keep it plain.
     */
    private fun resetTail() {
        doc.setParagraphAttributes(doc.length, 1, styles.noBackground, true)
    }

    fun clear() {
        doc.remove(0, doc.length)
        reservedTail = 0
        markers.clear()
        resetTail()
    }
}