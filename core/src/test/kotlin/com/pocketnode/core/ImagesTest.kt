package com.pocketnode.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ImagesTest {
    private val ref1 = "mc-image:aaaaaaaaaaaaaaaa"
    private val ref2 = "mc-image:bbbbbbbbbbbbbbbb"
    private val ref3 = "mc-image:cccccccccccccccc"
    private val ref4 = "mc-image:dddddddddddddddd"
    private val bytes = mapOf(ref1 to byteArrayOf(1, 2, 3), ref2 to byteArrayOf(4, 5), ref3 to byteArrayOf(6), ref4 to byteArrayOf(7, 8, 9, 10))

    @AfterTest fun reset() { Images.source = { null }; Attachments.fileSource = { null } }

    private val clock = ToolSpec("camera.look", "look", "{}", false)
    private fun chat(vararg results: String): List<Msg> = buildList {
        add(Msg.User("look at this"))
        add(Msg.Assistant("", results.indices.map { ToolCall("c$it", "camera.look", "{}") }))
        results.forEachIndexed { i, r -> add(Msg.ToolResult("c$i", "camera.look", r)) }
    }

    @Test
    fun `standard base64 matches the well known cases`() {
        assertEquals("", StdBase64.encode(byteArrayOf()))
        assertEquals("Zg==", StdBase64.encode("f".toByteArray()))
        assertEquals("Zm8=", StdBase64.encode("fo".toByteArray()))
        assertEquals("Zm9v", StdBase64.encode("foo".toByteArray()))
        assertEquals("Zm9vYg==", StdBase64.encode("foob".toByteArray()))
        assertEquals(java.util.Base64.getEncoder().encodeToString(ByteArray(1000) { (it * 7).toByte() }), StdBase64.encode(ByteArray(1000) { (it * 7).toByte() }))
    }

    @Test
    fun `only the newest photos are attached and older ones are named as not shown`() {
        Images.source = { bytes[it] }
        val msgs = chat("""{"image":"$ref1"}""", """{"image":"$ref2"}""", """{"image":"$ref3","other":"$ref4"}""")
        val picked = Images.attachments(msgs)
        assertEquals(setOf(ref2, ref3, ref4), picked.values.flatten().toSet(), "the three newest")
        assertTrue(ref1 !in picked.values.flatten())
        assertEquals("""{"image":"(this photo is not shown again)"}""", Images.readable("""{"image":"$ref1"}""", emptyList()))
        assertEquals("""{"image":"(photo 1 is attached)"}""", Images.readable("""{"image":"$ref2"}""", listOf(ref2)))
    }

    @Test
    fun `a photo that is gone is never attached`() {
        Images.source = { null }
        assertTrue(Images.attachments(chat("""{"image":"$ref1"}""")).isEmpty())
    }

    @Test
    fun `OpenAI style puts the picture in a user message after the tool results`() {
        Images.source = { bytes[it] }
        val m = OpenAiModel({ "x" }, { null }, { "m" }).encodeMessages(chat("""{"image":"$ref1"}""", """{"image":"$ref2"}"""))
        val roles = m.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("system", "user", "assistant", "tool", "tool", "user"), roles)
        val photos = m.last().jsonObject["content"]!!.jsonArray
        assertEquals("image_url", photos[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AQID", photos[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals(3, photos.size)
        assertTrue(m[3].jsonObject["content"]!!.jsonPrimitive.content.contains("is attached"))
    }

    @Test
    fun `Anthropic puts the picture inside the tool result`() {
        Images.source = { bytes[it] }
        val m = AnthropicModel({ "k" }).encodeMessages(chat("""{"image":"$ref1"}"""))
        val result = m.last().jsonObject["content"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals("text", result[0].jsonObject["type"]!!.jsonPrimitive.content)
        val img = result[1].jsonObject
        assertEquals("image", img["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", img["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
        assertEquals("AQID", img["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Ollama sends the picture as an images list on a user message`() {
        Images.source = { bytes[it] }
        val m = OllamaModel({ "x" }, { "llava" }).encodeMessages(chat("""{"image":"$ref1"}"""))
        val last = m.last().jsonObject
        assertEquals("user", last["role"]!!.jsonPrimitive.content)
        assertEquals(listOf("AQID"), last["images"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `without any photo the messages are unchanged`() {
        Images.source = { bytes[it] }
        val plain = listOf(Msg.User("hi"), Msg.Assistant("", listOf(ToolCall("c", "clock.now", "{}"))), Msg.ToolResult("c", "clock.now", "12:00"), Msg.Assistant("It is noon."))
        val m = OpenAiModel({ "x" }, { null }, { "m" }).encodeMessages(plain)
        assertEquals(listOf("system", "user", "assistant", "tool", "assistant"), m.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("12:00", m[3].jsonObject["content"]!!.jsonPrimitive.content)
    }

    private val fileRef = "mc-file:eeeeeeeeeeeeeeee"

    @Test
    fun `a message with a photo and a file is split for the screen and expanded for the model`() {
        Images.source = { bytes[it] }
        Attachments.fileSource = { if (it == fileRef) StoredFile("notes.txt", "buy rice\nbuy beans") else null }
        val text = "What is this?\n$ref1\n$fileRef"
        val p = Attachments.parse(text)
        assertEquals("What is this?", p.text)
        assertEquals(listOf(ref1), p.photos)
        assertEquals(listOf(fileRef), p.files)
        assertEquals("What is this? notes.txt Photo", Attachments.preview(text))
        assertTrue(Attachments.expandForModel(text).contains("[Attached file: notes.txt (reference $fileRef; data tools take this reference)]\nbuy rice\nbuy beans\n[End of file]"))
        assertEquals(Attachments.Parsed("hello", emptyList(), emptyList()), Attachments.parse("hello"))
        assertTrue(Attachments.expandForModel("x $fileRef").let { true })
        Attachments.fileSource = { null }
        assertTrue(Attachments.expandForModel(fileRef).contains("no longer stored"))
    }

    @Test
    fun `a long file is cut and says so`() {
        Attachments.fileSource = { StoredFile("big.txt", "a".repeat(Attachments.MAX_FILE_CHARS + 500)) }
        val e = Attachments.expandForModel(fileRef)
        assertTrue(e.contains("The rest of the file is not shown"))
        assertTrue(e.length < Attachments.MAX_FILE_CHARS + 300)
    }

    @Test
    fun `each model gets a photo the owner attached to their own message`() {
        Images.source = { bytes[it] }
        val msgs = listOf<Msg>(Msg.User("Describe this\n$ref1"))
        val openAi = OpenAiModel({ "x" }, { null }, { "m" }).encodeMessages(msgs).last().jsonObject["content"]!!.jsonArray
        assertEquals("text", openAi[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(openAi[0].jsonObject["text"]!!.jsonPrimitive.content.contains("is attached"))
        assertEquals("data:image/jpeg;base64,AQID", openAi[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        val anthropic = AnthropicModel({ "k" }).encodeMessages(msgs).last().jsonObject["content"]!!.jsonArray
        assertEquals("image", anthropic[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("AQID", anthropic[0].jsonObject["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("text", anthropic[1].jsonObject["type"]!!.jsonPrimitive.content)
        val ollama = OllamaModel({ "x" }, { "llava" }).encodeMessages(msgs).last().jsonObject
        assertEquals(listOf("AQID"), ollama["images"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `an attached file reaches every model as words`() {
        Attachments.fileSource = { StoredFile("todo.md", "- call Ada") }
        val msgs = listOf<Msg>(Msg.User("Summarise\n$fileRef"))
        val openAi = OpenAiModel({ "x" }, { null }, { "m" }).encodeMessages(msgs).last().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(openAi.contains("[Attached file: todo.md (reference") && openAi.contains("call Ada"))
        val anthropic = AnthropicModel({ "k" }).encodeMessages(msgs).last().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(anthropic.contains("call Ada"))
        assertTrue(OllamaModel({ "x" }, { "m" }).encodeMessages(msgs).last().jsonObject["content"]!!.jsonPrimitive.content.contains("call Ada"))
    }
}
