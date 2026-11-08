package com.pocketnode.core

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Locks and unlocks the bytes of files the app keeps for the owner (made documents, attached files, photos).
 * AES-256-GCM, a fresh random nonce per file, and a short fixed prefix so a locked file can be told from a plain one.
 * [key] gives the 32 byte key, or null when it cannot be had; then nothing can be locked or opened.
 */
class FileVault(private val key: () -> ByteArray?) {
    /** Locks [plain]. Throws if there is no key, so a file is never saved unlocked by mistake. */
    fun seal(plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secret(), GCMParameterSpec(TAG_BITS, nonce)) }
        return MAGIC + nonce + cipher.doFinal(plain)
    }

    /** The original bytes, or null if [sealed] is not a locked file, was changed, or the key is wrong or missing. */
    fun open(sealed: ByteArray): ByteArray? {
        if (!isSealed(sealed) || sealed.size < OVERHEAD) return null
        return try {
            val nonce = sealed.copyOfRange(MAGIC.size, MAGIC.size + NONCE)
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(TAG_BITS, nonce)) }
            cipher.doFinal(sealed, MAGIC.size + NONCE, sealed.size - MAGIC.size - NONCE)
        } catch (e: Exception) { null }
    }

    private fun secret(): SecretKeySpec {
        val k = key()
        require(k != null && k.size == 32) { "The key that locks files is not available." }
        return SecretKeySpec(k, "AES")
    }

    companion object {
        private val MAGIC = "MCV1".toByteArray(Charsets.ISO_8859_1)
        private const val NONCE = 12
        private const val TAG_BITS = 128
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private val random = SecureRandom()

        /** What locking adds to a file's size: the prefix, the nonce and the check value. */
        const val OVERHEAD = 4 + NONCE + TAG_BITS / 8

        fun isSealed(bytes: ByteArray): Boolean = bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

        /** Same test from just the first bytes of a file. */
        fun looksSealed(head: ByteArray): Boolean = isSealed(head)
    }
}
