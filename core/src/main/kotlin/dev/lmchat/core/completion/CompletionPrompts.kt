package dev.lmchat.core.completion

import com.google.gson.JsonObject
import dev.lmchat.core.llm.ChatMessages

object CompletionPrompts {
    /** Instructions for a general chat model that has to act as a code completion engine. */
    fun chatMessages(fileName: String, prefix: String, suffix: String, maxLines: Int): List<JsonObject> {
        val system = "You are a code completion engine embedded in an IDE. The user message contains a source file " +
                "with a <CURSOR> marker. Reply with ONLY the code to insert at <CURSOR>: at most $maxLines line(s), " +
                "no explanations, no markdown fences, and do not repeat code that is already before or after the " +
                "cursor. Match the file's indentation and style."
        return listOf(ChatMessages.system(system), ChatMessages.user("File: $fileName\n\n$prefix<CURSOR>$suffix"))
    }
}