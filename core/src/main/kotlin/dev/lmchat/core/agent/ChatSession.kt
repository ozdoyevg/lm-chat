package dev.lmchat.core.agent

import com.google.gson.JsonObject
import dev.lmchat.core.llm.ChatMessages
import dev.lmchat.core.llm.OpenAiCompatibleClient
import dev.lmchat.core.llm.StreamListener
import dev.lmchat.core.tools.ToolHost
import dev.lmchat.core.tools.ToolResults
import java.util.concurrent.CopyOnWriteArrayList

/** One conversation: keeps the message history and runs the model/tool loop. No UI, no IDE. */
class ChatSession(private val toolHost: ToolHost) {
    private val history = CopyOnWriteArrayList<JsonObject>()

    @Volatile
    private var stopRequested = false
    @Volatile
    private var activeClient: OpenAiCompatibleClient? = null

    val isEmpty: Boolean get() = history.isEmpty()
    val stopWasRequested: Boolean get() = stopRequested

    fun clear() = history.clear()

    /** Records the user's message and re-arms the stop flag. Call before [runTurn]. */
    fun beginTurn(userText: String) {
        stopRequested = false
        history.add(ChatMessages.user(userText))
    }

    fun requestStop() {
        stopRequested = true
        activeClient?.cancel()
    }

    /** Streams the answer and runs tool calls until the model stops asking for tools. Blocks; call off the UI thread. */
    fun runTurn(client: OpenAiCompatibleClient, config: AgentConfig, listener: AgentListener): TurnResult {
        activeClient = client
        var reportedTokens: Int? = null
        val streamListener = object : StreamListener {
            override fun onText(chunk: String) = listener.onAssistantText(chunk)
            override fun onReasoning(chunk: String) = listener.onThinking()
        }
        try {
            for (round in 1..config.maxRounds) {
                if (stopRequested) {
                    listener.onNotice("Stopped."); return TurnResult(false, reportedTokens)
                }

                val tools = if (config.useTools) toolHost.specs else null
                val result =
                    client.streamChat(config.model, messagesFor(config), tools, config.temperature, streamListener)
                result.promptTokens?.let { reportedTokens = it + (result.completionTokens ?: 0) }

                if (result.cancelled) {
                    if (result.text.isNotEmpty()) history.add(ChatMessages.assistant(result.text))
                    listener.onNotice("Stopped.")
                    return TurnResult(false, reportedTokens)
                }

                history.add(ChatMessages.assistant(result.text, result.toolCalls))
                if (result.toolCalls.isEmpty()) return TurnResult(true, reportedTokens)

                for (call in result.toolCalls) {
                    listener.onToolCall(call.name, call.arguments)
                    val output = toolHost.execute(call.name, call.arguments)
                    history.add(ChatMessages.tool(call.id, output))
                    listener.onToolResult(ToolResults.summarize(output), ToolResults.isFailure(output))
                }
            }
            listener.onNotice("Stopped: too many tool rounds.")
            return TurnResult(false, reportedTokens)
        } finally {
            activeClient = null
        }
    }

    /** Rough size of the conversation, for when the server does not report token usage. */
    fun estimateTokens(systemPrompt: String): Int {
        var characters = systemPrompt.length
        for (message in history) {
            message.get("content")?.takeIf { !it.isJsonNull }?.let { characters += it.asString.length }
        }
        return ContextUsage.estimateTokens(characters)
    }

    /** Asks the model for a short title based on the first user message. Returns null on any failure. */
    fun suggestTitle(client: OpenAiCompatibleClient, model: String): String? {
        val first = history.firstOrNull { it.get("role")?.asString == "user" }
            ?.get("content")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val raw = try {
            client.chatOnce(
                model,
                listOf(ChatMessages.system(SessionTitles.PROMPT), ChatMessages.user(first.take(600))),
                0.2, 24
            )
        } catch (e: Exception) {
            return null
        }
        return SessionTitles.clean(raw).ifEmpty { null }
    }

    private fun messagesFor(config: AgentConfig): List<JsonObject> {
        val messages = ArrayList<JsonObject>()
        if (config.systemPrompt.isNotBlank()) messages.add(ChatMessages.system(config.systemPrompt))
        messages.addAll(history)
        return messages
    }
}