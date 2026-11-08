package com.pocketnode.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the settings tools need from the app. */
interface SettingsHost {
    /** "system", "light" or "dark". */
    fun appearance(): String
    fun setAppearance(mode: String)
    /** The daily limit in estimated tokens, 0 for none. */
    fun usageLimit(): Long
    fun setUsageLimit(tokens: Long)
    fun briefing(): BriefingSettings
    fun setBriefing(s: BriefingSettings)
    /** Asks the owner; null means yes, otherwise the reason it was refused. */
    fun ask(title: String, message: String): String?
}

/**
 * Settings the owner can change by hand, as tools for the chat: appearance (harmless, no question), the daily usage limit and the morning
 * briefing (the owner is asked first). Keys, permissions, accounts and the assistant's on/off switch stay manual (docs/design/chat-parity.md, B).
 * Never given to agents or other devices.
 */
object SettingsTools {
    private fun obj(args: String): JsonObject = try { Json.parseToJsonElement(args) as? JsonObject ?: JsonObject(emptyMap()) } catch (e: Exception) { JsonObject(emptyMap()) }
    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty().trim()

    fun all(h: SettingsHost): List<Tool> = listOf(appearance(h), usageLimit(h), briefing(h))

    private fun tool(name: String, about: String, schema: String, body: (String) -> String) = object : Tool {
        override val spec = ToolSpec(name, about, schema, idempotent = false)
        override val needs: Set<Permission> = emptySet()
        override fun call(args: String): String = try { body(args) } catch (e: Exception) { "Error: ${(e.message ?: "It did not work.").take(160)}" }
    }

    private fun appearance(h: SettingsHost) = tool("settings.appearance", "Shows or changes how the app looks: light, dark or system (follow the phone). Leave mode out to just say what it is now.",
        """{"type":"object","properties":{"mode":{"type":"string","enum":["light","dark","system"]}}}""") { args ->
        val mode = str(obj(args), "mode").lowercase()
        if (mode.isEmpty()) return@tool "The app looks ${h.appearance()} now."
        if (mode !in setOf("light", "dark", "system")) return@tool "Error: The choices are light, dark and system."
        h.setAppearance(mode)
        "The app now looks $mode."
    }

    /** Reads "300000", "300k", "1m" or "1 million" as tokens; "none", "off" or "no limit" as 0; null when it is not a number. */
    fun parseLimit(text: String): Long? {
        val t = text.lowercase().trim()
        if (t in setOf("none", "off", "no limit", "no", "0", "unlimited")) return 0L
        val m = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*(k|thousand|m|million)?\\b").find(t) ?: return null
        val n = m.groupValues[1].toDouble()
        val mult = when (m.groupValues[2]) { "k", "thousand" -> 1_000.0; "m", "million" -> 1_000_000.0; else -> 1.0 }
        return (n * mult).toLong()
    }

    private fun usageLimit(h: SettingsHost) = tool("settings.usage_limit", "Shows or changes the daily limit on how much the assistant may use the model, in estimated tokens (about four letters each). The owner is asked to confirm a change. Choices: none, 100 thousand, 300 thousand, 1 million, 3 million. Leave limit out to just say what it is now.",
        """{"type":"object","properties":{"limit":{"type":"string","description":"For example none, 300k or 1 million"}}}""") { args ->
        val text = str(obj(args), "limit")
        if (text.isEmpty()) return@tool "The daily limit is ${UsageMeter.describe(h.usageLimit())}."
        val want = parseLimit(text) ?: return@tool "Error: I could not read \"${text.take(30)}\" as a limit. The choices are ${UsageMeter.CHOICES.joinToString(", ") { UsageMeter.describe(it) }}."
        val pick = UsageMeter.CHOICES.firstOrNull { it == want } ?: return@tool "Error: The choices are ${UsageMeter.CHOICES.joinToString(", ") { UsageMeter.describe(it) }}."
        if (pick == h.usageLimit()) return@tool "The daily limit is already ${UsageMeter.describe(pick)}."
        h.ask("Change the daily limit?", "From ${UsageMeter.describe(h.usageLimit())} to ${UsageMeter.describe(pick)}.")?.let { return@tool "Error: $it" }
        h.setUsageLimit(pick)
        "The daily limit is now ${UsageMeter.describe(pick)}."
    }

    private fun briefing(h: SettingsHost) = tool("settings.briefing", "Shows or changes the morning briefing: switch it on or off and set the time. The owner is asked to confirm a change. Leave everything out to just say how it is now.",
        """{"type":"object","properties":{"on":{"type":"boolean"},"at":{"type":"string","description":"The time, for example 7:30 or 8 in the morning"}}}""") { args ->
        val o = obj(args)
        val cur = h.briefing()
        val onText = str(o, "on").lowercase()
        val at = str(o, "at")
        fun now() = if (cur.enabled) "on at ${cur.timeText()}" else "off"
        if (onText.isEmpty() && at.isEmpty()) return@tool "The morning briefing is ${now()}."
        var next = cur
        if (at.isNotEmpty()) {
            val t = parseClock(at) ?: return@tool "Error: I could not read \"${at.take(30)}\" as a time. Try 7:30 or 8."
            next = next.copy(hour = t.first, minute = t.second)
        }
        if (onText.isNotEmpty()) next = next.copy(enabled = onText != "false")
        else if (at.isNotEmpty() && !cur.enabled) next = next.copy(enabled = true)
        if (next == cur) return@tool "The morning briefing is already ${now()}."
        val after = if (next.enabled) "on at ${next.timeText()}" else "off"
        h.ask("Change the morning briefing?", "It is ${now()}. It will be $after.")?.let { return@tool "Error: $it" }
        h.setBriefing(next)
        "The morning briefing is now $after."
    }
}

/** Reads "7:30", "8", "8am" or "6:15 pm" as (hour, minute), or null. */
internal fun parseClock(text: String): Pair<Int, Int>? {
    val m = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""", RegexOption.IGNORE_CASE).find(text.trim()) ?: return null
    var h = m.groupValues[1].toInt()
    val min = m.groupValues[2].ifEmpty { "0" }.toInt()
    when (m.groupValues[3].lowercase()) { "pm" -> if (h < 12) h += 12; "am" -> if (h == 12) h = 0 }
    return if (h in 0..23 && min in 0..59) h to min else null
}
