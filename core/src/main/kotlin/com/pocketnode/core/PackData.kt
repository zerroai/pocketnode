package com.pocketnode.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What the other data packs (budget, study, clients and invoices, library, safety) keep, for Export my data and Import. The export
 * writes each pack's data as it is stored and a short readable list; the import adds only what the phone does not already have,
 * by comparing the words and numbers of each item, and never changes or replaces anything that is here.
 */
object PackData {
    class Spec(val packId: String, val title: String, val key: String)

    /** The packs covered here. Notes and to-dos have their own, older handling in [DataExport] and [DataImport]. */
    val SPECS = listOf(
        Spec("com.pocketnode.budget", "Budget", "data"),
        Spec("com.pocketnode.study", "Study cards", "data"),
        Spec("com.pocketnode.invoices", "Clients and invoices", "data"),
        Spec("com.pocketnode.library", "Library", "data"),
        Spec("com.pocketnode.safety", "Emergency contact", "contact"),
    )

    private val json = Json { ignoreUnknownKeys = true }
    fun spec(packId: String): Spec? = SPECS.firstOrNull { it.packId == packId }

    fun parse(raw: String?): JsonObject = try { raw?.takeIf { it.isNotBlank() }?.let { json.parseToJsonElement(it) as? JsonObject } ?: JsonObject(emptyMap()) } catch (e: Exception) { JsonObject(emptyMap()) }

    private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.long(k: String): Long = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
    private fun JsonObject.arr(k: String): List<JsonObject> = (this[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun cents(c: Long): String = "%d.%02d".format(c / 100, c % 100)
    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** How many items the data holds, counting what the owner would count. Zero means nothing worth exporting. */
    fun count(packId: String, data: JsonObject): Int = when (packId) {
        "com.pocketnode.budget" -> data.arr("entries").size
        "com.pocketnode.study" -> data.arr("cards").size
        "com.pocketnode.invoices" -> data.arr("invoices").size + data.arr("clients").size
        "com.pocketnode.library" -> data.arr("docs").size
        "com.pocketnode.safety" -> if (data.str("number").isNotBlank()) 1 else 0
        else -> 0
    }

    /** Readable lines for the export page. */
    fun lines(packId: String, data: JsonObject): List<String> = when (packId) {
        "com.pocketnode.budget" -> data.arr("entries").sortedBy { it.str("date") }.map { "${it.str("date")}  ${it.str("type")}  ${cents(it.long("cents"))}  ${oneLine(it.str("category"))}" + oneLine(it.str("note")).let { n -> if (n.isEmpty()) "" else "  ($n)" } } +
            data.let { (it["limits"] as? JsonObject).orEmpty().map { (k, v) -> "Monthly limit  ${oneLine(k)}  ${cents((v as? JsonPrimitive)?.longOrNull ?: 0)}" } }
        "com.pocketnode.study" -> data.arr("cards").map { "${oneLine(it.str("deck"))}: ${oneLine(it.str("front"))} = ${oneLine(it.str("back"))}" }
        "com.pocketnode.invoices" -> data.arr("clients").map { "Client: ${oneLine(it.str("name"))}" + it.str("email").let { e -> if (e.isEmpty()) "" else ", $e" } } +
            data.arr("invoices").map { "${it.str("number")}  ${oneLine(it.str("client"))}  issued ${it.str("issued")}, due ${it.str("due")}  total ${cents(it.long("totalCents"))}  ${if (it["paid"] is JsonPrimitive && it.str("paid").isNotBlank() && it.str("paid") != "null") "paid ${it.str("paid")}" else "not paid"}" }
        "com.pocketnode.library" -> data.arr("docs").map { "${oneLine(it.str("title"))} (${it.str("text").length} characters)" }
        "com.pocketnode.safety" -> if (count(packId, data) > 0) listOf("${oneLine(data.str("name"))}, ${data.str("number")}") else emptyList()
        else -> emptyList()
    }

    class Merged(val data: JsonObject, val added: Int, val skipped: Int)

    /** Adds the items of [incoming] that [current] does not have. Nothing in [current] is changed except by adding to it. */
    fun merge(packId: String, current: JsonObject, incoming: JsonObject): Merged = when (packId) {
        "com.pocketnode.budget" -> {
            val a = mergeList(current, incoming, "entries", "next", 5000) { "${it.str("date")}|${it.str("type")}|${it.long("cents")}|${it.str("category")}|${it.str("note")}" }
            val limits = (current["limits"] as? JsonObject).orEmpty().toMutableMap()
            var extra = 0
            for ((k, v) in (incoming["limits"] as? JsonObject).orEmpty()) if (k !in limits && (v as? JsonPrimitive)?.longOrNull != null) { limits[k] = v; extra++ }
            Merged(JsonObject(a.data + ("limits" to JsonObject(limits))), a.added + extra, a.skipped)
        }
        "com.pocketnode.study" -> mergeList(current, incoming, "cards", "next", 3000) { "${it.str("deck")}|${it.str("front")}|${it.str("back")}" }
        "com.pocketnode.library" -> mergeList(current, incoming, "docs", "next", 300) { "${it.str("title")}|${it.str("text")}" }
        "com.pocketnode.invoices" -> mergeInvoices(current, incoming)
        "com.pocketnode.safety" -> if (current.str("number").isBlank() && incoming.str("number").isNotBlank()) Merged(incoming, 1, 0) else Merged(current, 0, if (incoming.str("number").isNotBlank()) 1 else 0)
        else -> Merged(current, 0, 0)
    }

    /** Adds items by signature, giving each new one the next free id. Items without the shape of the others are skipped. */
    private fun mergeList(current: JsonObject, incoming: JsonObject, listKey: String, nextKey: String, max: Int, sig: (JsonObject) -> String): Merged {
        val items = current.arr(listKey).toMutableList()
        val have = items.map(sig).toMutableSet()
        var next = (current[nextKey] as? JsonPrimitive)?.intOrNull ?: (1 + (items.maxOfOrNull { (it["id"] as? JsonPrimitive)?.intOrNull ?: 0 } ?: 0))
        var added = 0; var skipped = 0
        for (it in incoming.arr(listKey)) {
            if (items.size >= max || !have.add(sig(it))) { skipped++; continue }
            items += JsonObject(it + ("id" to JsonPrimitive(next++))); added++
        }
        return Merged(JsonObject(current + (listKey to JsonArray(items)) + (nextKey to JsonPrimitive(next))), added, skipped)
    }

    private fun mergeInvoices(current: JsonObject, incoming: JsonObject): Merged {
        val clients = current.arr("clients").toMutableList()
        val haveClients = clients.map { it.str("name").lowercase() }.toMutableSet()
        val invoices = current.arr("invoices").toMutableList()
        val haveNumbers = invoices.map { it.str("number") }.toMutableSet()
        var next = (current["nextInvoice"] as? JsonPrimitive)?.intOrNull ?: 1
        var added = 0; var skipped = 0
        for (c in incoming.arr("clients")) if (clients.size < 500 && c.str("name").isNotBlank() && haveClients.add(c.str("name").lowercase())) { clients += c; added++ } else skipped++
        for (i in incoming.arr("invoices")) {
            val number = i.str("number")
            // A number that is already used here for a different invoice is not reused or renumbered: it is left out and counted.
            if (invoices.size >= 2000 || !Regex("INV-\\d{4,}").matches(number) || !haveNumbers.add(number)) { skipped++; continue }
            invoices += i; added++
            next = maxOf(next, number.removePrefix("INV-").toInt() + 1)
        }
        return Merged(JsonObject(current + ("clients" to JsonArray(clients)) + ("invoices" to JsonArray(invoices)) + ("nextInvoice" to JsonPrimitive(next))), added, skipped)
    }

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
}
