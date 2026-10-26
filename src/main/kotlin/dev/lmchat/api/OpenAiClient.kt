package dev.lmchat.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

fun JsonObject.str(key: String): String? = get(key)?.takeIf { !it.isJsonNull }?.asString
private fun JsonObject.intOrNull(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

class ToolCall(val id: String, val name: String, val arguments: String)
class ChatResult(
    val text: String, val toolCalls: List<ToolCall>, val cancelled: Boolean,
    val promptTokens: Int? = null, val completionTokens: Int? = null
)

class ModelInfo(val id: String, val ctx: Int?)
class ContextInfo(val max: Int?, val loaded: Int?)

interface StreamListener {
    fun onText(chunk: String)
    fun onReasoning(chunk: String)
}

class OpenAiClient(baseUrl: String, private val apiKey: String) {

    private val root = normalize(baseUrl)
    @Volatile private var stream: InputStream? = null
    @Volatile private var cancelled = false

    private class Acc { var id = ""; var name = ""; val args = StringBuilder() }

    private fun normalize(url: String): String {
        val t = url.trim().trimEnd('/')
        val path = try { URI.create(t).path } catch (e: IllegalArgumentException) { "" }
        return if (path.isNullOrEmpty()) "$t/v1" else t
    }

    private fun builder(url: String): HttpRequest.Builder {
        val b = HttpRequest.newBuilder(URI.create(url))
        if (apiKey.isNotBlank()) b.header("Authorization", "Bearer $apiKey")
        return b
    }

    private fun request(path: String) = builder("$root$path")

    private fun post(path: String, body: JsonObject) = postUrl("$root$path", body)

    private fun postUrl(url: String, body: JsonObject): JsonObject {
        val req = builder(url)
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        check(resp.statusCode() in 200..299) { "HTTP ${resp.statusCode()}: ${resp.body().take(300)}" }
        return JsonParser.parseString(resp.body()).asJsonObject
    }

    /** Ollama native fill-in-the-middle: the server applies the model's own infill template. */
    fun ollamaFim(model: String, prefix: String, suffix: String, maxTokens: Int): String {
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
        return postUrl("${root.removeSuffix("/v1")}/api/generate", body).str("response") ?: ""
    }
    fun listModelInfos(filter: String = ""): List<ModelInfo> {
        val terms = filter.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val resp = http.send(
            request("/models").timeout(Duration.ofSeconds(15)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        check(resp.statusCode() in 200..299) { "HTTP ${resp.statusCode()}: ${resp.body().take(300)}" }
        val data = JsonParser.parseString(resp.body()).asJsonObject.getAsJsonArray("data") ?: JsonArray()
        return data
            .mapNotNull { el ->
                val o = el.asJsonObject
                o.str("id")?.let { ModelInfo(it, contextOf(o)) }
            }
            .filterNot { it.id.contains("embed", ignoreCase = true) }
            .filter { m -> terms.isEmpty() || terms.any { m.id.contains(it, ignoreCase = true) } }
            .sortedBy { it.id }
    }

    fun listModels(filter: String = ""): List<String> = listModelInfos(filter).map { it.id }

    private fun contextOf(o: JsonObject): Int? {
        for (k in listOf("context_length", "context_window", "max_context_length", "max_model_len")) {
            o.intOrNull(k)?.let { return it }
        }
        return null
    }

    /** Context window of a model on a local LM Studio or Ollama server; null if unknown or not a local host. */
    fun contextInfo(model: String): ContextInfo? {
        if (!isLocalHost()) return null
        val origin = root.removeSuffix("/v1")

        // LM Studio: max context always, loaded context only while the model is loaded
        try {
            val m = getJson("$origin/api/v0/models").getAsJsonArray("data")
                ?.map { it.asJsonObject }?.firstOrNull { it.str("id") == model }
            if (m != null) return ContextInfo(m.intOrNull("max_context_length"), m.intOrNull("loaded_context_length"))
        } catch (e: Exception) { /* not LM Studio */
        }

        // Ollama: maximum from /api/show, loaded context from /api/ps (newer versions)
        try {
            val body = JsonObject().apply { addProperty("model", model) }
            val info = postUrl("$origin/api/show", body).get("model_info")?.takeIf { it.isJsonObject }?.asJsonObject
            val max = info?.entrySet()?.firstOrNull { it.key.endsWith(".context_length") }?.value?.asInt
            var loaded: Int? = null
            try {
                loaded = getJson("$origin/api/ps").getAsJsonArray("models")
                    ?.map { it.asJsonObject }
                    ?.firstOrNull { it.str("model") == model || it.str("name") == model }
                    ?.intOrNull("context_length")
            } catch (e: Exception) { /* older Ollama */
            }
            if (max != null || loaded != null) return ContextInfo(max, loaded)
        } catch (e: Exception) { /* not Ollama */
        }
        return null
    }

    private fun getJson(url: String): JsonObject {
        val resp = http.send(
            builder(url).timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        check(resp.statusCode() in 200..299) { "HTTP ${resp.statusCode()}" }
        return JsonParser.parseString(resp.body()).asJsonObject
    }

    private fun isLocalHost(): Boolean {
        val host = try {
            URI.create(root).host
        } catch (e: Exception) {
            null
        } ?: return false
        return host == "localhost" || !host.contains('.') || host.endsWith(".local") ||
                host.endsWith(".lan") || Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(host)
    }

    fun cancel() {
        cancelled = true
        try { stream?.close() } catch (e: IOException) { /* ignore */ }
    }

    private fun openStream(
        model: String, messages: List<JsonObject>, tools: JsonArray?, temperature: Double, includeUsage: Boolean
    ): HttpResponse<InputStream> {
        val body = JsonObject()
        body.addProperty("model", model)
        body.addProperty("stream", true)
        body.addProperty("temperature", temperature)
        if (includeUsage) body.add("stream_options", JsonObject().apply { addProperty("include_usage", true) })
        val arr = JsonArray()
        messages.forEach { arr.add(it) }
        body.add("messages", arr)
        if (tools != null && tools.size() > 0) {
            body.add("tools", tools)
            body.addProperty("tool_choice", "auto")
        }
        // First request can be slow: the server may load the model before it answers.
        val req = request("/chat/completions")
            .timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        return http.send(req, HttpResponse.BodyHandlers.ofInputStream())
    }

    fun streamChat(
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray?,
        temperature: Double,
        listener: StreamListener
    ): ChatResult {
        cancelled = false
        var resp = openStream(model, messages, tools, temperature, includeUsage = true)
        if (resp.statusCode() == 400) {
            val err = resp.body().use { it.readBytes().toString(Charsets.UTF_8) }
            if (!err.contains("stream_options")) throw IOException("HTTP 400: ${err.take(500)}")
            resp = openStream(model, messages, tools, temperature, includeUsage = false)
        }
        if (resp.statusCode() !in 200..299) {
            val err = resp.body().use { it.readBytes().toString(Charsets.UTF_8) }
            throw IOException("HTTP ${resp.statusCode()}: ${err.take(500)}")
        }
        stream = resp.body()
        if (cancelled) {
            resp.body().close(); return ChatResult("", emptyList(), true)
        }

        val text = StringBuilder()
        val accs = sortedMapOf<Int, Acc>()
        var promptTokens: Int? = null
        var completionTokens: Int? = null
        try {
            resp.body().bufferedReader(Charsets.UTF_8).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val json = JsonParser.parseString(data).asJsonObject
                    json.get("error")?.takeIf { !it.isJsonNull }
                        ?.let { throw IOException("Provider error: ${it.toString().take(300)}") }
                    json.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject?.let { u ->
                        u.intOrNull("prompt_tokens")?.let { promptTokens = it }
                        u.intOrNull("completion_tokens")?.let { completionTokens = it }
                    }
                    val choice = json.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: continue
                    val delta = choice.getAsJsonObject("delta") ?: continue

                    delta.str("content")?.takeIf { it.isNotEmpty() }?.let {
                        text.append(it); listener.onText(it)
                    }
                    (delta.str("reasoning_content") ?: delta.str("reasoning"))?.let { listener.onReasoning(it) }

                    delta.getAsJsonArray("tool_calls")?.forEach { el ->
                        val tc = el.asJsonObject
                        val idx = tc.get("index")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                        val acc = accs.getOrPut(idx) { Acc() }
                        tc.str("id")?.let { if (it.isNotEmpty()) acc.id = it }
                        tc.getAsJsonObject("function")?.let { f ->
                            f.str("name")?.let { if (acc.name.isEmpty()) acc.name = it }
                            f.str("arguments")?.let { acc.args.append(it) }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (!cancelled) throw e
        } finally {
            stream = null
        }

        val calls = accs.entries.filter { it.value.name.isNotEmpty() }.map { (i, a) ->
            ToolCall(a.id.ifEmpty { "call_$i" }, a.name, a.args.toString().ifBlank { "{}" })
        }
        return ChatResult(text.toString(), calls, cancelled, promptTokens, completionTokens)
    }
    
    companion object {
        // HTTP/1.1 on purpose: the JDK client's h2c upgrade attempt on plain http:// confuses some servers.
        private val http: HttpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    }

    /** Non-streaming chat completion; returns the assistant text. */
    fun chatOnce(model: String, messages: List<JsonObject>, temperature: Double, maxTokens: Int): String {
        val body = JsonObject()
        body.addProperty("model", model)
        body.addProperty("stream", false)
        body.addProperty("temperature", temperature)
        body.addProperty("max_tokens", maxTokens)
        val arr = JsonArray()
        messages.forEach { arr.add(it) }
        body.add("messages", arr)
        return post("/chat/completions", body).getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
            ?.getAsJsonObject("message")?.str("content") ?: ""
    }

    /** Raw text completion, used for fill-in-the-middle. */
    fun completeOnce(model: String, prompt: String, temperature: Double, maxTokens: Int, stop: List<String>): String {
        val body = JsonObject()
        body.addProperty("model", model)
        body.addProperty("prompt", prompt)
        body.addProperty("stream", false)
        body.addProperty("temperature", temperature)
        body.addProperty("max_tokens", maxTokens)
        if (stop.isNotEmpty()) {
            val s = JsonArray()
            stop.forEach { s.add(it) }
            body.add("stop", s)
        }
        return post("/completions", body).getAsJsonArray("choices")?.firstOrNull()?.asJsonObject?.str("text") ?: ""
    }
}