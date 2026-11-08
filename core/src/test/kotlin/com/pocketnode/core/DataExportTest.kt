package com.pocketnode.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataExportTest {
    private val now = 1_792_786_000_000L
    private val photo = "mc-image:0123456789abcdef"
    private val file = "mc-file:fedcba9876543210"

    private val chats = listOf(
        ExportChat("local:abc", "Packing list", now, listOf(
            Msg.User("What should I pack?\n$photo\n$file"),
            Msg.Assistant("", listOf(ToolCall("c1", "weather.now", "{\"place\":\"Lagos\"}"))),
            Msg.ToolResult("c1", "weather.now", "{\"temperatureC\":27}"),
            Msg.Assistant("Take light clothes. You can open it [here](sandbox:list.pdf)."),
        )),
        ExportChat("telegram:42", "Telegram chat", now - 60_000, listOf(Msg.User("hi"), Msg.Assistant("hello"))),
    )
    private val reminders = listOf(Reminder(1, "Call mum", now + 3_600_000, "daily", now))
    private val notes = """{"next":3,"notes":[{"id":1,"text":"Buy milk","created":"2026-10-16T10:00:00Z"}],"todos":[{"id":2,"text":"Pay rent","done":false,"due":"2026-11-01"},{"id":3,"text":"Book flight","done":true}]}"""

    @AfterTest fun reset() { Attachments.fileSource = { null } }

    @Test fun readablePageHasChatsRemindersNotesAndTodos() {
        Attachments.fileSource = { StoredFile("notes.txt", "x") }
        val md = DataExport.markdown(chats, reminders, notes, now)
        assertTrue("### Packing list" in md)
        assertTrue("**You:** What should I pack?" in md)
        assertTrue("[a photo was attached]" in md)
        assertTrue("[file attached: notes.txt]" in md)
        assertTrue("*Used the weather.now tool.*" in md)
        assertTrue("Take light clothes. You can open it above." in md)
        assertTrue("Telegram chat, last message" in md)
        assertTrue("Call mum" in md && "repeats daily" in md)
        assertTrue("Buy milk" in md)
        assertTrue("- [ ] Pay rent (due 2026-11-01)" in md)
        assertTrue("- [x] Book flight" in md)
    }

    @Test fun rawReferencesAndToolResultsDoNotLeakIntoTheReadablePage() {
        val md = DataExport.markdown(chats, reminders, notes, now)
        assertFalse("mc-image:" in md)
        assertFalse("mc-file:" in md)
        assertFalse("temperatureC" in md)
        assertFalse("sandbox:" in md)
    }

    @Test fun structuredFileParsesAndKeepsToolResults() {
        val root = Json.parseToJsonElement(DataExport.data(chats, reminders, notes, now)).jsonObject
        assertEquals("Pocketnode", root["app"]!!.jsonPrimitive.content)
        val first = root["chats"]!!.jsonArray[0].jsonObject
        assertEquals("Packing list", first["title"]!!.jsonPrimitive.content)
        assertEquals("Chat on this phone", first["source"]!!.jsonPrimitive.content)
        val msgs = first["messages"]!!.jsonArray
        assertEquals(listOf("user", "assistant", "tool", "assistant"), msgs.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("1", msgs[0].jsonObject["photos"]!!.jsonPrimitive.content)
        assertEquals("What should I pack?", msgs[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue("temperatureC" in msgs[2].jsonObject["result"]!!.jsonPrimitive.content)
        assertEquals("weather.now", msgs[1].jsonObject["toolCalls"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("Telegram chat", root["chats"]!!.jsonArray[1].jsonObject["source"]!!.jsonPrimitive.content)
        assertTrue(root["reminders"]!!.jsonArray[0].jsonObject["at"]!!.jsonPrimitive.content.endsWith("Z"))
        assertEquals(1, (root["notes"] as JsonObject)["notes"]!!.jsonArray.size)
    }

    @Test fun aHeadingInsideAMessageIsNotMistakenForAChat() {
        val one = ExportChat("local:x", "Only chat", now, listOf(Msg.User("# my heading\nplain"), Msg.Assistant("Here:\n### Step one\ntext")))
        val md = DataExport.markdown(listOf(one), emptyList(), null, now)
        assertEquals(1, Regex("(?m)^### ").findAll(md).count())
        assertTrue("\\### Step one" in md)
    }

    @Test fun noKeysOrTokensAreInTheExport() {
        val all = DataExport.markdown(chats, reminders, notes, now) + DataExport.data(chats, reminders, notes, now) + DataExport.readme(2, 1, DataExport.notes(notes), now)
        assertFalse("api_key" in all.lowercase() || "bot_token" in all.lowercase())
    }

    @Test fun emptyDataStillMakesAValidExport() {
        val md = DataExport.markdown(emptyList(), emptyList(), null, now)
        assertTrue("No chats." in md && "None waiting." in md)
        val root = Json.parseToJsonElement(DataExport.data(emptyList(), emptyList(), null, now)).jsonObject
        assertEquals(0, root["chats"]!!.jsonArray.size)
    }

    @Test fun damagedNotesDoNotStopTheExport() {
        assertEquals(0, DataExport.notes("{not json").notes.size)
        assertEquals(0, DataExport.notes("[1,2]").todos.size)
        val md = DataExport.markdown(chats, reminders, "{not json", now)
        assertTrue("### Packing list" in md)
    }

    @Test fun zipHasTheThreeFiles() {
        val bytes = DataExport.zip(chats, reminders, notes, now)
        val names = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; names[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(setOf("README.txt", "chats.md", "data.json"), names.keys)
        assertTrue("not locked" in names["README.txt"]!!)
        assertTrue("2 chats" in names["README.txt"]!!)
    }

    @Test fun `one chat can be turned into plain text for sharing, without tool results or attachment markers`() {
        val chat = ExportChat("local:1", "Trip\nplan", 0L, listOf(
            Msg.User("Where should we go?"), Msg.Assistant("Try Lagos.", listOf(ToolCall("c1", "weather.now", "{}"))),
            Msg.ToolResult("c1", "weather.now", """{"temp":29,"secret":"internal"}"""), Msg.Assistant("It is 29 degrees."),
        ))
        val text = DataExport.chatText(chat, 0L)
        val lines = text.lines()
        assertEquals("Trip plan", lines[0]); assertEquals(true, lines[1].startsWith("Shared from Pocketnode, 1 Jan 1970"), lines[1])
        assertEquals("\nYou: Where should we go?\n\nAssistant: Try Lagos.\n\nAssistant: It is 29 degrees.\n", text.substringAfter("\n").substringAfter("\n"))
        assertEquals(false, "internal" in text)
        assertEquals("Untitled chat", DataExport.chatText(ExportChat("x", " ", 0L, emptyList()), 0L).lines().first())
    }
}
