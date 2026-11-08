package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReportTest {
    @Test fun `the report names the error and the code but never the message`() {
        val secret = "my bank pin is 4921 and the key is sk-live-abc"
        val t = IllegalStateException(secret, RuntimeException("also $secret"))
        val text = CrashReport.format(t, "io-1", "0.1.0", "Android 10", "samsung SM-G960F", 0L)
        assertFalse("4921" in text || "sk-live" in text || "bank" in text, text)
        assertTrue("java.lang.IllegalStateException" in text && "Caused by: java.lang.RuntimeException" in text)
        assertTrue("CrashReportTest" in text, "the stack frames are there")
        assertTrue("1970-01-01 00:00:00 UTC" in text && "App version: 0.1.0" in text && "Thread: io-1" in text)
    }

    @Test fun `a very deep or looping cause chain stays short`() {
        var t: Throwable = RuntimeException("a")
        repeat(30) { t = RuntimeException("b", t) }
        val text = CrashReport.format(t, "main", "v", "a", "p", 0L)
        assertTrue(Regex("Caused by: ").findAll(text).count() <= 3)
        val self = object : RuntimeException("x") { override val cause: Throwable get() = this }
        assertTrue(CrashReport.format(self, "main", "v", "a", "p", 0L).length < 20_000)
    }
}
