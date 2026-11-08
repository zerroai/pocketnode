package com.pocketnode.core

import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ed25519Test {
    private fun h(s: String) = Hex.decode(s)!!

    @Test
    fun `matches the RFC 8032 test vectors`() {
        val seed1 = h("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", Hex.encode(Ed25519.publicKey(seed1)))
        val sig1 = h("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertContentEquals(sig1, Ed25519.sign(seed1, ByteArray(0)))
        assertTrue(Ed25519.verify(Ed25519.publicKey(seed1), ByteArray(0), sig1))

        val seed2 = h("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val msg2 = h("72")
        val sig2 = h("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00")
        assertEquals("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", Hex.encode(Ed25519.publicKey(seed2)))
        assertContentEquals(sig2, Ed25519.sign(seed2, msg2))
        assertTrue(Ed25519.verify(Ed25519.publicKey(seed2), msg2, sig2))
    }

    @Test
    fun `verifies signatures made by the platform implementation`() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val msg = "from the JDK".toByteArray()
        val sig = Signature.getInstance("Ed25519").run { initSign(pair.private); update(msg); sign() }
        val rawPublic = pair.public.encoded.takeLast(32).toByteArray() // X.509 wrapper, then the 32-byte key
        assertTrue(Ed25519.verify(rawPublic, msg, sig))
    }

    @Test
    fun `rejects a changed message, a changed signature, another key and malformed input`() {
        val seed = Ed25519.generateSeed()
        val pub = Ed25519.publicKey(seed)
        val sig = Ed25519.sign(seed, "hello".toByteArray())
        assertTrue(Ed25519.verify(pub, "hello".toByteArray(), sig))
        assertFalse(Ed25519.verify(pub, "hellO".toByteArray(), sig))
        assertFalse(Ed25519.verify(pub, "hello".toByteArray(), sig.also { it[10] = (it[10] + 1).toByte() }))
        assertFalse(Ed25519.verify(Ed25519.publicKey(Ed25519.generateSeed()), "hello".toByteArray(), Ed25519.sign(seed, "hello".toByteArray())))
        assertFalse(Ed25519.verify(ByteArray(5), ByteArray(1), ByteArray(64)))
        assertFalse(Ed25519.verify(pub, ByteArray(1), ByteArray(3)))
        assertFalse(Ed25519.verify(ByteArray(32), ByteArray(1), ByteArray(64))) // not a valid curve point
    }

    @Test
    fun `hex decoding refuses bad input`() {
        assertEquals(null, Hex.decode("abc"))
        assertEquals(null, Hex.decode("zz"))
        assertContentEquals(byteArrayOf(0x0a, -1), Hex.decode("0aff"))
    }
}
