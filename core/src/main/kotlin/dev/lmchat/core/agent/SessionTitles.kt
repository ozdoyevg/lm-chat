package dev.lmchat.core.agent

object SessionTitles {
    const val PROMPT =
        "Write a short title (max 5 words) for a chat that starts with the user's message below. " +
                "Reply with the title only: no quotes, no punctuation at the end."

    /** Immediate placeholder title: the start of the first message. */
    fun fromFirstMessage(text: String, maxLength: Int = 40): String {
        val line = text.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .replace(Regex("\\s+"), " ")
        return when {
            line.isEmpty() -> "New chat"
            line.length > maxLength -> line.take(maxLength).trimEnd() + "…"
            else -> line
        }
    }

    /** Cleans a model-written title (drops reasoning blocks, quotes, markdown). */
    fun clean(raw: String): String =
        raw.replace(Regex("(?s)<think>.*?</think>"), "")
            .lines().firstOrNull { it.isNotBlank() }.orEmpty()
            .trim().trim('"', '\'', '`', '*', '#', '.', ' ')
            .take(60)
}