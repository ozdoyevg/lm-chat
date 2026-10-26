package dev.lmchat.core.llm

import com.google.gson.JsonObject
import java.net.URI

/** Native (non-OpenAI) endpoints of LM Studio and Ollama. [originUrl] is the server root without /v1. */
class LocalServerApi(private val originUrl: String, private val http: JsonHttp) {

    /** Only local-looking hosts are probed, so API keys are never sent to unrelated cloud endpoints. */
    fun isLocalHost(): Boolean {
        val host = try {
            URI.create(originUrl).host
        } catch (e: Exception) {
            null
        } ?: return false
        return host == "localhost" || !host.contains('.') || host.endsWith(".local") ||
                host.endsWith(".lan") || Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(host)
    }

    /** Context window of [model]: LM Studio reports max and loaded length, Ollama max (and loaded on newer versions). */
    fun contextInfo(model: String): ContextInfo? {
        if (!isLocalHost()) return null

        try {
            val entry = http.get("$originUrl/api/v0/models", 5).getAsJsonArray("data")
                ?.map { it.asJsonObject }?.firstOrNull { it.stringOrNull("id") == model }
            if (entry != null) {
                return ContextInfo(entry.intOrNull("max_context_length"), entry.intOrNull("loaded_context_length"))
            }
        } catch (e: Exception) { /* not LM Studio */
        }

        try {
            val request = JsonObject().apply { addProperty("model", model) }
            val modelInfo = http.post("$originUrl/api/show", request, 5).get("model_info")
                ?.takeIf { it.isJsonObject }?.asJsonObject
            val max = modelInfo?.entrySet()?.firstOrNull { it.key.endsWith(".context_length") }?.value?.asInt
            var loaded: Int? = null
            try {
                loaded = http.get("$originUrl/api/ps", 5).getAsJsonArray("models")
                    ?.map { it.asJsonObject }
                    ?.firstOrNull { it.stringOrNull("model") == model || it.stringOrNull("name") == model }
                    ?.intOrNull("context_length")
            } catch (e: Exception) { /* older Ollama */
            }
            if (max != null || loaded != null) return ContextInfo(max, loaded)
        } catch (e: Exception) { /* not Ollama */
        }
        return null
    }

    /** Ollama fill-in-the-middle: the server applies the model's own infill template. */
    fun ollamaInfill(model: String, prefix: String, suffix: String, maxTokens: Int): String {
        val options = JsonObject()
        options.addProperty("temperature", 0.0)
        options.addProperty("num_predict", maxTokens)
        val body = JsonObject()
        body.addProperty("model", model)
        body.addProperty("prompt", prefix)
        body.addProperty("suffix", suffix)
        body.addProperty("stream", false)
        body.addProperty("keep_alive", "30m")
        body.add("options", options)
        return http.post("$originUrl/api/generate", body).stringOrNull("response") ?: ""
    }
}