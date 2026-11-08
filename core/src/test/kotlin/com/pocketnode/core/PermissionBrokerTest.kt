package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PermissionBrokerTest {
    private val net = Permission("net", "api.telegram.org")
    private val camera = Permission("device.camera")

    @Test
    fun `needs both declaration and grant`() {
        val b = PermissionBroker()
        assertTrue(b.authorize("p", setOf(net), setOf(net)) is Decision.Denied) // not granted yet
        b.grantDefaults("p", setOf(net))
        assertEquals(Decision.Allowed, b.authorize("p", setOf(net), setOf(net)))
        assertTrue(b.authorize("p", emptySet(), setOf(net)) is Decision.Denied) // not declared
    }

    @Test
    fun `sensitive scopes are not granted by default`() {
        val b = PermissionBroker()
        b.grantDefaults("p", setOf(net, camera))
        assertTrue(b.authorize("p", setOf(net, camera), setOf(camera)) is Decision.Denied)
        b.grant("p", camera)
        assertEquals(Decision.Allowed, b.authorize("p", setOf(net, camera), setOf(camera)))
    }

    @Test
    fun `grants are per pack and can be revoked`() {
        val b = PermissionBroker()
        b.grantDefaults("a", setOf(net))
        assertTrue(b.authorize("b", setOf(net), setOf(net)) is Decision.Denied)
        b.revoke("a", net)
        assertTrue(b.authorize("a", setOf(net), setOf(net)) is Decision.Denied)
    }

    @Test
    fun `a wildcard target covers specific targets but not other scopes`() {
        val b = PermissionBroker()
        b.grantDefaults("p", setOf(Permission("net")))
        assertEquals(Decision.Allowed, b.authorize("p", setOf(Permission("net")), setOf(net)))
        assertTrue(b.authorize("p", setOf(Permission("net")), setOf(Permission("store"))) is Decision.Denied)
    }

    @Test
    fun `a sensitive grant and a revoked default survive a restart`() {
        val kv = InMemoryPackKv()
        val first = PermissionBroker(persist = kv)
        first.grantDefaults("p", setOf(net, camera))
        first.grant("p", camera)
        first.revoke("p", net)

        val second = PermissionBroker(persist = kv) // a new process
        second.grantDefaults("p", setOf(net, camera)) // activation runs again
        assertEquals(Decision.Allowed, second.authorize("p", setOf(net, camera), setOf(camera)))
        assertTrue(second.authorize("p", setOf(net, camera), setOf(net)) is Decision.Denied, "a permission the user took away stays away")

        second.revoke("p", camera)
        val third = PermissionBroker(persist = kv)
        assertTrue(third.authorize("p", setOf(camera), setOf(camera)) is Decision.Denied)
        third.grant("p", net)
        assertEquals(Decision.Allowed, PermissionBroker(persist = kv).also { it.grantDefaults("p", setOf(net)) }.authorize("p", setOf(net), setOf(net)))
    }

    @Test
    fun `nothing is saved unless a store is given, and other packs are untouched`() {
        val kv = InMemoryPackKv()
        PermissionBroker(persist = kv).grant("a", camera)
        assertTrue(PermissionBroker(persist = kv).authorize("b", setOf(camera), setOf(camera)) is Decision.Denied)
        PermissionBroker().grant("c", camera)
        assertEquals(null, kv.get("core.grants", "c"))
    }
}
