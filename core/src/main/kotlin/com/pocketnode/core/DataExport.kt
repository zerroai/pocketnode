package com.pocketnode.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One conversation, ready to export. */
class ExportChat(val id: String, val title: String, val lastActivityMillis: Long, val messages: List<Msg>)

/**
 * Everything the owner can take out of the app as text: conversations, reminders, notes and to-dos. The result is one zip file
 * with a readable page, the same data in a structured file, and a short note on what is and is not inside.
 * Photos, attached files, made files, keys and passwords are not included (made files can be saved one by one from Files).
 */
object DataExport {
    private val json = Json { prettyPrint = true }

    fun zip(chats: List<ExportChat>, reminders: List<Reminder>, notesJson: String?, nowMillis: Long, packData: Map<String, String?> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun add(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            add("README.txt", readme(chats.size, reminders.size, notes(notesJson), nowMillis, packData))
            add("chats.md", markdown(chats, reminders, notesJson, nowMillis, packData))
            add("data.json", data(chats, reminders, notesJson, nowMillis, packData))
        }
        return out.toByteArray()
    }

    fun readme(chatCount: Int, reminderCount: Int, notes: Notes, nowMillis: Long, packData: Map<String, String?> = emptyMap()): String = """
Pocketnode export, ${stamp(nowMillis)}

This file holds ${plural(chatCount, "chat")}, ${plural(reminderCount, "reminder")}, ${plural(notes.notes.size, "note")} and ${plural(notes.todos.size, "to-do")}.${packSentence(packData)}

- chats.md is for reading. Open it in any text editor.
- data.json holds the same things in a form other programs can read.

Not included: photos and files you attached, files the assistant made (save those one by one from the Files tab), and your keys, passwords and tokens.

This file is not locked. Anyone who has it can read your chats, so keep it somewhere safe.
""".trimStart()

    /** The readable page. */
    fun markdown(chats: List<ExportChat>, reminders: List<Reminder>, notesJson: String?, nowMillis: Long, packData: Map<String, String?> = emptyMap()): String {
        val n = notes(notesJson)
        val sb = StringBuilder()
        sb.append("# Pocketnode export\n\nExported ${stamp(nowMillis)}. ${plural(chats.size, "chat")}, ${plural(reminders.size, "reminder")}, ${plural(n.notes.size, "note")}, ${plural(n.todos.size, "to-do")}.\n")
        sb.append("\n## Chats\n")
        if (chats.isEmpty()) sb.append("\nNo chats.\n")
        for (c in chats) {
            sb.append("\n### ").append(oneLine(c.title.ifBlank { "Untitled chat" })).append("\n")
            sb.append("\n").append(source(c.id)).append(", last message ").append(stamp(c.lastActivityMillis)).append("\n")
            for (m in c.messages) when (m) {
                is Msg.User -> sb.append("\n**You:** ").append(safe(userText(m.text))).append("\n")
                is Msg.Assistant -> {
                    if (m.text.isNotBlank()) sb.append("\n**Assistant:** ").append(safe(MarkdownLite.hideInternalLinks(m.text).trim())).append("\n")
                    for (call in m.calls) sb.append("\n*Used the ").append(call.name).append(" tool.*\n")
                }
                is Msg.ToolResult -> {}
            }
        }
        sb.append("\n## Reminders\n")
        if (reminders.isEmpty()) sb.append("\nNone waiting.\n")
        for (r in reminders) sb.append("\n- ").append(stamp(r.at)).append(if (r.repeat.isNullOrBlank()) "" else " (repeats ${r.repeat})").append(": ").append(oneLine(r.text)).append("\n")
        sb.append("\n## Notes\n")
        if (n.notes.isEmpty()) sb.append("\nNone.\n")
        for (note in n.notes) sb.append("\n- ").append(note.created?.let { "$it: " }.orEmpty()).append(oneLine(note.text)).append("\n")
        sb.append("\n## To-dos\n")
        if (n.todos.isEmpty()) sb.append("\nNone.\n")
        for (t in n.todos) sb.append("\n- [").append(if (t.done) "x" else " ").append("] ").append(oneLine(t.text)).append(t.due?.let { " (due $it)" }.orEmpty()).append("\n")
        for (spec in PackData.SPECS) {
            val d = PackData.parse(packData[spec.packId])
            if (PackData.count(spec.packId, d) == 0) continue
            sb.append("\n## ").append(spec.title).append("\n")
            for (line in PackData.lines(spec.packId, d)) sb.append("\n- ").append(oneLine(line)).append("\n")
        }
        return sb.toString()
    }

    /** One conversation as plain text for sharing: who said what, in order. Tool results, photos and attached files are left out. */
    fun chatText(chat: ExportChat, nowMillis: Long): String {
        val sb = StringBuilder()
        sb.append(oneLine(chat.title.ifBlank { "Untitled chat" })).append("\n")
        sb.append("Shared from Pocketnode, ").append(stamp(nowMillis)).append("\n")
        for (m in chat.messages) when (m) {
            is Msg.User -> userText(m.text).trim().takeIf { it.isNotEmpty() }?.let { sb.append("\nYou: ").append(it).append("\n") }
            is Msg.Assistant -> MarkdownLite.hideInternalLinks(m.text).trim().takeIf { it.isNotEmpty() }?.let { sb.append("\nAssistant: ").append(it).append("\n") }
            is Msg.ToolResult -> {}
        }
        return sb.toString()
    }

    /** The structured copy. Times are UTC, written like 2026-10-23T21:08:00Z. */
    fun data(chats: List<ExportChat>, reminders: List<Reminder>, notesJson: String?, nowMillis: Long, packData: Map<String, String?> = emptyMap()): String {
        val root = buildJsonObject {
            put("app", "Pocketnode")
            put("exportedAt", iso(nowMillis))
            put("chats", buildJsonArray {
                for (c in chats) add(buildJsonObject {
                    put("id", c.id)
                    put("title", c.title)
                    put("source", source(c.id))
                    put("lastActivity", iso(c.lastActivityMillis))
                    put("messages", buildJsonArray {
                        for (m in c.messages) add(when (m) {
                            is Msg.User -> {
                                val p = Attachments.parse(m.text)
                                buildJsonObject {
                                    put("role", "user"); put("text", p.text)
                                    put("photos", p.photos.size)
                                    put("files", buildJsonArray { p.files.forEach { add(JsonPrimitive(Attachments.fileSource(it)?.name ?: "file")) } })
                                }
                            }
                            is Msg.Assistant -> buildJsonObject {
                                put("role", "assistant"); put("text", MarkdownLite.hideInternalLinks(m.text))
                                put("toolCalls", buildJsonArray { m.calls.forEach { call -> add(buildJsonObject { put("name", call.name); put("arguments", call.args) }) } })
                            }
                            is Msg.ToolResult -> buildJsonObject { put("role", "tool"); put("tool", m.name); put("result", m.text) }
                        })
                    })
                })
            })
            put("reminders", buildJsonArray {
                for (r in reminders) add(buildJsonObject {
                    put("text", r.text); put("at", iso(r.at)); put("repeat", r.repeat ?: ""); put("created", iso(r.createdAt))
                })
            })
            put("notes", notesElement(notesJson))
            put("packs", buildJsonObject {
                for (spec in PackData.SPECS) {
                    val d = PackData.parse(packData[spec.packId])
                    if (PackData.count(spec.packId, d) > 0) put(spec.packId, d)
                }
            })
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }

    /** " It also holds your budget, ..." for the packs that have something saved, or nothing. */
    private fun packSentence(packData: Map<String, String?>): String {
        val names = PackData.SPECS.filter { PackData.count(it.packId, PackData.parse(packData[it.packId])) > 0 }.map { it.title.lowercase() }
        return if (names.isEmpty()) "" else " It also holds what these packs saved: ${names.joinToString(", ")}."
    }

    class Note(val text: String, val created: String?)
    class Todo(val text: String, val done: Boolean, val due: String?)
    class Notes(val notes: List<Note>, val todos: List<Todo>)

    /** Reads what the notes pack keeps. Anything unreadable gives an empty result rather than failing the export. */
    fun notes(raw: String?): Notes {
        val o = parseObject(raw) ?: return Notes(emptyList(), emptyList())
        fun str(e: JsonObject, k: String) = (e[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val notes = (o["notes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { n -> str(n, "text")?.let { t -> Note(t, str(n, "created")) } } }
        val todos = (o["todos"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { n -> str(n, "text")?.let { t -> Todo(t, (n["done"] as? JsonPrimitive)?.booleanOrNull == true, str(n, "due")) } } }
        return Notes(notes, todos)
    }

    private fun notesElement(raw: String?): JsonElement = parseObject(raw) ?: JsonNull
    private fun parseObject(raw: String?): JsonObject? = try { raw?.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it) as? JsonObject } } catch (e: Exception) { null }

    private fun userText(raw: String): String {
        val p = Attachments.parse(raw)
        val parts = mutableListOf<String>()
        if (p.text.isNotBlank()) parts += p.text.trim()
        repeat(p.photos.size) { parts += "[a photo was attached]" }
        p.files.forEach { parts += "[file attached: ${Attachments.fileSource(it)?.name ?: "file"}]" }
        return parts.joinToString("\n\n")
    }

    private fun source(id: String) = when {
        id.startsWith("telegram:") -> "Telegram chat"
        id.startsWith("whatsapp:") -> "WhatsApp chat"
        id.startsWith("briefing:") -> "Morning briefing"
        else -> "Chat on this phone"
    }

    /** A heading inside a message must not look like the heading of a chat, so a line that starts with # gets a backslash. */
    private fun safe(text: String) = text.replace(Regex("(?m)^(#{1,6})(\\s)"), "\\\\$1$2")

    private fun oneLine(s: String) = s.replace(Regex("\\s*\\n\\s*"), " ").trim()
    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
    private fun stamp(millis: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date(millis))
    private fun iso(millis: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))
}
