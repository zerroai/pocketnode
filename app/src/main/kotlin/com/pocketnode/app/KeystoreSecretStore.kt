package com.pocketnode.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores secrets encrypted with a hardware-backed AES key that never leaves the Android Keystore. */
class KeystoreSecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun put(name: String, value: String) {
        prefs.edit().putString(name, seal(value)).apply()
    }

    /** Like [put], but returns only once the value is on disk. For a secret that locks other data. */
    fun putDurably(name: String, value: String): Boolean = prefs.edit().putString(name, seal(value)).commit()

    fun get(name: String): String? = prefs.getString(name, null)?.let { open(it) }

    /** Encrypts [plain] with the Keystore key. The result is text that can be stored anywhere. */
    fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** Reverses [seal]. Null when the key was invalidated (for example the lock screen was removed). */
    fun open(sealedText: String): String? {
        val sealed = Base64.decode(sealedText, Base64.NO_WRAP)
        return try {
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
            }
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES).decodeToString()
        } catch (e: Exception) {
            null
        }
    }

    /** True when something is stored under [name], even if it can no longer be opened. */
    fun has(name: String): Boolean = prefs.contains(name)

    fun remove(name: String) = prefs.edit().remove(name).apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "pocketnode-secrets"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
