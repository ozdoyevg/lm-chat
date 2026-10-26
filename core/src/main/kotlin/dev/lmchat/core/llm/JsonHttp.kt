package dev.lmchat.core.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Minimal JSON-over-HTTP helper with optional bearer authentication. */
class JsonHttp(private val apiKey: String) {

    fun get(url: String, timeoutSeconds: Long = 15): JsonObject {
        val request = newRequest(url).timeout(Duration.ofSeconds(timeoutSeconds)).GET().build()
        return parse(client.send(request, HttpResponse.BodyHandlers.ofString()))
    }

    fun post(url: String, body: JsonObject, timeoutSeconds: Long = 60): JsonObject {
        val request = newRequest(url)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        return parse(client.send(request, HttpResponse.BodyHandlers.ofString()))
    }

    /** The caller must close the returned stream. The first request to a model can be slow, so the timeout is generous. */
    fun postStream(url: String, body: JsonObject, timeoutMinutes: Long = 5): HttpResponse<InputStream> {
        val request = newRequest(url)
            .timeout(Duration.ofMinutes(timeoutMinutes))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    }

    private fun parse(response: HttpResponse<String>): JsonObject {
        if (response.statusCode() !in 200..299) {
            throw IOException("HTTP ${response.statusCode()}: ${response.body().take(300)}")
        }
        return JsonParser.parseString(response.body()).asJsonObject
    }

    private fun newRequest(url: String): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create(url))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        return builder
    }

    private companion object {
        // HTTP/1.1 on purpose: the JDK client's h2c upgrade attempt on plain http:// confuses some servers.
        val client: HttpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    }
}