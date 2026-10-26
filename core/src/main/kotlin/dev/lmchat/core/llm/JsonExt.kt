package dev.lmchat.core.llm

import com.google.gson.JsonObject

fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull }?.asString

fun JsonObject.intOrNull(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt