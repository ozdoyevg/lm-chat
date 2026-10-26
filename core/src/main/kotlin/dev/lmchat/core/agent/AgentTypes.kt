package dev.lmchat.core.agent

/** Events of a running turn. They arrive on the worker thread; UI code must hop to its own thread. */
interface AgentListener {
    fun onAssistantText(chunk: String)
    fun onThinking()
    fun onToolCall(name: String, argumentsJson: String)
    fun onToolResult(summary: String, failed: Boolean)
    fun onNotice(message: String)
}

class AgentConfig(
    val model: String,
    val systemPrompt: String,
    val temperature: Double,
    val useTools: Boolean,
    val maxRounds: Int = 10
)

/** [completed] is false when the turn was stopped or hit the round limit. */
class TurnResult(val completed: Boolean, val reportedTokens: Int?)