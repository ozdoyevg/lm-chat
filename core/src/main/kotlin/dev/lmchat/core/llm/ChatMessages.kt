package dev.lmchat.core.llm

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Builders for OpenAI-style chat messages. */
object ChatMessages {
    fun system(text: String) = message("system", text)
    fun user(text: String) = message("user", text)

    fun assistant(text: String, toolCalls: List<ToolCall> = emptyList()): JsonObject {
        val message = message("assistant", text)
        if (toolCalls.isNotEmpty()) {
            val array = JsonArray()
            for (call in toolCalls) {
                val function = JsonObject().apply {
                    addProperty("name", call.name)
                    addProperty("arguments", call.arguments)
                }
                array.add(JsonObject().apply {
                    addProperty("id", call.id)
                    addProperty("type", "function")
                    add("function", function)
                })
            }
            message.add("tool_calls", array)
        }
        return message
    }

    fun tool(callId: String, content: String) =
        message("tool", content).apply { addProperty("tool_call_id", callId) }

    private fun message(role: String, content: String) =
        JsonObject().apply { addProperty("role", role); addProperty("content", content) }
}