package com.pocketnode.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom

/**
 * Photos on their way to the model are shrunk (longest side 1024 pixels, JPEG) and kept locked in the app's cache under a
 * random reference, so the chat only ever stores the reference. Android may clear the cache; the app then says the
 * photo is not shown again instead of failing.
 */
class PhotoFiles(private val dir: File, private val vault: com.pocketnode.core.FileVault) {
    /** Data saver: photos are kept smaller, so each message to the model carries less. */
    @Volatile var saver = false
    private val side get() = if (saver) SAVER_SIDE else MAX_SIDE

    init { dir.mkdirs() }

    fun source(ref: String): ByteArray? {
        val id = ref.removePrefix("mc-image:")
        if (id.length != 16 || !id.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return File(dir, "$id.jpg.lock").takeIf { it.isFile }?.let { vault.open(it.readBytes()) }
    }

    /** Locks photos an earlier version kept as plain files, then erases the plain ones. */
    fun lockOld(): Int {
        var n = 0
        dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".jpg") }.forEach { f ->
            try { writeWhole(File(dir, f.name + ".lock"), vault.seal(f.readBytes())); eraseFile(f); n++ }
            catch (e: Exception) { android.util.Log.e("Pocketnode", "could not lock a photo: ${e.message}") }
        }
        return n
    }

    /** Shrinks, straightens and stores [original]; returns the reference and the new size. Throws if it is not a picture. */
    fun store(original: ByteArray): Stored {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("That is not a picture.")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(original, 0, original.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("That picture could not be read.")
        val turned = rotate(decoded, orientation(original))
        val scaled = scale(turned)
        val out = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, if (saver) 55 else 72, it) }.toByteArray()
        val id = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        writeWhole(File(dir, "$id.jpg.lock"), vault.seal(out))
        val result = Stored("mc-image:$id", scaled.width, scaled.height)
        if (scaled !== decoded) decoded.recycle()
        return result
    }

    /** Erases one stored photo by its reference. */
    fun delete(ref: String) {
        val id = ref.removePrefix("mc-image:")
        if (id.length == 16 && id.all { it in '0'..'9' || it in 'a'..'f' }) eraseFile(File(dir, "$id.jpg.lock"))
    }

    fun eraseAll() { dir.listFiles()?.forEach { eraseFile(it) } }

    /** Deletes photos older than [days] days. */
    fun tidy(days: Int = 3) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { eraseFile(it) }
    }

    private fun orientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }

    private fun rotate(b: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun scale(b: Bitmap): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= side) return b
        val f = side.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    data class Stored(val ref: String, val width: Int, val height: Int)

    companion object { const val MAX_SIDE = 1024; const val SAVER_SIDE = 640 }
}
