package com.pocketnode.core

/**
 * Erases what the owner made and said, and keeps how the app is set up.
 *
 * Erased: chats and their steps and runs, the Activity record, reminders, and whatever packs keep in their own store
 * (notes, to-dos). Kept: the model and its key, installed packs and what they may touch, who may use Telegram and WhatsApp,
 * paired devices, and the trial or licence. Files and photos are not in the database; the app erases those itself.
 */
class DataWipe(private val db: SqlDb) {
    class Counts(val chats: Int, val reminders: Int, val packEntries: Int)

    /** Pack store entries that are settings of the app and not the owner's content. */
    private val keep = listOf("core", "core.grants", "com.pocketnode.telegram", "com.pocketnode.whatsapp")

    /** Reminders that were waiting, so the app can cancel their alarms. Call before [erase]. */
    fun reminderIds(): List<Long> = db.transaction { tx -> tx.query("SELECT id FROM reminders") { it.long(0) } }

    fun erase(): Counts = db.transaction { tx ->
        val chats = tx.query("SELECT COUNT(*) FROM sessions") { it.long(0).toInt() }.firstOrNull() ?: 0
        val reminders = tx.query("SELECT COUNT(*) FROM reminders") { it.long(0).toInt() }.firstOrNull() ?: 0
        val marks = keep.joinToString(",") { "?" }
        val entries = tx.query("SELECT COUNT(*) FROM pack_kv WHERE pack_id NOT IN ($marks)", *keep.toTypedArray()) { it.long(0).toInt() }.firstOrNull() ?: 0
        for (t in listOf("steps", "runs", "messages", "sessions", "activity", "reminders")) tx.exec("DELETE FROM $t")
        tx.exec("DELETE FROM pack_kv WHERE pack_id NOT IN ($marks)", *keep.toTypedArray())
        Counts(chats, reminders, entries)
    }
}
