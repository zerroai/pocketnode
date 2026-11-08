package com.pocketnode.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One input a tool takes, read from its schema, so the agent builder can show a box for it instead of asking for JSON. */
data class ArgField(val name: String, val type: String, val required: Boolean, val about: String)

object AgentArgs {
    private val json = Json { ignoreUnknownKeys = true }

    /** The simple inputs of a tool (text, numbers, yes/no). Inputs of other shapes are left out; they cannot be filled from a box. */
    fun fields(schema: String): List<ArgField> {
        val o = runCatching { json.parseToJsonElement(schema) as? JsonObject }.getOrNull() ?: return emptyList()
        val props = o["properties"] as? JsonObject ?: return emptyList()
        val required = (o["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        return props.entries.mapNotNull { (k, v) ->
            val p = v as? JsonObject ?: return@mapNotNull null
            val t = (p["type"] as? JsonPrimitive)?.contentOrNull ?: "string"
            if (t !in setOf("string", "integer", "number", "boolean")) null
            else ArgField(k, t, k in required, (p["description"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
    }

    /** Builds the JSON a tool is called with. Empty boxes are left out; a box that holds a `{name}` of an earlier result stays text. */
    fun build(schema: String, values: Map<String, String>): String {
        val fs = fields(schema).associateBy { it.name }
        return buildJsonObject {
            values.forEach { (k, raw) ->
                val v = raw.trim()
                if (v.isEmpty()) return@forEach
                val f = fs[k]
                val ref = v.contains('{')
                when {
                    f == null || ref || f.type == "string" -> put(k, v)
                    f.type == "integer" -> { val n = v.toLongOrNull(); if (n != null) put(k, n) else put(k, v) }
                    f.type == "number" -> { val n = v.toDoubleOrNull(); if (n != null) put(k, n) else put(k, v) }
                    else -> put(k, v.equals("true", true) || v.equals("yes", true))
                }
            }
        }.toString()
    }

    /** The reverse: the text of each box for a saved call. */
    fun parse(argsJson: String): Map<String, String> {
        val o = runCatching { json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull() ?: return emptyMap()
        return o.mapValues { (_, v) -> (v as? JsonPrimitive)?.let { it.contentOrNull ?: it.toString() } ?: v.toString() }
    }
}
