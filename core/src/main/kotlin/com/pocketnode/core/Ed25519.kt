package com.pocketnode.core

import java.security.MessageDigest
import java.security.SecureRandom
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec

/**
 * Ed25519 on raw 32-byte keys and 64-byte signatures. Uses a pure Java implementation because the
 * platform's own Ed25519 only exists from Android 13, and we support Android 7 and up.
 */
object Ed25519 {
    private val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

    fun generateSeed(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun publicKey(seed: ByteArray): ByteArray = EdDSAPrivateKeySpec(seed, spec).a.toByteArray()

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        val key = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, spec))
        return EdDSAEngine(MessageDigest.getInstance(spec.hashAlgorithm)).run {
            initSign(key)
            update(message)
            sign()
        }
    }

    /** Returns false for any malformed input instead of throwing. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return try {
            val key = EdDSAPublicKey(EdDSAPublicKeySpec(publicKey, spec))
            EdDSAEngine(MessageDigest.getInstance(spec.hashAlgorithm)).run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        } catch (e: Exception) {
            false
        }
    }
}

object Hex {
    fun encode(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Returns null if [s] is not valid hex of an even length. */
    fun decode(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        return try { ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } } catch (e: NumberFormatException) { null }
    }
}
