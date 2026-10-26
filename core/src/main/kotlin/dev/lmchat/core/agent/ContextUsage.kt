package dev.lmchat.core.agent

import java.util.Locale

object ContextUsage {
    /** About four characters per token. Good enough for an indicator. */
    fun estimateTokens(characters: Int): Int = characters / 4

    /** 950 -> "950", 3200 -> "3.2k", 8000 -> "8k". */
    fun format(tokens: Int): String =
        if (tokens < 1000) "$tokens"
        else String.format(Locale.US, "%.1fk", tokens / 1000.0).replace(".0k", "k")
}