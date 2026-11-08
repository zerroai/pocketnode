package com.pocketnode.core

/** A text file the owner attached, kept by the app and read by the model as part of the message. */
data class StoredFile(val name: String, val text: String)

/**
 * Things the owner attaches to a message. The message text carries a plain reference for each one (`mc-image:...` for a
 * photo, `mc-file:...` for a text file), so the stored conversation stays plain text. The screen turns the references into
 * thumbnails and file chips; a model client turns them into the actual picture or the file's words.
 */
object Attachments {
    private val filePattern = Regex("mc-file:[0-9a-f]{16}")

    /** Finds an attached file by reference. The app supplies a file-backed one. */
    @Volatile var fileSource: (String) -> StoredFile? = { null }

    /** At most this much of one file is shown to the model. */
    const val MAX_FILE_CHARS = 60_000

    data class Parsed(val text: String, val photos: List<String>, val files: List<String>)

    /** The words the owner typed, and the attachments, apart. */
    fun parse(text: String): Parsed {
        val photos = Images.referencesIn(text)
        val files = filePattern.findAll(text).map { it.value }.distinct().toList()
        val clean = filePattern.replace(Images.stripReferences(text), "").lines().joinToString("\n") { it.trimEnd() }.trim()
        return Parsed(clean, photos, files)
    }

    /** A line for lists and notifications, such as "Photo" or "notes.txt". */
    fun preview(text: String): String {
        val p = parse(text)
        val names = p.files.mapNotNull { fileSource(it)?.name } + List(p.photos.size) { "Photo" }
        return (listOf(p.text).filter { it.isNotBlank() } + names).joinToString(" ")
    }

    /** The message as the model should read it: each attached file's words in place of its reference. Photos are left for the client. */
    fun expandForModel(text: String): String = filePattern.replace(text) { m ->
        val f = fileSource(m.value)
        if (f == null) "(an attached file is no longer stored)"
        else "\n[Attached file: ${f.name} (reference ${m.value}; data tools take this reference)]\n${f.text.take(MAX_FILE_CHARS)}${if (f.text.length > MAX_FILE_CHARS) "\n[The rest of the file is not shown]" else ""}\n[End of file]\n"
    }
}
