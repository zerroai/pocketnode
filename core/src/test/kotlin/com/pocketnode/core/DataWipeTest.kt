package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals

class DataWipeTest {
    private val db = JdbcSqlDb(":memory:")
    private val store = SqlRunStore(db)
    private val kv = SqlPackKv(db)
    private val reminders = ReminderStore(db)
    private val activity = ActivityLog(db)

    private fun count(table: String) = db.transaction { it.query("SELECT COUNT(*) FROM $table") { r -> r.long(0).toInt() }.first() }

    @Test fun erasesContentAndKeepsSetup() {
        store.setSessionTitle("local:a", "First")
        store.setSessionTitle("telegram:1", "Second")
        store.claimRun("local:a", "r1", "secret plans")
        reminders.add("call Ada", System.currentTimeMillis() + 60_000)
        activity.add(ActivityKind.SYSTEM, "something", "detail")
        kv.set("com.pocketnode.notes", "data", "{\"notes\":[{\"text\":\"private\"}]}")
        kv.set("com.pocketnode.telegram", "offset", "42")
        kv.set("com.pocketnode.telegram", "approved", "[1]")
        kv.set("core", "preinstalled.telegram", "1")
        kv.set("core.grants", "com.pocketnode.calendar", "calendar.read")

        val wipe = DataWipe(db)
        assertEquals(1, wipe.reminderIds().size)
        val counts = wipe.erase()

        assertEquals(2, counts.chats); assertEquals(1, counts.reminders); assertEquals(1, counts.packEntries)
        for (t in listOf("sessions", "messages", "runs", "steps", "activity", "reminders")) assertEquals(0, count(t), t)
        assertEquals(null, kv.get("com.pocketnode.notes", "data"))
        assertEquals("42", kv.get("com.pocketnode.telegram", "offset"))
        assertEquals("[1]", kv.get("com.pocketnode.telegram", "approved"))
        assertEquals("1", kv.get("core", "preinstalled.telegram"))
        assertEquals("calendar.read", kv.get("core.grants", "com.pocketnode.calendar"))
    }

    @Test fun anEmptyAppErasesWithoutTrouble() {
        val counts = DataWipe(db).erase()
        assertEquals(0, counts.chats + counts.reminders + counts.packEntries)
    }

    @Test fun chatsCanBeStartedAgainAfterwards() {
        store.claimRun("local:a", "r1", "one")
        DataWipe(db).erase()
        store.claimRun("local:a", "r2", "two")
        assertEquals(listOf("two"), store.timeline("local:a").filterIsInstance<Msg.User>().map { it.text })
    }

    @Test fun removingOnePacksDataLeavesTheOthersAndTheCoreSettings() {
        kv.set("com.pocketnode.budget", "data", "{}"); kv.set("com.pocketnode.budget", "other", "x")
        kv.set("com.pocketnode.notes", "data", "{}"); kv.set("core", "preinstalled.telegram", "1")
        assertEquals(true, kv.hasData("com.pocketnode.budget"))
        kv.deleteAll("com.pocketnode.budget")
        assertEquals(false, kv.hasData("com.pocketnode.budget"))
        assertEquals(null, kv.get("com.pocketnode.budget", "other"))
        assertEquals("{}", kv.get("com.pocketnode.notes", "data")); assertEquals("1", kv.get("core", "preinstalled.telegram"))
        val mem = InMemoryPackKv(); mem.set("a", "k", "1"); mem.set("b", "k", "2"); mem.deleteAll("a")
        assertEquals(null, mem.get("a", "k")); assertEquals("2", mem.get("b", "k"))
    }
}
