package com.pocketnode.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.pocketnode.core.StoredFile
import com.pocketnode.core.readUpTo
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom

/** Text files the owner attached, kept locked in the app's cache under a random reference. */
class AttachmentFiles(private val dir: File, private val vault: com.pocketnode.core.FileVault) {
    init { dir.mkdirs() }

    fun store(name: String, text: String): String {
        val id = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        writeWhole(File(dir, "$id.txt.lock"), vault.seal(text.toByteArray(Charsets.UTF_8)))
        writeWhole(File(dir, "$id.name.lock"), vault.seal(name.toByteArray(Charsets.UTF_8)))
        return "mc-file:$id"
    }

    fun source(ref: String): StoredFile? {
        val id = ref.removePrefix("mc-file:")
        if (id.length != 16 || !id.all { it in '0'..'9' || it in 'a'..'f' }) return null
        val text = File(dir, "$id.txt.lock").takeIf { it.isFile }?.let { vault.open(it.readBytes()) }?.toString(Charsets.UTF_8) ?: return null
        val name = File(dir, "$id.name.lock").takeIf { it.isFile }?.let { vault.open(it.readBytes()) }?.toString(Charsets.UTF_8).orEmpty()
        return StoredFile(name.ifBlank { "file" }, text)
    }

    /** Locks files an earlier version kept as plain text, then erases the plain ones. */
    fun lockOld(): Int {
        var n = 0
        dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".txt") }.forEach { txt ->
            val id = txt.name.removeSuffix(".txt")
            try {
                writeWhole(File(dir, "$id.txt.lock"), vault.seal(txt.readBytes()))
                File(dir, "$id.name").takeIf { it.isFile }?.let { writeWhole(File(dir, "$id.name.lock"), vault.seal(it.readBytes())); eraseFile(it) }
                eraseFile(txt)
                n++
            } catch (e: Exception) { android.util.Log.e("Pocketnode", "could not lock an attached file: ${e.message}") }
        }
        return n
    }

    /** Erases one attached file by its reference. */
    fun delete(ref: String) {
        val id = ref.removePrefix("mc-file:")
        if (id.length == 16 && id.all { it in '0'..'9' || it in 'a'..'f' }) { eraseFile(File(dir, "$id.txt.lock")); eraseFile(File(dir, "$id.name.lock")) }
    }

    fun eraseAll() { dir.listFiles()?.forEach { eraseFile(it) } }

    fun tidy(days: Int = 7) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { eraseFile(it) }
    }
}

/** What the owner attached, ready to show as a chip or thumbnail and to send with their message. */
data class Attached(val ref: String, val label: String, val photo: Boolean)

/**
 * Turns a picked file into something the assistant can use: photos are shrunk, PDFs become pictures of their first pages
 * (the model reads those like any photo), and text files are read as words. Anything else is refused with a plain reason.
 */
class FileIntake(private val app: Context, private val photos: PhotoFiles, private val files: AttachmentFiles) {
    class Result(val items: List<Attached>, val note: String?)

    fun intake(uri: Uri): Result {
        val (name, size) = describe(uri)
        val mime = app.contentResolver.getType(uri).orEmpty()
        if (size > MAX_BYTES) throw IllegalArgumentException("\"$name\" is too big. The limit is ${MAX_BYTES / 1_000_000} MB.")
        return when {
            mime.startsWith("image/") -> Result(listOf(Attached(photos.store(readBytes(uri)).ref, name, true)), null)
            mime == "application/pdf" || name.endsWith(".pdf", true) -> pdf(uri, name)
            isText(mime, name) -> {
                if (size > MAX_TEXT_BYTES) throw IllegalArgumentException("\"$name\" is too long to read. Text files can be up to ${MAX_TEXT_BYTES / 1000} KB.")
                val text = readBytes(uri).decodeToString()
                if (text.isBlank()) throw IllegalArgumentException("\"$name\" is empty.")
                Result(listOf(Attached(files.store(name, text), name, false)), null)
            }
            else -> throw IllegalArgumentException("Pocketnode can read photos, PDFs and text files. \"$name\" is a different kind of file.")
        }
    }

    private fun pdf(uri: Uri, name: String): Result {
        val pfd = app.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalArgumentException("\"$name\" could not be opened.")
        val out = mutableListOf<Attached>()
        var total = 0
        try {
            PdfRenderer(pfd).use { r ->
                total = r.pageCount
                if (total == 0) throw IllegalArgumentException("\"$name\" has no pages.")
                for (i in 0 until minOf(total, MAX_PDF_PAGES)) {
                    r.openPage(i).use { page ->
                        val width = 1100
                        val height = (page.height * (width.toFloat() / page.width)).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE) }
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
                        bmp.recycle()
                        out += Attached(photos.store(jpeg).ref, if (total > 1) "$name, page ${i + 1}" else name, true)
                    }
                }
            }
        } catch (e: SecurityException) {
            throw IllegalArgumentException("\"$name\" is protected and cannot be read.")
        } catch (e: java.io.IOException) {
            throw IllegalArgumentException("\"$name\" could not be read as a PDF.")
        } finally {
            runCatching { pfd.close() }
        }
        return Result(out, if (total > MAX_PDF_PAGES) "Only the first $MAX_PDF_PAGES of $total pages were attached." else null)
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) }
            }
        }
        return name to size
    }

    private fun readBytes(uri: Uri): ByteArray {
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readUpTo(MAX_BYTES.toInt() + 1) } ?: throw IllegalArgumentException("That file could not be opened.")
        if (bytes.size > MAX_BYTES) throw IllegalArgumentException("That file is too big. The limit is ${MAX_BYTES / 1_000_000} MB.")
        return bytes
    }

    private fun isText(mime: String, name: String): Boolean {
        if (mime.startsWith("text/") || mime in TEXT_MIMES) return true
        return name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS
    }

    companion object {
        const val MAX_BYTES = 25_000_000L
        const val MAX_TEXT_BYTES = 300_000L
        const val MAX_PDF_PAGES = 3
        private val TEXT_MIMES = setOf("application/json", "application/xml", "application/x-yaml", "application/javascript", "application/x-sh", "application/csv")
        private val TEXT_EXTENSIONS = setOf("txt", "md", "csv", "json", "xml", "log", "yaml", "yml", "ini", "html", "css", "js", "ts", "kt", "java", "py", "sh", "sql", "tsv", "toml", "rtf")
        /** What the file picker offers. */
        val PICKER_TYPES = arrayOf("image/*", "application/pdf", "text/*", "application/json", "application/xml")
    }
}
