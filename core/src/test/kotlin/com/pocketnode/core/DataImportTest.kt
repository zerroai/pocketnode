package com.pocketnode.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataImportTest {
    private val now = 1_792_786_000_000L
    private val day = 86_400_000L
    private val notes = """{"next":4,"notes":[{"id":1,"text":"Buy milk","created":"2026-10-16T10:00:00Z"},{"id":2,"text":"Call Ada","created":"2026-10-17T10:00:00Z"}],"todos":[{"id":3,"text":"Pay rent","done":false,"due":"2026-11-01","created":"x"}]}"""

    /** A phone with some history, exported. */
    private fun exported(): ByteArray {
        val db = JdbcSqlDb(":memory:")
        val store = SqlRunStore(db)
        val r = store.claimRun("local:a", "r1", "What is the weather in Lagos?")
        store.putStep(r, Step(1, StepKind.TOOL, StepStatus.DONE, text = "{\"temperatureC\":27}", call = ToolCall("c1", "weather.now", "{}")))
        store.finishRun(r, RunOutcome.ANSWERED, "It is 27 degrees.")
        store.setSessionTitle("local:a", "Weather")
        val t = store.claimRun("telegram:7", "r2", "hello from telegram")
        store.finishRun(t, RunOutcome.ANSWERED, "hi there")
        val reminders = ReminderStore(db) { now }
        reminders.add("Call mum", now + 2 * day, "daily"); reminders.add("Old thing", now + 10_000)
        val chats = store.sessions().map { s -> ExportChat(s.id, s.title, s.lastActivityMillis, store.timeline(s.id)) }
        return DataExport.zip(chats, reminders.list(), notes, now)
    }

    private inner class Target {
        val db = JdbcSqlDb(":memory:")
        val store = SqlRunStore(db)
        var clock = now + 60_000 // a minute later, so the 10 second reminder is already past
        val reminders = ReminderStore(db) { clock }
        val kv = SqlPackKv(db)
    }

    @Test fun `an export comes back as chats with their messages, tool results and order`() {
        val t = Target()
        val result = DataImport.apply(DataImport.read(exported()), t.store, t.reminders, t.kv, t.clock)
        assertEquals(2, result.chatsAdded); assertEquals(0, result.chatsSkipped)
        assertEquals(setOf("local:a", "telegram:7"), t.store.sessions().map { it.id }.toSet())
        assertEquals("Weather", t.store.sessions().first { it.id == "local:a" }.title)
        val line = t.store.timeline("local:a")
        assertEquals(listOf("user", "tool", "assistant"), line.map { when (it) { is Msg.User -> "user"; is Msg.ToolResult -> "tool"; is Msg.Assistant -> "assistant" } })
        assertEquals("What is the weather in Lagos?", (line[0] as Msg.User).text)
        assertEquals("weather.now", (line[1] as Msg.ToolResult).name)
        assertTrue("27" in (line[1] as Msg.ToolResult).text)
        assertEquals("It is 27 degrees.", (line[2] as Msg.Assistant).text)
    }

    @Test fun `importing twice changes nothing the second time`() {
        val t = Target(); val plan = DataImport.read(exported())
        DataImport.apply(plan, t.store, t.reminders, t.kv, t.clock)
        val again = DataImport.apply(plan, t.store, t.reminders, t.kv, t.clock)
        assertEquals(0, again.chatsAdded); assertEquals(2, again.chatsSkipped)
        assertEquals(0, again.remindersAdded.size)
        assertEquals(0, again.notesAdded + again.todosAdded)
        assertEquals(2, t.store.sessions().size)
        assertEquals(1, t.reminders.list().size)
    }

    @Test fun `a chat already on the phone is left exactly as it is`() {
        val t = Target()
        t.store.claimRun("local:a", "mine", "my own words")
        val result = DataImport.apply(DataImport.read(exported()), t.store, t.reminders, t.kv, t.clock)
        assertEquals(1, result.chatsAdded); assertEquals(1, result.chatsSkipped)
        assertEquals(listOf(Msg.User("my own words")), t.store.history("local:a"))
    }

    @Test fun `only reminders still in the future come back, with their repeat`() {
        val t = Target()
        val result = DataImport.apply(DataImport.read(exported()), t.store, t.reminders, t.kv, t.clock)
        assertEquals(1, result.remindersAdded.size); assertEquals(1, result.remindersSkipped)
        val r = t.reminders.list().single()
        assertEquals("Call mum", r.text); assertEquals("daily", r.repeat)
    }

    @Test fun `notes and to-dos are added next to existing ones without repeating the same words`() {
        val t = Target()
        t.kv.set(DataImport.NOTES_PACK, "data", """{"next":2,"notes":[{"id":1,"text":"Buy milk","created":"x"}],"todos":[]}""")
        val result = DataImport.apply(DataImport.read(exported()), t.store, t.reminders, t.kv, t.clock)
        assertEquals(1, result.notesAdded); assertEquals(1, result.todosAdded)
        val saved = Json.parseToJsonElement(t.kv.get(DataImport.NOTES_PACK, "data")!!).jsonObject
        assertEquals(listOf("Buy milk", "Call Ada"), saved["notes"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content })
        assertEquals("Pay rent", saved["todos"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        val ids = (saved["notes"]!!.jsonArray + saved["todos"]!!.jsonArray).map { it.jsonObject["id"]!!.jsonPrimitive.content.toInt() }
        assertEquals(ids.size, ids.toSet().size, "every note and to-do has its own id")
    }

    @Test fun `without the notes pack the notes are left out and that is said`() {
        val t = Target()
        val result = DataImport.apply(DataImport.read(exported()), t.store, t.reminders, null, t.clock)
        assertTrue(result.notesSkipped)
        assertEquals(0, result.notesAdded + result.todosAdded)
    }

    @Test fun `a file that is not ours is refused in plain words`() {
        fun zipOf(name: String, text: String) = ByteArrayOutputStream().also { o -> ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }.toByteArray()
        assertEquals("That file is not a zip file.", assertFailsWith<IllegalArgumentException> { DataImport.read("hello".toByteArray()) }.message)
        assertTrue(assertFailsWith<IllegalArgumentException> { DataImport.read(zipOf("other.txt", "x")) }.message!!.contains("data.json"))
        assertEquals("That is not a Pocketnode export.", assertFailsWith<IllegalArgumentException> { DataImport.read(zipOf("data.json", """{"app":"Something Else","chats":[]}""")) }.message)
        assertEquals("The data in that file could not be read.", assertFailsWith<IllegalArgumentException> { DataImport.read(zipOf("data.json", "{broken")) }.message)
    }

    @Test fun `damaged items are skipped and the rest still come in`() {
        val bad = """{"app":"Pocketnode","chats":[{"title":"no id","messages":[]},{"id":"local:z","title":"ok","lastActivity":"nonsense","messages":[{"role":"user","text":"hi"},{"role":"weird","text":"x"},"junk"]}],"reminders":[{"text":""},{"text":"x","at":"nope"}],"notes":null}"""
        val zip = ByteArrayOutputStream().also { o -> ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("data.json")); z.write(bad.toByteArray()); z.closeEntry() } }.toByteArray()
        val plan = DataImport.read(zip)
        assertEquals(1, plan.chats.size); assertEquals(0, plan.reminders.size)
        val t = Target()
        assertEquals(1, DataImport.apply(plan, t.store, t.reminders, t.kv, t.clock).chatsAdded)
        assertEquals(listOf(Msg.User("hi")), t.store.history("local:z"))
    }

    @Test fun `a chat comes back in the list at the time of its last message`() {
        val t = Target()
        DataImport.apply(DataImport.read(exported()), t.store, t.reminders, t.kv, t.clock)
        assertFalse(t.store.sessions().any { it.lastActivityMillis == 0L })
    }

}
