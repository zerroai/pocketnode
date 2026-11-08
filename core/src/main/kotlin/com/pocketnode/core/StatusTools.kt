package com.pocketnode.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the read-only status tools need from the app. */
interface StatusHost {
    fun activity(limit: Int, about: String, problemsOnly: Boolean): List<ActivityEntry>
    /** Short facts for the Settings screen, in order: label to value. */
    fun status(): List<Pair<String, String>>
    fun devices(): List<String>
}

/**
 * Read-only tools that answer "what did you do", "how much have I used" and "which devices do I have", the same things the owner can read in
 * Activity, Settings and Devices. They change nothing. They are never given to other devices.
 */
object StatusTools {
    private fun obj(args: String): JsonObject = try { Json.parseToJsonElement(args) as? JsonObject ?: JsonObject(emptyMap()) } catch (e: Exception) { JsonObject(emptyMap()) }
    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty().trim()

    fun all(h: StatusHost): List<Tool> = listOf(
        tool("activity.recent", "Lists what the assistant did lately: tools used, requests that left the phone, questions asked of the owner. Use it when the owner asks what you did or whether anything went wrong.",
            """{"type":"object","properties":{"about":{"type":"string","description":"Only entries that mention this word"},"limit":{"type":"integer","description":"How many, up to 30"},"problems_only":{"type":"boolean"}}}""") { args ->
            val o = obj(args)
            val limit = str(o, "limit").toIntOrNull()?.coerceIn(1, 30) ?: 10
            val rows = h.activity(limit, str(o, "about"), str(o, "problems_only").lowercase() == "true")
            if (rows.isEmpty()) "Nothing matches." else rows.joinToString("\n") { e ->
                val t = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US).format(java.util.Date(e.at))
                "$t ${e.kind.name.lowercase()}: ${e.subject.take(60)}" + (if (e.detail.isNotBlank()) " (${e.detail.replace(Regex("\\s+"), " ").take(100)})" else "") + (if (e.problem) " [problem]" else "")
            }
        },
        tool("assistant.status", "Tells the owner how the assistant is set up: whether it is on, which model it uses, how much of today's limit is used, the plan, and how many agents and devices there are.", """{"type":"object","properties":{}}""") {
            h.status().joinToString("\n") { (k, v) -> "$k: $v" }
        },
    )

    private fun tool(name: String, about: String, schema: String, body: (String) -> String) = object : Tool {
        override val spec = ToolSpec(name, about, schema, idempotent = true)
        override val needs: Set<Permission> = emptySet()
        override fun call(args: String): String = try { body(args) } catch (e: Exception) { "Error: ${(e.message ?: "It did not work.").take(160)}" }
    }
}
