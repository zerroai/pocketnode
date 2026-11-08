package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals

class IoTest {
    @Test
    fun `reads up to the limit and no further`() {
        val data = ByteArray(20_000) { (it % 251).toByte() }
        assertEquals(20_000, data.inputStream().readUpTo(100_000).size)
        assertEquals(1_001, data.inputStream().readUpTo(1_001).size)
        assertEquals(0, data.inputStream().readUpTo(0).size)
        assertEquals(0, ByteArray(0).inputStream().readUpTo(10).size)
        assertEquals(data.toList().take(9000), data.inputStream().readUpTo(9000).toList())
    }
}
