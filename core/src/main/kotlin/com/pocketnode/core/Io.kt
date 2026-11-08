package com.pocketnode.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads at most [limit] bytes. Stands in for `InputStream.readNBytes`, which only exists from Android 13's Java level;
 * calling it on an older phone throws NoSuchMethodError and kills the thread.
 */
fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var left = limit
    while (left > 0) {
        val n = read(buffer, 0, minOf(buffer.size, left))
        if (n < 0) break
        out.write(buffer, 0, n)
        left -= n
    }
    return out.toByteArray()
}
