package dev.lmchat.core.completion

object CompletionCleaner {
    private val thinkBlock = Regex("(?s)<think>.*?</think>")
    private val fencedBlock = Regex("(?s)^\\s*```[a-zA-Z0-9_+-]*\\n(.*?)(\\n```.*)?$")

    /** Turns raw model output into text that can be inserted at the cursor. Empty means "no suggestion". */
    fun clean(raw: String, prefix: String, maxLines: Int): String {
        var text = raw.replace(thinkBlock, "").replace("<CURSOR>", "")
        fencedBlock.find(text)?.let { text = it.groupValues[1] }
        text = text.trimEnd()

        // the model repeated the line the cursor is on
        val currentLine = prefix.substringAfterLast('\n')
        if (currentLine.isNotBlank() && text.startsWith(currentLine)) text = text.removePrefix(currentLine)

        val leadingBreak = if (text.startsWith("\n")) "\n" else ""
        val body = text.removePrefix("\n").lines().take(maxLines).joinToString("\n").trimEnd()
        return if (body.isBlank()) "" else leadingBreak + body
    }
}