package com.pocketnode.app

import android.content.Context
import java.security.SecureRandom

/**
 * The key that locks the app database. It is 32 random bytes made on this phone, kept only in sealed form
 * (the Keystore key seals it) and never written anywhere else. If the Keystore key is lost, the database cannot be read.
 */
class DbKey(context: Context) {
    private val secrets = KeystoreSecretStore(context)

    /** The key in the form SQLCipher takes for a raw key. Null when a key was saved but cannot be opened any more. Makes one if none exists. */
    fun getOrCreate(): ByteArray? {
        if (secrets.has(NAME)) return secrets.get(NAME)?.let(::form)
        val hex = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        if (!secrets.putDurably(NAME, hex)) return null
        return form(hex)
    }

    /** Forgets a key that can no longer be opened, so a new one can be made. Only for when there is no readable database to protect. */
    fun reset() = secrets.remove(NAME)

    private fun form(hex: String) = "x'$hex'".toByteArray()

    private companion object { const val NAME = "db_key" }
}
