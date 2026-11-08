package com.pocketnode.core

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailCodecTest {
    @Test
    fun `addresses are checked and names stripped`() {
        for (ok in listOf("ada@example.com", "a.b+tag@mail.example.co.uk", "o'neil@x-y.org")) assertTrue(MailCodec.isAddress(ok), ok)
        for (bad in listOf("", "ada", "ada@", "@x.com", "ada@x", "a b@x.com", "ada@x.com\r\nBcc: z@x.com", "a@@x.com", "ada@.com")) assertTrue(!MailCodec.isAddress(bad), bad)
        assertEquals("ada@x.com", MailCodec.bareAddress("Ada Obi <ada@x.com>"))
        assertEquals("ada@x.com", MailCodec.bareAddress(" ada@x.com "))
        assertNull(MailCodec.bareAddress("Ada <nope>"))
    }

    @Test
    fun `headers with accents round trip and cannot carry extra headers`() {
        for (s in listOf("Plain subject", "Café au lait — 50% off", "日本語の件名", "x".repeat(200) + " é")) {
            val encoded = MailCodec.encodeHeader(s)
            assertTrue(encoded.lines().all { it.length <= 76 }, "short lines: $encoded")
            assertEquals(s, MailCodec.decodeHeader(encoded.replace("\r\n ", " ")))
        }
        assertEquals("a Bcc: evil@x.com", MailCodec.encodeHeader("a\r\nBcc: evil@x.com"))
    }

    @Test
    fun `encoded words from other mail programs are read, including quoted printable and other charsets`() {
        assertEquals("Café", MailCodec.decodeHeader("=?UTF-8?B?Q2Fmw6k=?="))
        assertEquals("Café", MailCodec.decodeHeader("=?iso-8859-1?Q?Caf=E9?="))
        assertEquals("Hello World", MailCodec.decodeHeader("=?utf-8?Q?Hello_World?="))
        assertEquals("AB", MailCodec.decodeHeader("=?utf-8?B?QQ==?= =?utf-8?B?Qg==?="), "space between encoded words is dropped")
        assertEquals("Re: plain", MailCodec.decodeHeader("Re: plain"))
    }

    @Test
    fun `base64 and quoted printable decode`() {
        assertEquals("hello", StdBase64.decode("aGVsbG8=").decodeToString())
        assertEquals("hello", StdBase64.decode("aGVs\r\nbG8").decodeToString(), "line breaks and missing padding")
        assertEquals("café = ok", MailCodec.quotedPrintable("caf=C3=A9 =3D ok").decodeToString())
        assertEquals("one line", MailCodec.quotedPrintable("one =\r\nline").decodeToString(), "soft break")
    }

    @Test
    fun `headers unfold and take their names in lower case`() {
        val h = MailCodec.parseHeaders("Subject: a very\r\n long subject\r\nFrom: Ada <a@x.com>\r\nX-Odd:\tvalue")
        assertEquals("a very long subject", h["subject"]); assertEquals("Ada <a@x.com>", h["from"]); assertEquals("value", h["x-odd"])
    }

    private fun text(raw: String) = MimeText.extract(raw.replace("\n", "\r\n").toByteArray(Charsets.ISO_8859_1))

    @Test
    fun `plain, encoded and html-only messages give their words`() {
        assertEquals("Hi Ada,\nSee you.", text("Subject: x\n\nHi Ada,\nSee you.").text)
        assertEquals("Café", text("Content-Type: text/plain; charset=utf-8\nContent-Transfer-Encoding: quoted-printable\n\nCaf=C3=A9").text)
        assertEquals("Café", text("Content-Type: text/plain; charset=\"iso-8859-1\"\nContent-Transfer-Encoding: quoted-printable\n\nCaf=E9").text)
        assertEquals("hello world", text("Content-Type: text/plain\nContent-Transfer-Encoding: base64\n\naGVsbG8gd29ybGQ=").text)
        assertEquals("Hello\nBold & fine", text("Content-Type: text/html\n\n<html><head><style>p{}</style></head><body><p>Hello</p><div><b>Bold</b> &amp; fine</div></body></html>").text)
    }

    @Test
    fun `multipart messages prefer plain text, skip attachments and name them`() {
        val alt = text("Content-Type: multipart/alternative; boundary=\"B1\"\n\n--B1\nContent-Type: text/plain\n\nplain words\n--B1\nContent-Type: text/html\n\n<p>html words</p>\n--B1--\n")
        assertEquals("plain words", alt.text)
        val mixed = text(
            "Content-Type: multipart/mixed; boundary=outer\n\n--outer\nContent-Type: multipart/alternative; boundary=inner\n\n--inner\nContent-Type: text/html\n\n<p>only html</p>\n--inner--\n" +
                "--outer\nContent-Type: application/pdf; name=\"report.pdf\"\nContent-Disposition: attachment; filename=\"report.pdf\"\nContent-Transfer-Encoding: base64\n\nJVBERi0=\n--outer--\n",
        )
        assertEquals("only html", mixed.text)
        assertEquals(listOf("report.pdf"), mixed.attachments)
        assertEquals("", text("Content-Type: image/png\nContent-Transfer-Encoding: base64\n\niVBORw0=").text)
        assertEquals("", text("Content-Type: multipart/mixed\n\nno boundary here").text, "a broken multipart gives nothing instead of crashing")
    }

    @Test
    fun `an outgoing message carries only the headers it should and its body decodes back`() {
        val m = MailMessage("me@x.com", "Ada Obi", listOf("you@y.com"), listOf("cc@y.com"), listOf("secret@y.com"), "Café\r\nBcc: evil@z.com", "Line one\nLine two é")
        val raw = String(m.render(Date(0), "id1"), Charsets.ISO_8859_1)
        val (head, body) = raw.split("\r\n\r\n", limit = 2)
        assertTrue(head.contains("To: you@y.com") && head.contains("Cc: cc@y.com"))
        assertTrue(!raw.contains("secret@y.com") && !head.lines().any { it.startsWith("Bcc:") }, "bcc is for delivery only")
        assertTrue(head.contains("Message-ID: <id1@x.com>") && head.contains("charset=UTF-8"))
        assertEquals("Line one\r\nLine two é", String(StdBase64.decode(body), Charsets.UTF_8).trimEnd())
        assertEquals("Café Bcc: evil@z.com", MailCodec.decodeHeader(MailCodec.parseHeaders(head)["subject"]!!.let { it }))
        assertFailsWith<IllegalArgumentException> { MailMessage("me@x.com", "", listOf("not an address"), subject = "s", body = "b").render() }
        assertFailsWith<IllegalArgumentException> { MailMessage("me@x.com", "", emptyList(), subject = "s", body = "b").render() }
    }
}

/** A tiny SMTP server for tests: records what it was told and answers from a script. */
private class FakeSmtp(private val password: String = "secret", private val refuseRcpt: String? = null, private val advertiseAuth: String = "PLAIN LOGIN") : AutoCloseable {
    val server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
    val log = CopyOnWriteArrayList<String>()
    val data = CopyOnWriteArrayList<String>()
    val port get() = server.localPort
    init { Thread { while (!server.isClosed) { val s = try { server.accept() } catch (e: Exception) { return@Thread }; Thread { serve(s) }.start() } }.also { it.isDaemon = true }.start() }

    private fun serve(s: Socket) {
        val r = s.getInputStream().bufferedReader(Charsets.ISO_8859_1); val w = s.getOutputStream()
        fun say(t: String) { w.write((t + "\r\n").toByteArray()); w.flush() }
        say("220 fake ready")
        var loginStep = 0
        while (true) {
            val line = r.readLine() ?: break
            log += line
            when {
                loginStep == 1 -> { loginStep = 2; say("334 UGFzc3dvcmQ6") }
                loginStep == 2 -> { loginStep = 0; if (String(StdBase64.decode(line)) == password) say("235 ok") else say("535 5.7.8 bad credentials") }
                line.startsWith("EHLO") -> { w.write("250-fake\r\n250-SIZE 1000\r\n250 AUTH $advertiseAuth\r\n".toByteArray()); w.flush() }
                line.startsWith("AUTH PLAIN") -> { val parts = String(StdBase64.decode(line.removePrefix("AUTH PLAIN "))).split('\u0000'); if (parts.getOrNull(2) == password) say("235 ok") else say("535 5.7.8 bad credentials") }
                line == "AUTH LOGIN" -> { loginStep = 1; say("334 VXNlcm5hbWU6") }
                line.startsWith("MAIL FROM") -> say("250 ok")
                line.startsWith("RCPT TO") -> if (refuseRcpt != null && line.contains(refuseRcpt)) say("550 no such user") else say("250 ok")
                line == "DATA" -> {
                    say("354 go")
                    val sb = StringBuilder()
                    while (true) { val l = r.readLine() ?: break; if (l == ".") break; sb.append(if (l.startsWith("..")) l.drop(1) else l).append("\r\n") }
                    data += sb.toString(); say("250 queued")
                }
                line == "QUIT" -> { say("221 bye"); break }
                else -> say("502 unknown")
            }
        }
        s.close()
    }
    override fun close() { server.close() }

    /** The server logs a line on its own thread, a moment after the client has written it, so a test waits for it instead of racing. */
    fun awaitLogged(line: String) { repeat(300) { if (line in log) return; Thread.sleep(10) } }
}

class SmtpTest {
    private val servers = mutableListOf<AutoCloseable>()
    @AfterTest fun stop() = servers.forEach { it.close() }
    private fun smtp(s: FakeSmtp) = SmtpClient("127.0.0.1", s.port, MailSecurity.NONE, 5_000).also { servers += s }
    private val mail = MailMessage("me@x.com", "Me", listOf("a@y.com", "b@y.com"), listOf("c@y.com"), listOf("d@y.com"), "Hello", "Body text")

    @Test
    fun `a message is delivered to every recipient and arrives intact`() {
        val s = FakeSmtp(); smtp(s).send("me@x.com", "secret", mail)
        s.awaitLogged("QUIT")
        val rcpts = s.log.filter { it.startsWith("RCPT TO") }
        assertEquals(listOf("RCPT TO:<a@y.com>", "RCPT TO:<b@y.com>", "RCPT TO:<c@y.com>", "RCPT TO:<d@y.com>"), rcpts)
        assertTrue(s.log.contains("MAIL FROM:<me@x.com>") && s.log.last() == "QUIT")
        val sent = s.data.single()
        assertTrue(sent.contains("Subject: Hello") && !sent.contains("d@y.com"))
        val body = sent.substringAfter("\r\n\r\n")
        assertEquals("Body text", String(StdBase64.decode(body)).trim())
    }

    @Test
    fun `a wrong password gives advice about app passwords, and the server never sees the message`() {
        val s = FakeSmtp(password = "right")
        val e = assertFailsWith<MailException> { smtp(s).send("me@x.com", "wrong", mail) }
        assertTrue(e.message!!.contains("app password"))
        assertTrue(s.data.isEmpty())
    }

    @Test
    fun `login works with servers that only offer the older LOGIN method`() {
        val s = FakeSmtp(advertiseAuth = "LOGIN"); smtp(s).send("me@x.com", "secret", mail)
        assertTrue(s.log.contains("AUTH LOGIN") && s.data.size == 1)
    }

    @Test
    fun `a refused recipient is reported and nothing is sent`() {
        val s = FakeSmtp(refuseRcpt = "b@y.com")
        val e = assertFailsWith<MailException> { smtp(s).send("me@x.com", "secret", mail) }
        assertTrue(e.message!!.contains("refused an address"))
        assertTrue(s.data.isEmpty())
    }

    @Test
    fun `the login can be checked without sending`() {
        val s = FakeSmtp(); smtp(s).checkLogin("me@x.com", "secret")
        assertTrue(s.data.isEmpty() && s.log.none { it.startsWith("MAIL FROM") })
        assertFailsWith<MailException> { smtp(FakeSmtp()).checkLogin("me@x.com", "nope") }
    }

    @Test
    fun `an address that is not there, and a login with line breaks, are refused before anything is sent`() {
        assertFailsWith<MailException> { SmtpClient("127.0.0.1", 1, MailSecurity.NONE, 500).checkLogin("a", "b") }
        val s = FakeSmtp(); servers += s
        val e = assertFailsWith<MailException> { SmtpClient("127.0.0.1", s.port, MailSecurity.NONE, 2000).checkLogin("me\r\nMAIL FROM:<x@y.com>", "p") }
        assertTrue(e.message!!.contains("line break"))
        assertTrue(s.log.none { it.startsWith("MAIL FROM") }, "the injected command never reached the server")
    }
}

/** A tiny IMAP server for tests with a few canned messages. */
private class FakeImap(private val password: String = "secret") : AutoCloseable {
    val server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
    val log = CopyOnWriteArrayList<String>()
    val port get() = server.localPort
    private class Msg(val uid: Int, val seen: Boolean, val from: String, val subject: String, val raw: String)
    private val msgs = listOf(
        Msg(11, true, "Ada Obi <ada@x.com>", "Old news", "From: Ada Obi <ada@x.com>\r\nSubject: Old news\r\nDate: Mon, 12 Oct 2026 09:00:00 +0100\r\n\r\nolder body"),
        Msg(12, false, "=?UTF-8?B?Q2Fmw6k=?= <cafe@x.com>", "=?UTF-8?B?UXVhcnRlcmx5IHJlcG9ydCDigJMgZHJhZnQ=?=", "From: =?UTF-8?B?Q2Fmw6k=?= <cafe@x.com>\r\nSubject: =?UTF-8?B?UXVhcnRlcmx5IHJlcG9ydCDigJMgZHJhZnQ=?=\r\nDate: Wed, 14 Oct 2026 10:30:00 +0100\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\nPlease review caf=C3=A9 numbers."),
        Msg(13, false, "Bo <bo@y.com>", "Lunch?", "From: Bo <bo@y.com>\r\nSubject: Lunch?\r\nDate: Thu, 15 Oct 2026 08:00:00 +0100\r\n\r\nFree at 1?"),
    )
    init { Thread { while (!server.isClosed) { val s = try { server.accept() } catch (e: Exception) { return@Thread }; Thread { serve(s) }.start() } }.also { it.isDaemon = true }.start() }

    private fun serve(s: Socket) {
        val r = s.getInputStream().bufferedReader(Charsets.ISO_8859_1); val w = s.getOutputStream()
        fun out(t: String) { w.write(t.toByteArray(Charsets.ISO_8859_1)); w.flush() }
        out("* OK fake imap ready\r\n")
        while (true) {
            val line = r.readLine() ?: break
            log += line
            val tag = line.substringBefore(' '); val cmd = line.substringAfter(' ')
            when {
                cmd.startsWith("LOGIN") -> if (cmd.contains("\"$password\"")) out("$tag OK logged in\r\n") else out("$tag NO [AUTHENTICATIONFAILED] invalid credentials\r\n")
                cmd.startsWith("EXAMINE") -> out("* 3 EXISTS\r\n$tag OK [READ-ONLY] done\r\n")
                cmd.startsWith("UID SEARCH") -> {
                    val unseen = cmd.contains("UNSEEN"); val from = Regex("FROM \"([^\"]*)\"").find(cmd)?.groupValues?.get(1)
                    val ids = msgs.filter { (!unseen || !it.seen) && (from == null || it.from.contains(from, true)) }.map { it.uid }
                    out("* SEARCH ${ids.joinToString(" ")}\r\n$tag OK search done\r\n")
                }
                cmd.startsWith("UID FETCH") && cmd.contains("HEADER.FIELDS") -> {
                    val ids = cmd.substringAfter("UID FETCH ").substringBefore(' ').split(',').map { it.toInt() }
                    for (m in msgs.filter { it.uid in ids }) {
                        val hdr = m.raw.substringBefore("\r\n\r\n") + "\r\n\r\n"
                        out("* ${m.uid} FETCH (UID ${m.uid} FLAGS (${if (m.seen) "\\Seen" else ""}) BODY[HEADER.FIELDS (FROM SUBJECT DATE)] {${hdr.length}}\r\n$hdr)\r\n")
                    }
                    out("$tag OK fetch done\r\n")
                }
                cmd.startsWith("UID FETCH") && cmd.contains("BODY.PEEK[]") -> {
                    val id = cmd.substringAfter("UID FETCH ").substringBefore(' ').toInt(); val m = msgs.firstOrNull { it.uid == id }
                    if (m != null) out("* ${m.uid} FETCH (UID ${m.uid} BODY[]<0> {${m.raw.length}}\r\n${m.raw})\r\n")
                    out("$tag OK fetch done\r\n")
                }
                cmd.startsWith("LOGOUT") -> { out("* BYE\r\n$tag OK bye\r\n"); break }
                else -> out("$tag BAD unknown\r\n")
            }
        }
        s.close()
    }
    override fun close() { server.close() }
}

class ImapTest {
    private val servers = mutableListOf<AutoCloseable>()
    @AfterTest fun stop() = servers.forEach { it.close() }
    private fun client(s: FakeImap) = ImapClient("127.0.0.1", s.port, MailSecurity.NONE, 5_000).also { servers += s }

    @Test
    fun `the inbox lists the newest first with readable names and unread flags`() {
        val s = FakeImap()
        val list = client(s).open("me@x.com", "secret") { it.headers(it.search(false, null, null, null, 10)) }
        assertEquals(listOf(13L, 12L, 11L), list.map { it.uid })
        val report = list.first { it.uid == 12L }
        assertEquals("Quarterly report – draft", report.subject)
        assertEquals("Café <cafe@x.com>", report.from)
        assertEquals(true, report.unread); assertEquals(false, list.first { it.uid == 11L }.unread)
    }

    @Test
    fun `filters reach the server, and a limit keeps only the newest`() {
        val s = FakeImap()
        val c = client(s)
        assertEquals(listOf(13L, 12L), c.open("me@x.com", "secret") { it.search(true, null, null, null, 10) })
        assertEquals(listOf(11L), c.open("me@x.com", "secret") { it.search(false, "ada", null, null, 10) })
        assertEquals(listOf(13L), c.open("me@x.com", "secret") { it.search(false, null, null, null, 1) })
        assertTrue(s.log.any { it.contains("UID SEARCH ALL") } && s.log.any { it.contains("UID SEARCH UNSEEN") && !it.contains("SINCE") })
        assertTrue(s.log.any { it.contains("FROM \"ada\"") })
    }

    @Test
    fun `a message is read as text and the mailbox is only ever examined, never changed`() {
        val s = FakeImap()
        val raw = client(s).open("me@x.com", "secret") { it.message(12) }!!
        assertEquals("Please review café numbers.", MimeText.extract(raw).text)
        assertNull(client(s).open("me@x.com", "secret") { it.message(999) })
        assertTrue(s.log.none { it.contains("SELECT") || it.contains("STORE") || it.contains("EXPUNGE") || it.contains("DELETE") }, s.log.toString())
        assertTrue(s.log.any { it.contains("EXAMINE INBOX") } && s.log.any { it.contains("BODY.PEEK") })
    }

    @Test
    fun `search words cannot break out of their quotes, and a wrong password says so`() {
        val s = FakeImap()
        client(s).open("me@x.com", "secret") { it.search(false, "x\" OR ALL \"y", null, null, 5) }
        assertTrue(s.log.any { it.contains("FROM \"x OR ALL y\"") }, s.log.toString())
        val e = assertFailsWith<MailException> { client(FakeImap("right")).open("me@x.com", "wrong") { } }
        assertTrue(e.message!!.contains("app password"))
        assertFailsWith<MailException> { ImapClient("127.0.0.1", 1, MailSecurity.NONE, 500).checkLogin("a", "b") }
    }
}
