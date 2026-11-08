package com.pocketnode.core

/**
 * Pictures for the model. A phone operation that produces a photo puts a reference like `mc-image:0123456789abcdef` in its
 * result text, so the result stays plain text (it is stored, replayed and crash-resumed like any other). When a message goes
 * to a model, the client swaps each reference for the actual picture in the format that model's API wants.
 */
object Images {
    private val pattern = Regex("mc-image:[0-9a-f]{16}")

    /** Finds the JPEG bytes for a reference. The app supplies a file-backed one; the default has nothing. */
    @Volatile var source: (String) -> ByteArray? = { null }

    /** At most this many of the newest photos go with each request, so a long chat does not resend every picture. */
    const val MAX_PER_REQUEST = 3

    fun referencesIn(text: String): List<String> = pattern.findAll(text).map { it.value }.distinct().toList()

    fun stripReferences(text: String): String = pattern.replace(text, "")

    /**
     * The photos to attach to this request, keyed by the index of the message they belong to (a tool result, or a message the
     * owner wrote with photos attached). Newest photos win; older ones are left out and their reference is replaced by a note.
     */
    fun attachments(messages: List<Msg>): Map<Int, List<String>> {
        val picked = linkedMapOf<Int, MutableList<String>>()
        var budget = MAX_PER_REQUEST
        for (i in messages.indices.reversed()) {
            val text = when (val m = messages[i]) { is Msg.ToolResult -> m.text; is Msg.User -> m.text; else -> continue }
            for (ref in referencesIn(text).reversed()) {
                if (budget == 0) break
                if (source(ref) == null) continue
                picked.getOrPut(i) { mutableListOf() }.add(0, ref)
                budget--
            }
        }
        return picked
    }

    /** The result text as the model should read it: references replaced by what happened to the picture. */
    fun readable(text: String, attached: List<String>): String =
        pattern.replace(text) { if (it.value in attached) "(photo ${attached.indexOf(it.value) + 1} is attached)" else "(this photo is not shown again)" }

    fun base64(ref: String): String? = source(ref)?.let { StdBase64.encode(it) }
}

/** Standard Base64 (with padding). `java.util.Base64` is missing before Android 8, which this app still supports. */
object StdBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val index = IntArray(128) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

    /** Decodes Base64, ignoring spaces and line breaks and tolerating missing padding. Anything else that is not Base64 is skipped. */
    fun decode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (ch in text) {
            if (ch == '=') break
            val v = if (ch.code < 128) index[ch.code] else -1
            if (v < 0) continue
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) { bits -= 8; out.write((acc shr bits) and 0xff) }
        }
        return out.toByteArray()
    }

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            sb.append(ALPHABET[b0 shr 2]).append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < bytes.size) ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < bytes.size) ALPHABET[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }
}
