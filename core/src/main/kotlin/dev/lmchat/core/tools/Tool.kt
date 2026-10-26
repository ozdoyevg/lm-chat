package dev.lmchat.core.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.lmchat.core.llm.stringOrNull

class ToolParameter(val name: String, val description: String)

/** A function the model may call. All parameters are strings. */
interface Tool {
    val name: String
    val description: String
    val parameters: List<ToolParameter>
    fun execute(arguments: JsonObject): String
}

class FunctionTool(
    override val name: String,
    override val description: String,
    override val parameters: List<ToolParameter> = emptyList(),
    private val body: (JsonObject) -> String
) : Tool {
    override fun execute(arguments: JsonObject): String = body(arguments)
}

fun JsonObject.requireString(key: String): String = stringOrNull(key) ?: error("Missing argument: $key")

/** What the agent needs from a set of tools: their JSON schema and a way to run one by name. */
interface ToolHost {
    val specs: JsonArray
    fun execute(name: String, argumentsJson: String): String
}

object NoTools : ToolHost {
    override val specs = JsonArray()
    override fun execute(name: String, argumentsJson: String) = "Error: no tools are available"
}

class ToolRegistry(tools: List<Tool>) : ToolHost {
    private val byName = tools.associateBy { it.name }

    override val specs: JsonArray = JsonArray().also { array -> tools.forEach { array.add(describe(it)) } }

    /** Never throws: failures come back as "Error: ..." so the model can react to them. */
    override fun execute(name: String, argumentsJson: String): String {
        val tool = byName[name] ?: return "Unknown tool: $name"
        return try {
            val arguments =
                if (argumentsJson.isBlank()) JsonObject() else JsonParser.parseString(argumentsJson).asJsonObject
            tool.execute(arguments)
        } catch (e: Exception) {
            "Error: ${e.message ?: e}"
        }
    }

    private fun describe(tool: Tool): JsonObject {
        val properties = JsonObject()
        val required = JsonArray()
        for (parameter in tool.parameters) {
            properties.add(parameter.name, JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", parameter.description)
            })
            required.add(parameter.name)
        }
        val schema = JsonObject().apply {
            addProperty("type", "object")
            add("properties", properties)
            add("required", required)
        }
        val function = JsonObject().apply {
            addProperty("name", tool.name)
            addProperty("description", tool.description)
            add("parameters", schema)
        }
        return JsonObject().apply { addProperty("type", "function"); add("function", function) }
    }
}

object ToolResults {
    const val DENIED = "The user denied this change."

    fun isFailure(output: String) = output.startsWith("Error") || output == DENIED

    /** One-line description of a tool result for display. */
    fun summarize(output: String, maxLength: Int = 120): String =
        if (output.length <= maxLength) output.replace('\n', ' ') else "${output.length} chars returned"
}