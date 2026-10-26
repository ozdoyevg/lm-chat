package dev.lmchat.core.completion

import dev.lmchat.core.llm.OpenAiCompatibleClient

class CompletionContext(val fileName: String, val prefix: String, val suffix: String)

class CompletionSettings(
    val mode: CompletionMode,
    val template: FimTemplate,
    val model: String,
    val maxLines: Int,
    val maxTokens: Int
)

/** Turns a cursor position (text before and after it) into a suggestion. Knows nothing about editors. */
class CompletionEngine(private val client: OpenAiCompatibleClient) {

    fun suggest(context: CompletionContext, settings: CompletionSettings): String {
        val raw = when (settings.mode) {
            CompletionMode.OLLAMA ->
                client.ollamaInfill(settings.model, context.prefix, context.suffix, settings.maxTokens)

            CompletionMode.FIM -> client.completeOnce(
                settings.model, settings.template.buildPrompt(context.prefix, context.suffix),
                0.0, settings.maxTokens, settings.template.stopSequences
            )

            CompletionMode.CHAT -> client.chatOnce(
                settings.model,
                CompletionPrompts.chatMessages(context.fileName, context.prefix, context.suffix, settings.maxLines),
                0.0, settings.maxTokens
            )
        }
        return CompletionCleaner.clean(raw, context.prefix, settings.maxLines)
    }
}