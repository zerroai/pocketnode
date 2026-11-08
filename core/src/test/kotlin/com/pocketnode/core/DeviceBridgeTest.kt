package com.pocketnode.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class RecordingDevice(private val answers: Map<String, String> = emptyMap()) : DeviceHost {
    val calls = mutableListOf<Pair<String, String>>()
    override fun call(op: String, argsJson: String): String { calls += op to argsJson; return answers[op] ?: """{"ok":true}""" }
}

class DeviceBridgeTest {
    private val closers = mutableListOf<AutoCloseable>()
    @AfterTest fun cleanup() = closers.forEach { it.close() }

    private fun sandbox(declared: Set<Permission>, device: DeviceHost, broker: PermissionBroker = PermissionBroker().also { it.grantDefaults("p", declared) }, script: String): PackSandbox =
        PackSandbox("p", declared, broker, { _, _, _, _ -> HostResponse(404, "") }, script, device = device).also { closers += it }

    private val script = """
        pack = { tools: {
          open: async ({ name }) => device.call('apps.open', { name }),
          contacts: async ({ query }) => device.call('contacts.search', { query }),
          sneaky: async () => device.call('camera.snap', {}),
          bogus: async () => device.call('format.disk', {}),
          nothing: async () => device.call('flashlight', {}),
        } };
    """.trimIndent()

    @Test
    fun `a declared ordinary operation reaches the phone with its arguments and the answer comes back as data`() {
        val phone = RecordingDevice(mapOf("apps.open" to """{"opened":"Camera"}"""))
        val sb = sandbox(setOf(Permission("device", "apps.open")), phone, script = script)
        assertEquals("""{"opened":"Camera"}""", sb.callTool("open", """{"name":"camera"}"""))
        assertEquals(listOf("apps.open" to """{"name":"camera"}"""), phone.calls)
    }

    @Test
    fun `an operation the pack did not declare never reaches the phone`() {
        val phone = RecordingDevice()
        val sb = sandbox(setOf(Permission("device", "apps.open")), phone, script = script)
        assertTrue(assertFailsWith<Exception> { sb.callTool("nothing", "{}") }.message!!.contains("did not declare"))
        assertTrue(assertFailsWith<Exception> { sb.callTool("sneaky", "{}") }.message!!.contains("did not declare"))
        assertTrue(assertFailsWith<Exception> { sb.callTool("bogus", "{}") }.message.orEmpty().isNotBlank())
        assertTrue(phone.calls.isEmpty())
    }

    @Test
    fun `a sensitive operation needs the owner's switch even when declared, and works after it`() {
        val phone = RecordingDevice(mapOf("contacts.search" to """{"matches":[]}"""))
        val declared = setOf(Permission("device", "contacts.search"), Permission("device", "flashlight"))
        val broker = PermissionBroker().also { it.grantDefaults("p", declared) }
        val sb = sandbox(declared, phone, broker, script)
        assertTrue(assertFailsWith<Exception> { sb.callTool("contacts", """{"query":"Ada"}""") }.message!!.contains("has not granted"))
        assertEquals("""{"ok":true}""", sb.callTool("nothing", "{}")) // the ordinary one was granted at install
        assertTrue(phone.calls.none { it.first == "contacts.search" })
        broker.grant("p", Permission("device", "contacts.search"))
        assertEquals("""{"matches":[]}""", sb.callTool("contacts", """{"query":"Ada"}"""))
        broker.revoke("p", Permission("device", "contacts.search"))
        assertTrue(assertFailsWith<Exception> { sb.callTool("contacts", """{"query":"Ada"}""") }.message!!.contains("has not granted"))
    }

    @Test
    fun `a bare device permission is not a wildcard`() {
        val broker = PermissionBroker().also { it.grant("p", Permission("device", "*")) }
        assertTrue(broker.authorize("p", setOf(Permission("device", "*")), setOf(Permission("device", "flashlight"))) is Decision.Denied)
    }

    @Test
    fun `which operations are sensitive`() {
        for (op in listOf("contacts.search", "calendar.events", "location.get", "camera.snap", "apps.list")) assertTrue(DeviceOps.isSensitive(op), op)
        for (op in listOf("flashlight", "apps.open", "alarm.set", "notify", "share.text", "dial")) assertTrue(!DeviceOps.isSensitive(op), op)
        assertTrue(DeviceOps.isSensitive("no.such.thing"))
        assertEquals(DeviceOps.all.size, DeviceOps.all.map { it.name }.toSet().size)
    }

    @Test
    fun `operations that hand private content over ask every time, and camera and quiet ones do not`() {
        val asks = DeviceOps.all.filter { it.askEachTime }.map { it.name }.toSet()
        assertEquals(setOf("apps.list", "calendar.events", "contacts.search", "location.get", "photos.latest", "mail.send", "mail.inbox", "mail.read"), asks)
        assertTrue(DeviceOps.all.filter { it.askEachTime }.all { it.sensitive }, "anything asked each time is also sensitive")
        assertTrue(DeviceOps.all.filter { it.name in setOf("flashlight", "notify", "status", "apps.open", "camera.snap") }.none { it.askEachTime })
    }
}
