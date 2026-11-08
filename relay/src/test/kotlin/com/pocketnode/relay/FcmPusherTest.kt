package com.pocketnode.relay

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FcmPusherTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) + "\n-----END PRIVATE KEY-----\n"
    private val account = Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        kotlinx.serialization.json.buildJsonObject {
            put("client_email", kotlinx.serialization.json.JsonPrimitive("relay@demo.iam.gserviceaccount.com"))
            put("project_id", kotlinx.serialization.json.JsonPrimitive("demo-project"))
            put("private_key", kotlinx.serialization.json.JsonPrimitive(pem))
            put("token_uri", kotlinx.serialization.json.JsonPrimitive("https://oauth.test/token"))
        },
    )

    private class Fake(var tokenCode: Int = 200, var sendCode: Int = 200, var sendBody: String = "{}") {
        val calls = mutableListOf<Triple<String, Map<String, String>, String>>()
        val fn = HttpFn { _, url, headers, body ->
            calls += Triple(url, headers, body)
            if (url == "https://oauth.test/token") tokenCode to """{"access_token":"at-1","expires_in":3600}""" else sendCode to sendBody
        }
    }

    private val device = Device("d1", "fcm", "phone-token")
    private var clock = 1_700_000_000_000L

    @Test
    fun `the token request is a signed JWT with the claims Google expects`() {
        val p = FcmPusher(account, Fake().fn, { clock })
        val jwt = p.buildAssertion().split('.')
        assertEquals(3, jwt.size)
        val dec = Base64.getUrlDecoder()
        assertEquals("RS256", Json.parseToJsonElement(dec.decode(jwt[0]).decodeToString()).jsonObject.getValue("alg").jsonPrimitive.content)
        val claims = Json.parseToJsonElement(dec.decode(jwt[1]).decodeToString()).jsonObject
        assertEquals("relay@demo.iam.gserviceaccount.com", claims.getValue("iss").jsonPrimitive.content)
        assertEquals("https://www.googleapis.com/auth/firebase.messaging", claims.getValue("scope").jsonPrimitive.content)
        assertEquals("https://oauth.test/token", claims.getValue("aud").jsonPrimitive.content)
        assertEquals(1_700_000_000L, claims.getValue("iat").jsonPrimitive.content.toLong())
        assertEquals(1_700_003_600L, claims.getValue("exp").jsonPrimitive.content.toLong())
        val ok = Signature.getInstance("SHA256withRSA").run { initVerify(pair.public); update("${jwt[0]}.${jwt[1]}".toByteArray()); verify(dec.decode(jwt[2])) }
        assertTrue(ok)
    }

    @Test
    fun `a wake is a content-free high priority data message addressed to the phone's token`() {
        val f = Fake()
        assertEquals(PushResult.Sent, FcmPusher(account, f.fn, { clock }).wake(device))
        val send = f.calls.last()
        assertEquals("https://fcm.googleapis.com/v1/projects/demo-project/messages:send", send.first)
        assertEquals("Bearer at-1", send.second["Authorization"])
        val msg = Json.parseToJsonElement(send.third).jsonObject.getValue("message").jsonObject
        assertEquals("phone-token", msg.getValue("token").jsonPrimitive.content)
        assertEquals("wake", msg.getValue("data").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("HIGH", msg.getValue("android").jsonObject.getValue("priority").jsonPrimitive.content)
        assertEquals(setOf("token", "data", "android"), msg.keys) // no notification block and no message text
    }

    @Test
    fun `the access token is reused until shortly before it expires`() {
        val f = Fake()
        val p = FcmPusher(account, f.fn, { clock })
        p.wake(device); p.wake(device)
        assertEquals(1, f.calls.count { it.first == "https://oauth.test/token" })
        clock += 3_600_000 - 30_000 // inside the one-minute safety margin
        p.wake(device)
        assertEquals(2, f.calls.count { it.first == "https://oauth.test/token" })
    }

    @Test
    fun `a rejected token is reported so the relay can forget it`() {
        val cleared = mutableListOf<Device>()
        val f = Fake(sendCode = 404, sendBody = """{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}""")
        assertEquals(PushResult.TokenInvalid, FcmPusher(account, f.fn, { clock }, onTokenInvalid = { cleared += it }).wake(device))
        assertEquals(listOf(device), cleared)
    }

    @Test
    fun `failures to get a token or to send are failures, and other devices are skipped without any request`() {
        assertEquals(PushResult.Failed, FcmPusher(account, Fake(tokenCode = 401).fn, { clock }).wake(device))
        assertEquals(PushResult.Failed, FcmPusher(account, Fake(sendCode = 503).fn, { clock }).wake(device))
        val f = Fake()
        val p = FcmPusher(account, f.fn, { clock })
        assertEquals(PushResult.Skipped, p.wake(Device("d2", "", "")))
        assertEquals(PushResult.Skipped, p.wake(Device("d3", "fcm", "")))
        assertEquals(0, f.calls.size)
        assertEquals(PushResult.Skipped, NoPush.wake(device))
    }
}
