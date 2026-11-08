package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileVaultTest {
    private val key = ByteArray(32) { it.toByte() }
    private val vault = FileVault { key }

    @Test fun opensWhatItSealed() {
        val plain = "Salary 2026: 1,000,000".toByteArray()
        assertContentEquals(plain, vault.open(vault.seal(plain)))
    }

    @Test fun sealedBytesDoNotContainThePlainText() {
        val plain = "very private words".toByteArray()
        val sealed = vault.seal(plain)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("private"))
        assertTrue(FileVault.isSealed(sealed))
        assertEquals(plain.size + FileVault.OVERHEAD, sealed.size)
    }

    @Test fun sameTextSealedTwiceLooksDifferent() {
        val plain = "same".toByteArray()
        assertNotEquals(vault.seal(plain).toList(), vault.seal(plain).toList())
    }

    @Test fun emptyFilesWork() {
        assertContentEquals(ByteArray(0), vault.open(vault.seal(ByteArray(0))))
    }

    @Test fun aChangedByteIsRefused() {
        val sealed = vault.seal("hello".toByteArray())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertNull(vault.open(sealed))
    }

    @Test fun aWrongKeyIsRefused() {
        val sealed = vault.seal("hello".toByteArray())
        assertNull(FileVault { ByteArray(32) { 9 } }.open(sealed))
    }

    @Test fun plainBytesAndShortBytesAreNotOpened() {
        assertNull(vault.open("just a plain file".toByteArray()))
        assertNull(vault.open(ByteArray(0)))
        assertNull(vault.open("MCV1".toByteArray()))
        assertFalse(FileVault.isSealed("%PDF-1.4".toByteArray()))
    }

    @Test fun withoutAKeyNothingIsSealedOrOpened() {
        val none = FileVault { null }
        assertFailsWith<IllegalArgumentException> { none.seal("x".toByteArray()) }
        assertNull(none.open(vault.seal("x".toByteArray())))
    }
}
