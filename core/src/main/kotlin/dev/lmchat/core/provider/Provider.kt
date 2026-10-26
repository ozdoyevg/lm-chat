package dev.lmchat.core.provider

/** One OpenAI-compatible endpoint. Plain bean (no-arg constructor, mutable properties) so it can be persisted as is. */
class Provider(
    var id: String = "",
    var name: String = "",
    var baseUrl: String = "",
    /** Comma-separated; a model is listed if its id contains ANY term. Empty = show all. */
    var modelFilter: String = ""
) {
    fun copy() = Provider(id, name, baseUrl, modelFilter)
}

class ProviderPreset(val name: String, val baseUrl: String, val modelFilter: String)

object ProviderPresets {
    val all = listOf(
        ProviderPreset("LM Studio", "http://localhost:1234/v1", ""),
        ProviderPreset("Ollama (local)", "http://localhost:11434/v1", ""),
        ProviderPreset("OpenRouter (free models)", "https://openrouter.ai/api/v1", ":free"),
        ProviderPreset("OpenCode Zen (free models)", "https://opencode.ai/zen/v1", "free,big-pickle"),
        ProviderPreset("Custom (OpenAI-compatible)", "https://", "")
    )
}

/** Where API keys live. The IDE plugin backs this with Password Safe; other hosts can use env vars or a file. */
fun interface CredentialStore {
    fun apiKey(providerId: String): String
}