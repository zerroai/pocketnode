package com.pocketnode.core

import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownAndErrorsTest {
    private fun plain(text: String) = MarkdownLite.parse(text).joinToString("") { it.text }

    @Test
    fun `headings, bold, italic and code lose their markers and keep their look`() {
        val s = MarkdownLite.parse("### Notes:\nThis is **very** important and *a bit* odd, use `ls -la` now.")
        assertEquals("Notes:\nThis is very important and a bit odd, use ls -la now.", s.joinToString("") { it.text })
        assertTrue(s.first().bold && s.first().text == "Notes:")
        assertTrue(s.any { it.bold && it.text == "very" })
        assertTrue(s.any { it.italic && it.text == "a bit" })
        assertTrue(s.any { it.code && it.text == "ls -la" })
    }

    @Test
    fun `bullets become dots and numbered lists stay as written`() {
        assertEquals("• one\n• two\n  • nested\n1. first", plain("- one\n* two\n  - nested\n1. first"))
        assertTrue(MarkdownLite.parse("1. **Buy rice** (ID: 1)").any { it.bold && it.text == "Buy rice" })
    }

    @Test
    fun `fenced code is kept as code and stray markers stay as typed`() {
        val s = MarkdownLite.parse("before\n```kotlin\nval a = 2 * 3 * 4\n```\nafter")
        assertEquals("before\nval a = 2 * 3 * 4\nafter", s.joinToString("") { it.text })
        assertTrue(s.any { it.code && it.text == "val a = 2 * 3 * 4" })
        assertEquals("2 * 3 = 6 and snake_case_name and a * b", plain("2 * 3 = 6 and snake_case_name and a * b"))
        assertEquals("**unclosed and `open", plain("**unclosed and `open"))
        assertEquals("", plain(""))
    }

    @Test
    fun `nested emphasis and underscores in words`() {
        val s = MarkdownLite.parse("**bold and *both* here** and _soft_ but not my_var_name")
        assertEquals("bold and both here and soft but not my_var_name", s.joinToString("") { it.text })
        assertTrue(s.any { it.bold && it.italic && it.text == "both" })
        assertTrue(s.any { it.italic && it.text == "soft" })
    }

    @Test
    fun `network and provider failures read as plain advice`() {
        assertTrue(ModelErrors.friendly(SSLException("Read error: ssl=0x79: Connection reset by peer")).contains("Could not reach the model"))
        assertTrue(ModelErrors.friendly(IOException("boom")).contains("connection"))
        assertTrue(ModelErrors.friendly(SocketTimeoutException()).contains("too long"))
        assertTrue(ModelErrors.friendly(ModelHttpException(401, "Incorrect API key")).contains("API key"))
        assertTrue(ModelErrors.friendly(ModelHttpException(429, "slow")).contains("limiting"))
        assertTrue(ModelErrors.friendly(ModelHttpException(404, "no such model")).contains("model name"))
        assertTrue(ModelErrors.friendly(ModelHttpException(503, "down")).contains("their side") || ModelErrors.friendly(ModelHttpException(503, "down")).contains("its side"))
        assertEquals("No model is connected.", ModelErrors.friendly(IllegalStateException("No model is connected.")))
        assertEquals("Something went wrong.", ModelErrors.friendly(RuntimeException()))
    }

    @Test
    fun `made file links the model invents are replaced by a pointer to the card`() {
        assertEquals("You can download it above.", MarkdownLite.hideInternalLinks("You can download it [here](sandbox:/mc-doc:3e63ea3ac7b7709c)."))
        assertEquals("I have created it. You can open it above.", MarkdownLite.hideInternalLinks("I have created it. You can open it [here](sandbox:lock-test.pdf)."))
        assertEquals("Saved as above ok", MarkdownLite.hideInternalLinks("Saved as mc-doc:3e63ea3ac7b7709c ok"))
        assertEquals("See [docs](https://example.com)", MarkdownLite.hideInternalLinks("See [docs](https://example.com)"))
    }
}
