package com.pocketnode.core

import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What an export file holds, read and checked but not yet added to the phone. */
class ImportPlan(val chats: List<ExportChat>, val reminders: List<Reminder>, val notes: DataExport.Notes, val notesRaw: JsonObject?, val packs: Map<String, JsonObject> = emptyMap())

class ImportResult(
    val chatsAdded: Int, val chatsSkipped: Int,
    val remindersAdded: List<Reminder>, val remindersSkipped: Int,
    val notesAdded: Int, val todosAdded: Int, val notesSkipped: Boolean,
    /** Items added to the other data packs, and the titles of packs whose data was left out because the pack is not installed. */
    val packItemsAdded: Int = 0, val packItemsSkipped: Int = 0, val packsNotInstalled: List<String> = emptyList(),
)

/**
 * Brings back what [DataExport] wrote. Nothing is overwritten: a chat that is already on the phone is left as it is, a reminder
 * that is already set or already past is skipped, and notes and to-dos are added only when no note or to-do has the same words.
 * Photos and attached files are not in an export, so they do not come back.
 */
object DataImport {
    private const val MAX_BYTES = 60_000_000
    private const val MAX_ITEMS = 500
    private val json = Json { ignoreUnknownKeys = true }

    /** Reads the zip. Throws [IllegalArgumentException] with a plain sentence when it is not an export of ours or is damaged. */
    fun read(zip: ByteArray): ImportPlan {
        if (zip.size < 4 || zip[0] != 'P'.code.toByte() || zip[1] != 'K'.code.toByte()) throw IllegalArgumentException("That file is not a zip file.")
        val text = try { dataJson(zip) } catch (e: IllegalArgumentException) { throw e } catch (e: Exception) { throw IllegalArgumentException("That file is not a zip file.") }
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null } ?: throw IllegalArgumentException("The data in that file could not be read.")
        if (root["app"]?.jsonPrimitive?.contentOrNull != "Pocketnode") throw IllegalArgumentException("That is not a Pocketnode export.")
        val chats = (root["chats"] as? JsonArray).orEmpty().mapNotNull { c -> (c as? JsonObject)?.let(::chat) }
        val reminders = (root["reminders"] as? JsonArray).orEmpty().mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            val txt = o["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val at = o["at"]?.jsonPrimitive?.contentOrNull?.let(::parseTime) ?: return@mapNotNull null
            Reminder(0, txt, at, o["repeat"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }, 0)
        }
        val raw = root["notes"] as? JsonObject
        val packs = (root["packs"] as? JsonObject).orEmpty().mapNotNull { (id, v) -> (v as? JsonObject)?.takeIf { PackData.spec(id) != null && PackData.count(id, it) > 0 }?.let { id to it } }.toMap()
        return ImportPlan(chats, reminders, DataExport.notes(raw?.toString()), raw, packs)
    }

    private fun dataJson(zip: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name != "data.json") continue
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16_384); var total = 0
                while (true) {
                    val n = z.read(buf); if (n < 0) break
                    total += n; if (total > MAX_BYTES) throw IllegalArgumentException("That file is too big to import.")
                    out.write(buf, 0, n)
                }
                return out.toString(Charsets.UTF_8.name())
            }
        }
        throw IllegalArgumentException("That is not a Pocketnode export (it has no data.json).")
    }

    private fun chat(o: JsonObject): ExportChat? {
        val id = o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 200 } ?: return null
        val msgs = (o["messages"] as? JsonArray).orEmpty().mapNotNull { m ->
            val mo = m as? JsonObject ?: return@mapNotNull null
            val text = mo["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            when (mo["role"]?.jsonPrimitive?.contentOrNull) {
                "user" -> Msg.User(text)
                "assistant" -> Msg.Assistant(text)
                "tool" -> Msg.ToolResult("", mo["tool"]?.jsonPrimitive?.contentOrNull.orEmpty(), mo["result"]?.jsonPrimitive?.contentOrNull.orEmpty())
                else -> null
            }
        }
        return ExportChat(id, o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["lastActivity"]?.jsonPrimitive?.contentOrNull?.let(::parseTime) ?: 0L, msgs)
    }

    private fun parseTime(s: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)?.time
    } catch (e: Exception) { null }

    /**
     * Adds what is new. [notesKv] is the store the notes pack keeps its data in, or null when that pack is not installed (notes
     * are then left out and [ImportResult.notesSkipped] says so).
     */
    fun apply(plan: ImportPlan, store: RunStore, reminders: ReminderStore, notesKv: PackKv?, nowMillis: Long, packKv: PackKv? = null, installedPacks: Set<String> = emptySet()): ImportResult {
        var added = 0; var skipped = 0
        for (c in plan.chats) if (store.importSession(c.id, c.title, c.messages, c.lastActivityMillis)) added++ else skipped++

        val have = reminders.list().map { it.text to it.at }.toSet()
        val addedReminders = mutableListOf<Reminder>(); var reminderSkips = 0
        for (r in plan.reminders) {
            if (r.at <= nowMillis || (r.text to r.at) in have) { reminderSkips++; continue }
            val made = try { reminders.add(r.text, r.at, r.repeat) } catch (e: Exception) { null }
            if (made == null) reminderSkips++ else addedReminders += made
        }

        var notesAdded = 0; var todosAdded = 0
        val wantsNotes = plan.notes.notes.isNotEmpty() || plan.notes.todos.isNotEmpty()
        if (wantsNotes && notesKv != null) {
            val current = parseObject(notesKv.get(NOTES_PACK, "data"))
            val oldNotes = (current["notes"] as? JsonArray).orEmpty().toMutableList()
            val oldTodos = (current["todos"] as? JsonArray).orEmpty().toMutableList()
            var next = current["next"]?.jsonPrimitive?.intOrNull ?: (1 + (oldNotes + oldTodos).maxOfOrNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull ?: 0 }.let { it ?: 0 })
            val haveNotes = oldNotes.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }.toMutableSet()
            val haveTodos = oldTodos.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }.toMutableSet()
            for (n in plan.notes.notes) if (oldNotes.size < MAX_ITEMS && haveNotes.add(n.text)) {
                oldNotes += buildJsonObject { put("id", next++); put("text", n.text.take(2000)); put("created", n.created ?: "") }; notesAdded++
            }
            for (t in plan.notes.todos) if (oldTodos.size < MAX_ITEMS && haveTodos.add(t.text)) {
                oldTodos += buildJsonObject { put("id", next++); put("text", t.text.take(2000)); put("due", t.due?.let { JsonPrimitive(it) } ?: JsonNull); put("done", t.done); put("created", "") }; todosAdded++
            }
            if (notesAdded + todosAdded > 0) notesKv.set(NOTES_PACK, "data", JsonObject(current + mapOf("next" to JsonPrimitive(next), "notes" to JsonArray(oldNotes), "todos" to JsonArray(oldTodos))).toString())
        }
        var packAdded = 0; var packSkipped = 0; val missing = mutableListOf<String>()
        for ((id, incoming) in plan.packs) {
            val spec = PackData.spec(id) ?: continue
            if (packKv == null || id !in installedPacks) { missing += spec.title; continue }
            val merged = PackData.merge(id, PackData.parse(packKv.get(id, spec.key)), incoming)
            if (merged.added > 0) packKv.set(id, spec.key, merged.data.toString())
            packAdded += merged.added; packSkipped += merged.skipped
        }
        return ImportResult(added, skipped, addedReminders, reminderSkips, notesAdded, todosAdded, notesSkipped = wantsNotes && notesKv == null, packItemsAdded = packAdded, packItemsSkipped = packSkipped, packsNotInstalled = missing)
    }

    private fun parseObject(raw: String?): JsonObject = try { raw?.takeIf { it.isNotBlank() }?.let { json.parseToJsonElement(it) as? JsonObject } ?: JsonObject(emptyMap()) } catch (e: Exception) { JsonObject(emptyMap()) }

    const val NOTES_PACK = "com.pocketnode.notes"
}
