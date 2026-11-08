package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentArgsTest {
    private val schema = """{"type":"object","required":["place"],"properties":{"place":{"type":"string","description":"Where"},"days":{"type":"integer"},"metric":{"type":"boolean"},"list":{"type":"array"}}}"""

    @Test fun fieldsSkipComplexInputs() {
        assertEquals(listOf("place", "days", "metric"), AgentArgs.fields(schema).map { it.name })
        assertEquals(true, AgentArgs.fields(schema).first().required)
    }

    @Test fun buildKeepsTypesAndLeavesEmptiesOut() {
        assertEquals("""{"place":"Accra","days":2,"metric":true}""", AgentArgs.build(schema, linkedMapOf("place" to "Accra", "days" to "2", "metric" to "yes")))
        assertEquals("""{"place":"Accra"}""", AgentArgs.build(schema, linkedMapOf("place" to "Accra", "days" to "")))
    }

    @Test fun aResultReferenceStaysText() {
        assertEquals("""{"days":"{n}"}""", AgentArgs.build(schema, mapOf("days" to "{n}")))
    }

    @Test fun parseRoundTrips() {
        assertEquals(mapOf("place" to "Accra", "days" to "2"), AgentArgs.parse("""{"place":"Accra","days":2}"""))
        assertEquals(emptyMap(), AgentArgs.parse("nonsense"))
    }
}
