package dev.lmchat.core.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.IOException

/** Reads a server-sent-events chat completion stream and accumulates text, tool calls and usage. */
class ChatStreamReader(private val listener: StreamListener) {

    private class ToolCallBuilder {
        var id = ""
        var name = ""
        val arguments = StringBuilder()
    }

    private val text = StringBuilder()
    private val toolCalls = sortedMapOf<Int, ToolCallBuilder>()
    private var promptTokens: Int? = null
    private var completionTokens: Int? = null

    /** Reads until the end-of-stream marker or the end of input. Throws IOException if the stream breaks. */
    fun readAll(reader: BufferedReader) {
        while (true) {
            val line = reader.readLine() ?: return
            if (!handleLine(line)) return
        }
    }

    /** What was received so far; safe to call after a cancelled or broken stream. */
    fun result(cancelled: Boolean): ChatResult {
        val calls = toolCalls.entries.filter { it.value.name.isNotEmpty() }.map { (index, builder) ->
            ToolCall(
                builder.id.ifEmpty { "call_$index" },
                builder.name,
                builder.arguments.toString().ifBlank { "{}" }
            )
        }
        return ChatResult(text.toString(), calls, cancelled, promptTokens, completionTokens)
    }

    /** Returns false when the end-of-stream marker was reached. */
    private fun handleLine(line: String): Boolean {
        if (!line.startsWith("data:")) return true
        val payload = line.substring(5).trim()
        if (payload.isEmpty()) return true
        if (payload == "[DONE]") return false

        val json = JsonParser.parseString(payload).asJsonObject
        json.get("error")?.takeIf { !it.isJsonNull }
            ?.let { throw IOException("Provider error: ${it.toString().take(300)}") }
        readUsage(json)

        val choice = json.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.asJsonObject
            ?: return true
        val delta = choice.get("delta")?.takeIf { it.isJsonObject }?.asJsonObject ?: return true
        readContent(delta)
        readToolCalls(delta)
        return true
    }

    private fun readUsage(json: JsonObject) {
        val usage = json.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        usage.intOrNull("prompt_tokens")?.let { promptTokens = it }
        usage.intOrNull("completion_tokens")?.let { completionTokens = it }
    }

    private fun readContent(delta: JsonObject) {
        delta.stringOrNull("content")?.takeIf { it.isNotEmpty() }?.let {
            text.append(it)
            listener.onText(it)
        }
        (delta.stringOrNull("reasoning_content") ?: delta.stringOrNull("reasoning"))?.let { listener.onReasoning(it) }
    }

    private fun readToolCalls(delta: JsonObject) {
        val calls = delta.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        for (element in calls) {
            val call = element.asJsonObject
            val builder = toolCalls.getOrPut(call.intOrNull("index") ?: 0) { ToolCallBuilder() }
            call.stringOrNull("id")?.takeIf { it.isNotEmpty() }?.let { builder.id = it }
            val function = call.get("function")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            function.stringOrNull("name")?.let { if (builder.name.isEmpty()) builder.name = it }
            function.stringOrNull("arguments")?.let { builder.arguments.append(it) }
        }
    }
}