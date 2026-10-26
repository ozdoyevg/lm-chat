package dev.lmchat.core.completion

enum class CompletionMode(val id: String, val label: String) {
    CHAT("chat", "Chat (works with any model)"),
    FIM("fim", "FIM (/v1/completions, pick a template)"),
    OLLAMA("ollama", "Ollama FIM (/api/generate, model's own template)");

    companion object {
        fun fromId(id: String): CompletionMode = values().firstOrNull { it.id == id } ?: CHAT
    }
}