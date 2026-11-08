package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppToolManifestTest {
    private fun file(tools: String, protocol: Int = 1, name: String = "Cafe Orders") =
        """{"protocol":$protocol,"app":{"name":"$name"},"tools":[$tools]}""".toByteArray()

    private val read = """{"name":"order.status","description":"Shows the last order.","effect":"read"}"""

    @Test fun validFileIsRead() {
        val r = AppTools.parse(file("$read,{\"name\":\"order.place\",\"description\":\"Places one.\",\"schema\":{\"type\":\"object\"},\"effect\":\"external\",\"confirm\":true}"))
        assertEquals(2, (r as AppToolParse.Ok).manifest.tools.size)
    }

    @Test fun unknownFieldsAreIgnored() = assertTrue(AppTools.parse(file(read.replace("}", ",\"future\":1}"))) is AppToolParse.Ok)

    @Test fun problemsRefuseTheWholeFile() {
        fun bad(bytes: ByteArray) = (AppTools.parse(bytes) as AppToolParse.Bad).problems
        assertTrue(bad(file(read, protocol = 2)).single().contains("Protocol 2"))
        assertTrue(bad(file("$read,$read")).any { it.contains("twice") })
        assertTrue(bad(file("""{"name":"Bad Name","description":"x"}""")).any { it.contains("lower case") })
        assertTrue(bad(file("""{"name":"a","description":""}""")).any { it.contains("description") })
        assertTrue(bad(file("""{"name":"a","description":"x","effect":"delete"}""")).any { it.contains("effect") })
        assertTrue(bad(file("""{"name":"a","description":"x","effect":"external"}""")).any { it.contains("confirm") })
        assertTrue(bad(file("""{"name":"a","description":"x","schema":{"type":"string"}}""")).any { it.contains("object") })
        assertTrue(bad(file(read, name = "")).any { it.contains("app name") })
        assertTrue(bad("nope".toByteArray()).single().contains("JSON"))
        assertTrue(bad(ByteArray(AppTools.MAX_FILE_BYTES + 1)).single().contains("larger"))
    }

    @Test fun descriptionTooLongIsRefused() {
        val long = "x".repeat(AppTools.MAX_DESCRIPTION + 1)
        assertTrue(AppTools.parse(file("""{"name":"a","description":"$long","effect":"read"}""")) is AppToolParse.Bad)
    }

    @Test fun modelNameKeepsAppsApart() = assertEquals("app.com_cafe_orders.order.status", AppTools.modelName("com.cafe.orders", "order.status"))
}
