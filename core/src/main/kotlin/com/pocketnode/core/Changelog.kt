package com.pocketnode.core

/** What changed in each version, in plain words, newest first. The app shows this under Settings, About, "What's new". */
object Changelog {
    data class Entry(val version: String, val items: List<String>)

    val ENTRIES = listOf(
        Entry("0.1.0", listOf(
            "Word and PowerPoint files, next to PDF and Excel.",
            "New packs: Budget, Study cards, Clients and invoices, Safety, Library, News.",
            "Private chats that are deleted when you leave them.",
            "Delete old chats by themselves after a week, a month, three months or a year.",
            "Chats you send without a connection are tried again when it returns.",
            "Share a whole chat as text.",
            "Export and import now include what your packs saved.",
            "Remove a pack together with what it saved.",
            "Pack updates: the catalog is checked about once a day, withdrawn packs stop at once, and you can switch on automatic updates.",
            "Crash reports stay on the phone and leave out your messages.",
            "A new look for chats: rounded cards, a letter on a soft colour for each chat, and a small map in the chat when the assistant opens a place.",
        )),
    )

    /** The entry for one version, or null if none was written. */
    fun forVersion(version: String): Entry? = ENTRIES.firstOrNull { it.version == version }

    /** The text the dialog shows. */
    fun text(entries: List<Entry> = ENTRIES): String = entries.joinToString("\n\n") { e -> "Version ${e.version}\n" + e.items.joinToString("\n") { "- $it" } }
}
