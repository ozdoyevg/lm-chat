package dev.lmchat.core.llm

class ToolCall(val id: String, val name: String, val arguments: String)

class ChatResult(
    val text: String,
    val toolCalls: List<ToolCall>,
    val cancelled: Boolean,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null
)

class ModelInfo(val id: String, val contextLength: Int?)

/** [maxLength] is what the model supports, [loadedLength] what a local server has it loaded with (if known). */
class ContextInfo(val maxLength: Int?, val loadedLength: Int?)

interface StreamListener {
    fun onText(chunk: String)
    fun onReasoning(chunk: String)
}