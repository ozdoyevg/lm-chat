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

class ToolCall(val id: String, val name: String, val arguments: String)
class ChatResult(val text: String, val toolCalls: List<ToolCall>, val cancelled: Boolean)

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

    private fun request(path: String): HttpRequest.Builder {
        val b = HttpRequest.newBuilder(URI.create("$root$path"))
        if (apiKey.isNotBlank()) b.header("Authorization", "Bearer $apiKey")
        return b
    }

    fun listModels(filter: String = ""): List<String> {
        val terms = filter.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val resp = http.send(
            request("/models").timeout(Duration.ofSeconds(15)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        check(resp.statusCode() in 200..299) { "HTTP ${resp.statusCode()}: ${resp.body().take(300)}" }
        return JsonParser.parseString(resp.body()).asJsonObject.getAsJsonArray("data")
            .mapNotNull { it.asJsonObject.str("id") }
            .filterNot { it.contains("embed", ignoreCase = true) }
            .filter { id -> terms.isEmpty() || terms.any { id.contains(it, ignoreCase = true) } }
            .sorted()
    }

    fun cancel() {
        cancelled = true
        try { stream?.close() } catch (e: IOException) { /* ignore */ }
    }

    fun streamChat(
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray?,
        temperature: Double,
        listener: StreamListener
    ): ChatResult {
        cancelled = false
        val body = JsonObject()
        body.addProperty("model", model)
        body.addProperty("stream", true)
        body.addProperty("temperature", temperature)
        val arr = JsonArray()
        messages.forEach { arr.add(it) }
        body.add("messages", arr)
        if (tools != null && tools.size() > 0) {
            body.add("tools", tools)
            body.addProperty("tool_choice", "auto")
        }

        // First request can be slow: LM Studio may load the model before it answers.
        val req = request("/chat/completions")
            .timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
        if (resp.statusCode() !in 200..299) {
            val err = resp.body().use { it.readBytes().toString(Charsets.UTF_8) }
            throw IOException("HTTP ${resp.statusCode()}: ${err.take(500)}")
        }
        stream = resp.body()
        if (cancelled) { resp.body().close(); return ChatResult("", emptyList(), true) }

        val text = StringBuilder()
        val accs = sortedMapOf<Int, Acc>()
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
        return ChatResult(text.toString(), calls, cancelled)
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

    private fun post(path: String, body: JsonObject): JsonObject {
        val req = request(path)
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        check(resp.statusCode() in 200..299) { "HTTP ${resp.statusCode()}: ${resp.body().take(300)}" }
        return JsonParser.parseString(resp.body()).asJsonObject
    }
}