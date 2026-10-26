package dev.lmchat.core.llm

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpResponse

/**
 * Client for OpenAI-compatible servers (LM Studio, Ollama's /v1, OpenRouter, ...).
 * [baseUrl] may be given with or without the /v1 suffix.
 */
class OpenAiCompatibleClient(baseUrl: String, apiKey: String = "") {
    private val rootUrl = normalizeBaseUrl(baseUrl)
    private val http = JsonHttp(apiKey)
    private val localServer = LocalServerApi(rootUrl.removeSuffix("/v1"), http)

    @Volatile
    private var activeStream: InputStream? = null
    @Volatile
    private var cancelled = false

    // ---- models ----

    /** Lists models, hiding embedding models and applying a comma-separated "contains any" [filter]. */
    fun listModelInfos(filter: String = ""): List<ModelInfo> {
        val terms = filter.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val data = http.get("$rootUrl/models").getAsJsonArray("data") ?: JsonArray()
        return data
            .mapNotNull { element ->
                val entry = element.asJsonObject
                entry.stringOrNull("id")?.let { ModelInfo(it, contextLengthOf(entry)) }
            }
            .filterNot { it.id.contains("embed", ignoreCase = true) }
            .filter { model -> terms.isEmpty() || terms.any { model.id.contains(it, ignoreCase = true) } }
            .sortedBy { it.id }
    }

    fun listModels(filter: String = ""): List<String> = listModelInfos(filter).map { it.id }

    private fun contextLengthOf(entry: JsonObject): Int? {
        for (key in listOf("context_length", "context_window", "max_context_length", "max_model_len")) {
            entry.intOrNull(key)?.let { return it }
        }
        return null
    }

    // ---- one-shot requests ----

    fun chatOnce(model: String, messages: List<JsonObject>, temperature: Double, maxTokens: Int): String {
        val body = JsonObject()
        body.addProperty("model", model)
        body.addProperty("stream", false)
        body.addProperty("temperature", temperature)
        body.addProperty("max_tokens", maxTokens)
        body.add("messages", messageArray(messages))
        val choice = http.post("$rootUrl/chat/completions", body).getAsJsonArray("choices")?.firstOrNull()
        return choice?.asJsonObject?.getAsJsonObject("message")?.stringOrNull("content") ?: ""
    }

    /** Raw text completion, used for fill-in-the-middle with an explicit prompt template. */
    fun completeOnce(model: String, prompt: String, temperature: Double, maxTokens: Int, stop: List<String>): String {
        val body = JsonObject()
        body.addProperty("model", model)
        body.addProperty("prompt", prompt)
        body.addProperty("stream", false)
        body.addProperty("temperature", temperature)
        body.addProperty("max_tokens", maxTokens)
        if (stop.isNotEmpty()) {
            val stops = JsonArray()
            stop.forEach { stops.add(it) }
            body.add("stop", stops)
        }
        val choice = http.post("$rootUrl/completions", body).getAsJsonArray("choices")?.firstOrNull()
        return choice?.asJsonObject?.stringOrNull("text") ?: ""
    }

    // ---- streaming chat ----

    /** Streams a chat completion. [cancel] from another thread ends it and returns what was received so far. */
    fun streamChat(
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray?,
        temperature: Double,
        listener: StreamListener
    ): ChatResult {
        cancelled = false
        var response = openChatStream(model, messages, tools, temperature, includeUsage = true)
        if (response.statusCode() == 400) {
            val error = readError(response)
            if (!error.contains("stream_options")) throw IOException("HTTP 400: ${error.take(500)}")
            response = openChatStream(model, messages, tools, temperature, includeUsage = false)
        }
        if (response.statusCode() !in 200..299) {
            throw IOException("HTTP ${response.statusCode()}: ${readError(response).take(500)}")
        }

        val body = response.body()
        activeStream = body
        if (cancelled) {
            body.close(); return ChatResult("", emptyList(), true)
        }

        val reader = ChatStreamReader(listener)
        try {
            body.bufferedReader(Charsets.UTF_8).use { reader.readAll(it) }
        } catch (e: IOException) {
            if (!cancelled) throw e
        } finally {
            activeStream = null
        }
        return reader.result(cancelled)
    }

    fun cancel() {
        cancelled = true
        try {
            activeStream?.close()
        } catch (e: IOException) { /* already closed */
        }
    }

    private fun openChatStream(
        model: String, messages: List<JsonObject>, tools: JsonArray?, temperature: Double, includeUsage: Boolean
    ): HttpResponse<InputStream> {
        val body = JsonObject()
        body.addProperty("model", model)
        body.addProperty("stream", true)
        body.addProperty("temperature", temperature)
        if (includeUsage) body.add("stream_options", JsonObject().apply { addProperty("include_usage", true) })
        body.add("messages", messageArray(messages))
        if (tools != null && tools.size() > 0) {
            body.add("tools", tools)
            body.addProperty("tool_choice", "auto")
        }
        return http.postStream("$rootUrl/chat/completions", body)
    }

    private fun readError(response: HttpResponse<InputStream>): String =
        response.body().use { it.readBytes().toString(Charsets.UTF_8) }

    private fun messageArray(messages: List<JsonObject>) =
        JsonArray().also { array -> messages.forEach { array.add(it) } }

    // ---- local servers (LM Studio, Ollama) ----

    fun contextInfo(model: String): ContextInfo? = localServer.contextInfo(model)

    fun ollamaInfill(model: String, prefix: String, suffix: String, maxTokens: Int): String =
        localServer.ollamaInfill(model, prefix, suffix, maxTokens)
}

/** Adds /v1 when the URL has no path, so "http://host:1234" and "http://host:1234/v1" both work. */
private fun normalizeBaseUrl(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    val path = try {
        URI.create(trimmed).path
    } catch (e: IllegalArgumentException) {
        ""
    }
    return if (path.isNullOrEmpty()) "$trimmed/v1" else trimmed
}