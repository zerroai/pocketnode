package com.pocketnode.app

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.pocketnode.core.DocumentLayout
import com.pocketnode.core.FileVault
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom

/**
 * Files the assistant made for the owner (PDFs, spreadsheets, text), kept in the app's own storage so they can be opened,
 * shared and saved from the chat or the Files screen.
 *
 * Everything on disk is locked ([FileVault]): each file is a folder `<id>/` holding `data.lock` (the bytes) and `name.lock`
 * (the readable name, locked too because a name can say a lot). A plain copy exists only briefly, in [tempDir], when the
 * owner opens, shares or saves a file.
 */
class AssetFiles(val dir: File, private val vault: FileVault, private val tempDir: File) {
    init { dir.mkdirs() }

    class Made(val ref: String, val name: String, val pages: Int)

    /** One stored file as the Files screen shows it. [size] is the size of the file itself, not of its locked form. */
    class Asset(val ref: String, val name: String, val kind: String, val mime: String, val modified: Long, val size: Long)

    companion object {
        /** The kinds of file the assistant can make, by extension. */
        val TYPES = mapOf(
            "pdf" to ("PDF document" to "application/pdf"),
            "xlsx" to ("Excel spreadsheet" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            "docx" to ("Word document" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "pptx" to ("PowerPoint presentation" to "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            "csv" to ("CSV spreadsheet" to "text/csv"),
            "txt" to ("Text file" to "text/plain"),
            "md" to ("Text file" to "text/markdown"),
        )
        fun kind(name: String): String = TYPES[name.substringAfterLast('.', "").lowercase()]?.first ?: "File"
        fun mime(name: String): String = TYPES[name.substringAfterLast('.', "").lowercase()]?.second ?: "application/octet-stream"
        private const val DATA = "data.lock"
        private const val NAME = "name.lock"
    }

    private fun newId() = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun validId(id: String) = id.length == 16 && id.all { it in '0'..'9' || it in 'a'..'f' }

    private fun folder(ref: String): File? {
        val id = ref.removePrefix("mc-doc:")
        return if (validId(id)) File(dir, id) else null
    }

    private fun store(bytes: ByteArray, name: String): Made {
        val id = newId()
        val folder = File(dir, id).apply { mkdirs() }
        try {
            writeWhole(File(folder, DATA), vault.seal(bytes))
            writeWhole(File(folder, NAME), vault.seal(name.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) { folder.deleteRecursively(); throw e }
        return Made("mc-doc:$id", name, 0)
    }

    /** Saves bytes the assistant produced under a readable name. Returns the reference to give back to the model. */
    fun put(bytes: ByteArray, filename: String?, fallback: String, extension: String): Made {
        require(extension in TYPES) { "That kind of file cannot be made." }
        return store(bytes, safeName(filename?.takeIf { it.isNotBlank() } ?: fallback, extension))
    }

    /** Every stored file, newest first. A file whose name cannot be unlocked (its key is gone) is left out. */
    fun list(): List<Asset> = dir.listFiles().orEmpty().filter { it.isDirectory && validId(it.name) }.mapNotNull { info("mc-doc:${it.name}") }.sortedByDescending { it.modified }

    /** What is known about one stored file, or null if it is not one of ours, is gone, or cannot be unlocked. */
    fun info(ref: String): Asset? {
        val d = folder(ref)?.takeIf { it.isDirectory } ?: return null
        val data = File(d, DATA).takeIf { it.isFile } ?: return null
        val name = File(d, NAME).takeIf { it.isFile }?.let { vault.open(it.readBytes()) }?.toString(Charsets.UTF_8) ?: return null
        return Asset(ref, name, kind(name), mime(name), data.lastModified(), (data.length() - FileVault.OVERHEAD).coerceAtLeast(0))
    }

    /** The unlocked bytes, for use inside the app only. */
    fun bytes(ref: String): ByteArray? {
        val d = folder(ref)?.takeIf { it.isDirectory } ?: return null
        return File(d, DATA).takeIf { it.isFile }?.let { vault.open(it.readBytes()) }
    }

    /** A plain copy for another app to read, in a folder of its own that is emptied soon after. Null if the file cannot be unlocked. */
    fun plainCopy(ref: String): File? {
        val a = info(ref) ?: return null
        val bytes = bytes(ref) ?: return null
        val folder = File(tempDir, newId()).apply { mkdirs() }
        return File(folder, a.name).also { it.writeBytes(bytes) }
    }

    /** Erases plain copies made for other apps that are older than [olderThanMillis] (0 erases all). */
    fun clearCopies(olderThanMillis: Long) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        tempDir.listFiles()?.filter { it.isDirectory && (olderThanMillis == 0L || it.lastModified() < cutoff) }?.forEach { d ->
            d.listFiles()?.forEach { eraseFile(it) }
            d.delete()
        }
    }

    fun delete(ref: String): Boolean {
        val d = folder(ref)?.takeIf { it.isDirectory } ?: return false
        d.listFiles()?.forEach { eraseFile(it) }
        return d.delete()
    }

    /** Lays out [text] and writes it as a PDF. The reference is random; the file keeps a readable name so the share sheet shows it. */
    fun create(title: String, text: String, filename: String?): Made {
        val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL); color = 0xFF111111.toInt() }
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); color = 0xFF111111.toInt() }
        val pages = DocumentLayout.layout(title, text) { t, size, isBold -> (if (isBold) bold else regular).also { it.textSize = size }.measureText(t) }
        val doc = PdfDocument()
        try {
            pages.forEachIndexed { n, page ->
                val p = doc.startPage(PdfDocument.PageInfo.Builder(DocumentLayout.PAGE_WIDTH.toInt(), DocumentLayout.PAGE_HEIGHT.toInt(), n + 1).create())
                for (l in page.lines) p.canvas.drawText(l.text, l.x, l.y, (if (l.bold) bold else regular).also { it.textSize = l.size })
                doc.finishPage(p)
            }
            val out = ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
            val made = store(out, safeName(filename?.takeIf { it.isNotBlank() } ?: title.ifBlank { "Document" }, "pdf"))
            return Made(made.ref, made.name, pages.size)
        } finally {
            doc.close()
        }
    }

    /** Moves PDFs an earlier version left in the cache into permanent storage (they are locked by [lockOld]). */
    fun adoptCached(old: File) {
        old.listFiles()?.filter { it.isDirectory && !File(dir, it.name).exists() }?.forEach { it.renameTo(File(dir, it.name)) }
    }

    /** Locks files an earlier version kept as plain files. Each is checked by unlocking it again before the plain file is erased. Returns how many were locked. */
    fun lockOld(): Int {
        var n = 0
        dir.listFiles().orEmpty().filter { it.isDirectory && validId(it.name) && !File(it, DATA).exists() }.forEach { d ->
            val plain = d.listFiles().orEmpty().firstOrNull { it.isFile && it.extension.lowercase() in TYPES } ?: return@forEach
            try {
                val bytes = plain.readBytes()
                val sealed = vault.seal(bytes)
                check(vault.open(sealed)?.contentEquals(bytes) == true) { "lock check failed" }
                File(d, DATA).also { writeWhole(it, sealed); it.setLastModified(plain.lastModified()) }
                writeWhole(File(d, NAME), vault.seal(plain.name.toByteArray(Charsets.UTF_8)))
                eraseFile(plain)
                n++
            } catch (e: Exception) {
                android.util.Log.e("Pocketnode", "could not lock ${d.name}: ${e.message}")
                File(d, DATA).delete(); File(d, NAME).delete()
            }
        }
        return n
    }

    /** Erases every stored file and every plain copy made for another app. */
    fun eraseAll() {
        dir.listFiles()?.forEach { d -> if (d.isDirectory) { d.listFiles()?.forEach { eraseFile(it) }; d.delete() } else eraseFile(d) }
        clearCopies(0)
    }

    fun tidy(days: Int = 180) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        dir.listFiles()?.filter { it.isDirectory && it.lastModified() < cutoff }?.forEach { d -> d.listFiles()?.forEach { eraseFile(it) }; d.delete() }
    }

    private fun safeName(raw: String, extension: String): String {
        val base = raw.removeSuffix(".$extension").replace(Regex("[^A-Za-z0-9 ._-]"), "").replace(Regex("\\s+"), " ").trim().trim('.').take(60).ifBlank { "Document" }
        return "$base.$extension"
    }
}

/**
 * Opening, sharing and saving a stored file. Each of these unlocks the file on purpose, because the owner asked: Open and Share
 * hand a short-lived plain copy to another app (through the FileProvider, that one file only); Save writes a plain copy to a
 * place the owner picks. Every unlock is written to the Activity record.
 */
object FileActions {
    private fun uri(context: android.content.Context, file: File) = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)

    /** Opens the file in whatever app on the phone handles its type. False if there is none, or the file cannot be unlocked. */
    fun open(context: android.content.Context, ref: String): Boolean = runCatching {
        val file = CoreHost.unlockedCopy(ref, "opened in another app") ?: return false
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri(context, file), AssetFiles.mime(file.name)).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    fun share(context: android.content.Context, ref: String): Boolean = runCatching {
        val file = CoreHost.unlockedCopy(ref, "shared") ?: return false
        val u = uri(context, file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType(AssetFiles.mime(file.name)).putExtra(android.content.Intent.EXTRA_STREAM, u).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(file.name, u)
        context.startActivity(android.content.Intent.createChooser(send, file.name).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    const val NO_APP = "No app on this phone opens this kind of file. Use Save to put a copy on your phone, or Share to send it somewhere."
}
