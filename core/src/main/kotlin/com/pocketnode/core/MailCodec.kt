package com.pocketnode.core

import java.nio.charset.Charset

/** The parts of email's formats (RFC 5322, MIME, RFC 2047) this app needs, written once and tested on their own. */
object MailCodec {
    private val addressPattern = Regex("^[A-Za-z0-9._%+'\\-]+@[A-Za-z0-9](?:[A-Za-z0-9\\-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9\\-]*[A-Za-z0-9])?)+$")

    fun isAddress(text: String): Boolean = text.length <= 254 && addressPattern.matches(text)

    /** Takes "Ada <ada@x.com>" or "ada@x.com" and returns the bare address, or null if it is not one. */
    fun bareAddress(text: String): String? {
        val t = text.trim()
        val inside = Regex("<([^<>]+)>").find(t)?.groupValues?.get(1)?.trim() ?: t
        return inside.takeIf { isAddress(it) }
    }

    /** A header value with no line breaks, so a name or subject can never add headers of its own. */
    fun cleanHeader(v: String): String = v.replace(Regex("[\\r\\n]+"), " ").trim()

    /** Encodes a header value that has non-ASCII letters as RFC 2047 UTF-8 words; plain text is left alone. */
    fun encodeHeader(v: String): String {
        val clean = cleanHeader(v)
        if (clean.all { it.code in 32..126 }) return clean
        // Each word is at most 75 characters; split on character boundaries so no letter is cut in half.
        val words = mutableListOf<String>()
        var chunk = StringBuilder()
        fun flush() { if (chunk.isNotEmpty()) { words += "=?UTF-8?B?" + StdBase64.encode(chunk.toString().toByteArray(Charsets.UTF_8)) + "?="; chunk = StringBuilder() } }
        for (cp in clean.codePoints().toArray()) {
            val piece = String(Character.toChars(cp))
            if ((chunk.toString() + piece).toByteArray(Charsets.UTF_8).size > 42) flush()
            chunk.append(piece)
        }
        flush()
        return words.joinToString("\r\n ")
    }

    /** Reads RFC 2047 encoded words (`=?utf-8?B?...?=`, `=?iso-8859-1?Q?...?=`) back into text. */
    fun decodeHeader(v: String): String {
        val word = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=")
        // Spaces between two encoded words are not part of the text.
        val joined = Regex("(=\\?[^?]+\\?[bBqQ]\\?[^?]*\\?=)\\s+(?==\\?[^?]+\\?[bBqQ]\\?)").replace(v, "$1")
        return word.replace(joined) { m ->
            val cs = charset(m.groupValues[1])
            val bytes = if (m.groupValues[2].equals("b", true)) StdBase64.decode(m.groupValues[3]) else quotedPrintable(m.groupValues[3].replace('_', ' '), header = true)
            String(bytes, cs)
        }
    }

    fun charset(name: String?): Charset = try { Charset.forName((name ?: "utf-8").trim('"', ' ')) } catch (e: Exception) { Charsets.UTF_8 }

    /** Decodes quoted-printable text (`=C3=A9`, soft line breaks `=\r\n`) to bytes. */
    fun quotedPrintable(text: String, header: Boolean = false): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '=' && i + 1 < text.length && text[i + 1] == '\r' && i + 2 < text.length && text[i + 2] == '\n') { i += 3; continue }
            if (c == '=' && i + 1 < text.length && text[i + 1] == '\n') { i += 2; continue }
            if (c == '=' && i + 2 < text.length) {
                val hex = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) { out.write(hex); i += 3; continue }
            }
            if (c.code < 128) out.write(c.code) else out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toByteArray()
    }

    /** The message body as lines of at most 76 Base64 characters, ready for `Content-Transfer-Encoding: base64`. */
    fun base64Lines(bytes: ByteArray): String = StdBase64.encode(bytes).chunked(76).joinToString("\r\n")

    /** A header block split off a message: names lowercased, folded lines joined. */
    fun parseHeaders(block: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var name: String? = null
        for (line in block.split("\r\n", "\n")) {
            if (line.isEmpty()) continue
            if ((line.startsWith(" ") || line.startsWith("\t")) && name != null) { out[name] = out[name] + " " + line.trim(); continue }
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            name = line.substring(0, colon).trim().lowercase()
            out[name] = line.substring(colon + 1).trim()
        }
        return out
    }
}

/** What the owner needs from a received message: the words, and the names of anything attached. */
data class ParsedMail(val text: String, val attachments: List<String>)

/** Finds the readable text in a raw message, however it is wrapped (plain, HTML only, multipart, nested, encoded). */
object MimeText {
    private const val MAX_DEPTH = 6

    fun extract(raw: ByteArray): ParsedMail {
        val attachments = mutableListOf<String>()
        val text = walk(String(raw, Charsets.ISO_8859_1), attachments, 0).trim()
        return ParsedMail(text.replace("\r\n", "\n").replace(Regex("\\n{3,}"), "\n\n"), attachments)
    }

    private fun split(entity: String): Pair<String, String> {
        val i = entity.indexOf("\r\n\r\n").takeIf { it >= 0 }?.let { it to 4 } ?: entity.indexOf("\n\n").takeIf { it >= 0 }?.let { it to 2 } ?: return entity to ""
        return entity.substring(0, i.first) to entity.substring(i.first + i.second)
    }

    private fun param(value: String, key: String): String? =
        Regex("(?i)(?:^|;)\\s*$key\\s*=\\s*(\"([^\"]*)\"|[^;\\s]+)").find(value)?.let { it.groupValues[2].ifEmpty { it.groupValues[1] }.trim('"') }

    private fun walk(entity: String, attachments: MutableList<String>, depth: Int): String {
        val (head, body) = split(entity)
        val h = MailCodec.parseHeaders(head)
        val type = (h["content-type"] ?: "text/plain").substringBefore(';').trim().lowercase()
        val disposition = h["content-disposition"].orEmpty()
        val filename = param(disposition, "filename") ?: param(h["content-type"].orEmpty(), "name")
        if (filename != null && (disposition.startsWith("attachment", true) || !type.startsWith("text/"))) { attachments += MailCodec.decodeHeader(filename); return "" }
        if (type.startsWith("multipart/") && depth < MAX_DEPTH) {
            val boundary = param(h["content-type"].orEmpty(), "boundary") ?: return ""
            val parts = body.split(Regex("(?m)^--" + Regex.escape(boundary) + "(--)?[ \\t]*\\r?$")).drop(1).map { it.trimStart('\r', '\n') }.filter { it.isNotBlank() }
            val texts = parts.map { type2(it) to walk(it, attachments, depth + 1) }
            return texts.firstOrNull { it.first == "text/plain" && it.second.isNotBlank() }?.second
                ?: texts.firstOrNull { it.second.isNotBlank() }?.second.orEmpty()
        }
        if (type != "text/plain" && type != "text/html") return ""
        val enc = (h["content-transfer-encoding"] ?: "7bit").trim().lowercase()
        val bytes = when (enc) {
            "base64" -> StdBase64.decode(body)
            "quoted-printable" -> MailCodec.quotedPrintable(body)
            else -> body.toByteArray(Charsets.ISO_8859_1)
        }
        val s = String(bytes, MailCodec.charset(param(h["content-type"].orEmpty(), "charset")))
        return if (type == "text/html") htmlToText(s) else s
    }

    private fun type2(part: String): String = (MailCodec.parseHeaders(split(part).first)["content-type"] ?: "text/plain").substringBefore(';').trim().lowercase()

    fun htmlToText(html: String): String {
        var t = html.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
        t = t.replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</h[1-6]>|</li>"), "\n")
        t = t.replace(Regex("<[^>]*>"), "")
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&rsquo;", "'")
        return t.lines().joinToString("\n") { it.trim().replace(Regex("[ \\t]+"), " ") }.replace(Regex("\\n{3,}"), "\n\n").trim()
    }
}
