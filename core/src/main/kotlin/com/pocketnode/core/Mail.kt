package com.pocketnode.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** How a connection to a mail server is protected. [NONE] exists for tests and is never offered to the owner in a release build. */
enum class MailSecurity { SSL, STARTTLS, NONE }

/** A problem with mail, worded for the owner. */
class MailException(message: String) : Exception(message)

/** The owner's mailbox: where to send and read, and the login. The password should be an app password, kept in the Keystore. */
data class MailAccount(
    val address: String, val name: String,
    val smtpHost: String, val smtpPort: Int, val smtpSecurity: MailSecurity,
    val imapHost: String, val imapPort: Int, val imapSecurity: MailSecurity,
    val username: String, val password: String,
)

data class MailMessage(val from: String, val fromName: String, val to: List<String>, val cc: List<String> = emptyList(), val bcc: List<String> = emptyList(), val subject: String, val body: String) {
    /** The message as it goes over the wire: headers, then the body as Base64 UTF-8 text. Bcc is not written, only used for delivery. */
    fun render(now: Date = Date(), id: String = UUID.randomUUID().toString()): ByteArray {
        require(MailCodec.isAddress(from)) { "The sender address is not valid." }
        (to + cc + bcc).forEach { require(MailCodec.isAddress(it)) { "\"$it\" is not an email address." } }
        require(to.isNotEmpty()) { "Say who to send it to." }
        val fromHeader = if (fromName.isBlank()) from else MailCodec.encodeHeader(fromName) + " <$from>"
        val date = SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US).format(now)
        val head = buildString {
            append("From: $fromHeader\r\n")
            append("To: ${to.joinToString(", ")}\r\n")
            if (cc.isNotEmpty()) append("Cc: ${cc.joinToString(", ")}\r\n")
            append("Subject: ${MailCodec.encodeHeader(subject)}\r\n")
            append("Date: $date\r\n")
            append("Message-ID: <$id@${from.substringAfter('@')}>\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
        }
        return (head + MailCodec.base64Lines(body.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8)) + "\r\n").toByteArray(Charsets.ISO_8859_1)
    }
}

/** A line-based connection that can switch to TLS in the middle (STARTTLS). */
internal class MailConnection(private var socket: Socket, private val host: String, private val port: Int) : AutoCloseable {
    private var input: InputStream = socket.getInputStream().buffered()
    private var output: OutputStream = socket.getOutputStream().buffered()

    fun writeLine(line: String) { output.write((line + "\r\n").toByteArray(Charsets.ISO_8859_1)); output.flush() }
    fun write(bytes: ByteArray) { output.write(bytes); output.flush() }

    /** One line without its line break, or null at the end of the stream. */
    fun readLine(): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) { val s = buf.toString("ISO-8859-1"); return s.removeSuffix("\r") }
            buf.write(b)
        }
    }

    fun readBytes(n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) { val r = input.read(out, got, n - got); if (r < 0) throw IOException("The server closed the connection."); got += r }
        return out
    }

    /** Wraps the connection in TLS, checking that the certificate is for [host]. */
    fun startTls() {
        val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, port, true) as SSLSocket
        s.sslParameters = s.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        s.startHandshake()
        socket = s; input = s.inputStream.buffered(); output = s.outputStream.buffered()
    }

    override fun close() { runCatching { socket.close() } }

    companion object {
        fun open(host: String, port: Int, security: MailSecurity, timeoutMs: Int): MailConnection {
            try {
                val raw = Socket().apply { soTimeout = timeoutMs; connect(InetSocketAddress(host, port), timeoutMs) }
                val c = MailConnection(raw, host, port)
                if (security == MailSecurity.SSL) c.startTls()
                return c
            } catch (e: Exception) { throw explain(e, host) }
        }

        fun explain(e: Exception, host: String): MailException = when (e) {
            is MailException -> e
            is UnknownHostException -> MailException("Could not find the mail server \"$host\". Check the spelling and your connection.")
            is SocketTimeoutException -> MailException("The mail server \"$host\" did not answer in time.")
            is SSLException -> MailException("Could not make a secure connection to \"$host\". The server name or its certificate is not right.")
            else -> MailException("Could not talk to the mail server \"$host\". ${e.message.orEmpty()}".trim())
        }
    }
}

private const val BAD_LOGIN = "The email address or password has a line break in it, which is not allowed."
private const val AUTH_HELP = "The mail server did not accept the email address and password. Gmail, Outlook, Yahoo and iCloud need an app password, not your usual password."

/** Sends mail over SMTP. Opens a connection for each job and closes it. */
class SmtpClient(private val host: String, private val port: Int, private val security: MailSecurity, private val timeoutMs: Int = 20_000) {
    private class Reply(val code: Int, val lines: List<String>) { val text get() = lines.joinToString(" ") }

    private fun reply(c: MailConnection): Reply {
        val lines = mutableListOf<String>()
        while (true) {
            val l = c.readLine() ?: throw MailException("The mail server hung up.")
            lines += l.drop(4)
            if (l.length < 4 || l[3] != '-') return Reply(l.take(3).toIntOrNull() ?: 0, lines)
        }
    }

    private fun command(c: MailConnection, line: String, vararg ok: Int): Reply {
        c.writeLine(line)
        return reply(c).also { r -> if (r.code !in ok) throw failure(r, line) }
    }

    private fun failure(r: Reply, line: String): MailException = when {
        r.code == 535 || r.code == 534 || r.code == 530 && line.startsWith("MAIL") -> MailException(AUTH_HELP)
        r.code == 550 || r.code == 553 || r.code == 551 -> MailException("The mail server refused an address: ${r.text.take(120)}")
        r.code == 552 || r.code == 554 -> MailException("The mail server refused the message: ${r.text.take(120)}")
        else -> MailException("The mail server said: ${r.code} ${r.text.take(120)}")
    }

    private fun <T> session(user: String, password: String, block: (MailConnection) -> T): T {
        val c = MailConnection.open(host, port, security, timeoutMs)
        try {
            val greeting = reply(c)
            if (greeting.code != 220) throw failure(greeting, "greeting")
            var caps = command(c, "EHLO pocketnode.local", 250).lines.map { it.uppercase() }
            if (security == MailSecurity.STARTTLS) {
                if (caps.none { it.startsWith("STARTTLS") }) throw MailException("The mail server does not offer a secure connection on port $port.")
                command(c, "STARTTLS", 220)
                try { c.startTls() } catch (e: Exception) { throw MailConnection.explain(e, host) }
                caps = command(c, "EHLO pocketnode.local", 250).lines.map { it.uppercase() }
            }
            authenticate(c, caps, user, password)
            return block(c)
        } catch (e: MailException) {
            throw e
        } catch (e: Exception) {
            throw MailConnection.explain(e, host)
        } finally {
            runCatching { c.writeLine("QUIT") }
            c.close()
        }
    }

    private fun authenticate(c: MailConnection, caps: List<String>, user: String, password: String) {
        if (user.any { it == '\r' || it == '\n' } || password.any { it == '\r' || it == '\n' }) throw MailException(BAD_LOGIN)
        val methods = caps.firstOrNull { it.startsWith("AUTH ") || it.startsWith("AUTH=") }?.drop(5)?.split(' ', '=').orEmpty()
        if (methods.isEmpty() && security == MailSecurity.NONE && caps.none { it.startsWith("AUTH") }) return // a local test server may not ask
        if (methods.isEmpty() || "PLAIN" in methods) {
            val token = StdBase64.encode("\u0000$user\u0000$password".toByteArray(Charsets.UTF_8))
            c.writeLine("AUTH PLAIN $token")
            val r = reply(c)
            if (r.code == 235) return
            if (r.code != 504 && r.code != 501 && r.code != 502) throw failure(r, "AUTH")
        }
        command(c, "AUTH LOGIN", 334)
        command(c, StdBase64.encode(user.toByteArray(Charsets.UTF_8)), 334)
        command(c, StdBase64.encode(password.toByteArray(Charsets.UTF_8)), 235)
    }

    /** Connects and logs in without sending anything, so the owner can check their settings. */
    fun checkLogin(user: String, password: String) { session(user, password) { } }

    fun send(user: String, password: String, message: MailMessage) {
        val raw = message.render()
        if (raw.size > MAX_BYTES) throw MailException("That message is too large to send.")
        session(user, password) { c ->
            command(c, "MAIL FROM:<${message.from}>", 250)
            for (rcpt in (message.to + message.cc + message.bcc).distinct()) command(c, "RCPT TO:<$rcpt>", 250, 251)
            command(c, "DATA", 354)
            // Lines that start with a dot get a second one, so the message cannot end early.
            val body = String(raw, Charsets.ISO_8859_1).split("\r\n").joinToString("\r\n") { if (it.startsWith(".")) ".$it" else it }
            c.write((body.removeSuffix("\r\n") + "\r\n.\r\n").toByteArray(Charsets.ISO_8859_1))
            val r = reply(c)
            if (r.code != 250) throw failure(r, "DATA")
        }
    }

    companion object { const val MAX_BYTES = 10 * 1024 * 1024 }
}

data class MailHeader(val uid: Long, val from: String, val subject: String, val date: String, val unread: Boolean)

/** Reads mail over IMAP, read-only: it never changes a flag, moves or deletes anything. */
class ImapClient(private val host: String, private val port: Int, private val security: MailSecurity, private val timeoutMs: Int = 25_000) {
    inner class Session internal constructor(private val c: MailConnection) {
        private var n = 0

        /** Runs a command and returns every untagged response (literals included, as Latin-1 text) before the tagged OK. */
        internal fun run(command: String): List<String> {
            val tag = "a${++n}"
            c.writeLine("$tag $command")
            val untagged = mutableListOf<String>()
            while (true) {
                var line = c.readLine() ?: throw MailException("The mail server hung up.")
                if (line.startsWith("$tag ")) {
                    if (!line.substring(tag.length + 1).startsWith("OK", true)) throw MailException(if (command.startsWith("LOGIN", true)) AUTH_HELP else "The mail server said: ${line.drop(tag.length + 1).take(120)}")
                    return untagged
                }
                val whole = StringBuilder(line)
                while (true) {
                    val m = Regex("\\{(\\d+)\\}$").find(line) ?: break
                    val size = m.groupValues[1].toInt()
                    if (size > MAX_LITERAL) throw MailException("A message was too large to read.")
                    whole.append("\r\n").append(String(c.readBytes(size), Charsets.ISO_8859_1))
                    line = c.readLine() ?: break
                    whole.append(line)
                }
                untagged += whole.toString()
            }
        }

        private fun quote(s: String) = "\"" + s.replace(Regex("[\\r\\n\"\\\\]"), " ").replace(Regex("\\s+"), " ").trim() + "\""

        /** Message ids (UIDs) matching the filters, newest first. */
        fun search(unreadOnly: Boolean, from: String?, subject: String?, sinceDays: Int?, limit: Int): List<Long> {
            run("EXAMINE INBOX")
            val parts = mutableListOf<String>()
            if (unreadOnly) parts += "UNSEEN"
            from?.takeIf { it.isNotBlank() }?.let { parts += "FROM ${quote(it)}" }
            subject?.takeIf { it.isNotBlank() }?.let { parts += "SUBJECT ${quote(it)}" }
            sinceDays?.let { parts += "SINCE " + SimpleDateFormat("d-MMM-yyyy", Locale.US).format(Date(System.currentTimeMillis() - it * 86_400_000L)) }
            val found = run("UID SEARCH ${parts.joinToString(" ").ifEmpty { "ALL" }}").flatMap { Regex("\\d+").findAll(it.removePrefix("* SEARCH")).map { m -> m.value.toLong() }.toList() }
            return found.sortedDescending().take(limit)
        }

        fun headers(uids: List<Long>): List<MailHeader> {
            if (uids.isEmpty()) return emptyList()
            val responses = run("UID FETCH ${uids.joinToString(",")} (UID FLAGS BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)])")
            return responses.mapNotNull { r ->
                val uid = Regex("UID (\\d+)").find(r.substringBefore("{").ifEmpty { r })?.groupValues?.get(1)?.toLongOrNull() ?: Regex("UID (\\d+)").find(r)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
                val literalAt = Regex("\\{(\\d+)\\}\r\n").find(r)
                val block = literalAt?.let { r.substring(it.range.last + 1, minOf(r.length, it.range.last + 1 + it.groupValues[1].toInt())) }.orEmpty()
                val outside = if (literalAt == null) r else r.substring(0, literalAt.range.first) + r.substring(minOf(r.length, literalAt.range.last + 1 + literalAt.groupValues[1].toInt()))
                val flags = Regex("FLAGS \\(([^)]*)\\)").find(outside)?.groupValues?.get(1).orEmpty()
                val h = MailCodec.parseHeaders(block)
                MailHeader(uid, MailCodec.decodeHeader(h["from"].orEmpty()), MailCodec.decodeHeader(h["subject"].orEmpty()).ifBlank { "(no subject)" }, h["date"].orEmpty(), !flags.contains("\\Seen"))
            }.sortedByDescending { it.uid }
        }

        /** The raw start of one message (headers and body), or null if there is no such message. */
        fun message(uid: Long, maxBytes: Int = 60_000): ByteArray? {
            run("EXAMINE INBOX")
            val r = run("UID FETCH $uid (BODY.PEEK[]<0.$maxBytes>)").firstOrNull { it.contains("{") } ?: return null
            val m = Regex("\\{(\\d+)\\}\r\n").find(r) ?: return null
            return r.substring(m.range.last + 1, minOf(r.length, m.range.last + 1 + m.groupValues[1].toInt())).toByteArray(Charsets.ISO_8859_1)
        }
    }

    fun <T> open(user: String, password: String, block: (Session) -> T): T {
        val c = MailConnection.open(host, port, security, timeoutMs)
        try {
            val greeting = c.readLine() ?: throw MailException("The mail server hung up.")
            if (!greeting.startsWith("* OK") && !greeting.startsWith("* PREAUTH")) throw MailException("That does not look like an IMAP server: ${greeting.take(60)}")
            if (security == MailSecurity.STARTTLS) {
                val s = Session(c)
                s.run("STARTTLS")
                try { c.startTls() } catch (e: Exception) { throw MailConnection.explain(e, host) }
            }
            val s = Session(c)
            if (user.any { it == '\r' || it == '\n' } || password.any { it == '\r' || it == '\n' }) throw MailException(BAD_LOGIN)
            if (!greeting.startsWith("* PREAUTH")) s.run("LOGIN ${"\"" + user.replace("\\", "\\\\").replace("\"", "\\\"") + "\""} ${"\"" + password.replace("\\", "\\\\").replace("\"", "\\\"") + "\""}")
            return block(s)
        } catch (e: MailException) {
            throw e
        } catch (e: Exception) {
            throw MailConnection.explain(e, host)
        } finally {
            runCatching { c.writeLine("a99 LOGOUT") }
            c.close()
        }
    }

    fun checkLogin(user: String, password: String) { open(user, password) { it.run("EXAMINE INBOX") } }

    companion object { const val MAX_LITERAL = 2 * 1024 * 1024 }
}
