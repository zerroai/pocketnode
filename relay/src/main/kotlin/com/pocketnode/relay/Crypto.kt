package com.pocketnode.relay

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private val rng = SecureRandom()
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"

    /** URL-safe random token, [length] characters (about 6 bits each). */
    fun token(length: Int): String = buildString { repeat(length) { append(ALPHABET[rng.nextInt(ALPHABET.length)]) } }

    fun sha256Hex(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun hmacSha256Hex(key: String, data: ByteArray): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
        return mac.doFinal(data).joinToString("") { "%02x".format(it) }
    }

    /** Constant-time comparison of two strings. */
    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

/**
 * Encrypts stored message bodies with AES-256-GCM under a server key, so a copied database or
 * backup is unreadable. The server itself can read messages while it holds the key: this is
 * protection at rest, not end-to-end encryption.
 */
class Sealer(key: ByteArray) {
    private val rng = SecureRandom()
    private val keySpec = SecretKeySpec(key.also { require(it.size == 32) { "the storage key must be 32 bytes" } }, "AES")

    fun seal(plain: ByteArray, context: String): String {
        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce)); updateAAD(context.toByteArray()) }
        return Base64.getEncoder().encodeToString(nonce + c.doFinal(plain))
    }

    /** Returns null if the data was changed or belongs to a different context. */
    fun open(sealed: String, context: String): ByteArray? = try {
        val raw = Base64.getDecoder().decode(sealed)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, raw, 0, 12)); updateAAD(context.toByteArray()) }
        c.doFinal(raw, 12, raw.size - 12)
    } catch (e: Exception) {
        null
    }

    companion object {
        /** Parses a 64-character hex key from configuration. */
        fun fromHex(hex: String): Sealer = Sealer(com.pocketnode.core.Hex.decode(hex.trim()) ?: error("RELAY_STORAGE_KEY must be hex"))
    }
}
