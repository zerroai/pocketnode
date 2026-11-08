package com.pocketnode.app

import android.content.Context
import com.pocketnode.core.Hex
import java.security.SecureRandom

/**
 * The key that locks the files the app keeps (made documents, attached files, photos). Kept apart from the database key.
 * 32 random bytes made on this phone, stored only sealed by the Keystore. If the Keystore key is ever lost, a new key is made
 * and files locked with the old one can no longer be opened.
 */
class FileKey(context: Context) {
    private val secrets = KeystoreSecretStore(context)
    @Volatile private var cached: ByteArray? = null

    fun get(): ByteArray? = cached ?: synchronized(this) { cached ?: load().also { cached = it } }

    private fun load(): ByteArray? {
        if (secrets.has(NAME)) {
            secrets.get(NAME)?.let { Hex.decode(it) }?.takeIf { it.size == 32 }?.let { return it }
            secrets.remove(NAME)
        }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return if (secrets.putDurably(NAME, fresh.joinToString("") { "%02x".format(it) })) fresh else null
    }

    private companion object { const val NAME = "file_key" }
}

/** Overwrites a file with zeros and deletes it, so a plain copy is not left in free space. Best effort on flash storage. */
internal fun eraseFile(file: java.io.File) {
    if (!file.exists()) return
    try {
        java.io.RandomAccessFile(file, "rw").use { f ->
            val zeros = ByteArray(8192); var left = f.length()
            f.seek(0)
            while (left > 0) { val n = minOf(left, zeros.size.toLong()).toInt(); f.write(zeros, 0, n); left -= n }
            f.fd.sync()
        }
    } catch (e: Exception) { android.util.Log.w("Pocketnode", "could not overwrite ${file.name}: ${e.message}") }
    file.delete()
}

/** Writes [bytes] to [target] by way of a temporary name, so a crash never leaves half a file under the real name. */
internal fun writeWhole(target: java.io.File, bytes: ByteArray) {
    val tmp = java.io.File(target.path + ".tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(target)) { tmp.delete(); throw java.io.IOException("could not save ${target.name}") }
}
