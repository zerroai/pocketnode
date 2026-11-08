package com.pocketnode.relay

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class PushResult { Sent, Skipped, TokenInvalid, Failed }

/** Tells a sleeping phone to connect and collect its messages. The message itself never goes through push. */
fun interface PushSender {
    fun wake(device: Device): PushResult
}

object NoPush : PushSender {
    override fun wake(device: Device) = PushResult.Skipped
}

/** One HTTP call: returns the status and body. Replaced in tests. */
fun interface HttpFn {
    fun send(method: String, url: String, headers: Map<String, String>, body: String): Pair<Int, String>
}

object JdkHttp : HttpFn {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun send(method: String, url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val b = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).method(method, HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> b.header(k, v) }
        val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return r.statusCode() to r.body()
    }
}

/**
 * Firebase Cloud Messaging through the HTTP v1 API, authenticated with a service account. Sends a
 * high-priority data message with no content, just enough to wake the app.
 */
class FcmPusher(
    serviceAccountJson: String,
    private val http: HttpFn = JdkHttp,
    private val now: () -> Long = System::currentTimeMillis,
    private val sendBase: String = "https://fcm.googleapis.com",
    private val onTokenInvalid: (Device) -> Unit = {},
) : PushSender {
    private val json = Json { ignoreUnknownKeys = true }
    private val account: JsonObject = json.parseToJsonElement(serviceAccountJson).jsonObject
    private val email = account.getValue("client_email").jsonPrimitive.content
    private val projectId = account.getValue("project_id").jsonPrimitive.content
    private val tokenUri = account["token_uri"]?.jsonPrimitive?.contentOrNull ?: "https://oauth2.googleapis.com/token"
    private val privateKey = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(account.getValue("private_key").jsonPrimitive.content.replace(Regex("-----[A-Z ]+-----"), "").replace("\\s".toRegex(), ""))),
    )
    private var accessToken: String? = null
    private var accessExpiresAt = 0L

    /** The signed request for an access token. Public so tests can check what is claimed. */
    fun buildAssertion(): String {
        fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        val iat = now() / 1000
        val header = b64("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = b64(
            buildJsonObject {
                put("iss", email); put("scope", "https://www.googleapis.com/auth/firebase.messaging"); put("aud", tokenUri)
                put("iat", iat); put("exp", iat + 3600)
            }.toString().toByteArray(),
        )
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(privateKey); update("$header.$claims".toByteArray()); sign() }
        return "$header.$claims.${b64(sig)}"
    }

    @Synchronized
    private fun token(): String? {
        if (accessToken != null && now() < accessExpiresAt - 60_000) return accessToken
        val (code, body) = http.send(
            "POST", tokenUri, mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${buildAssertion()}",
        )
        if (code != 200) return null
        val o = json.parseToJsonElement(body).jsonObject
        accessToken = o["access_token"]?.jsonPrimitive?.contentOrNull ?: return null
        accessExpiresAt = now() + (o["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L) * 1000
        return accessToken
    }

    override fun wake(device: Device): PushResult {
        if (device.pushKind != "fcm" || device.pushToken.isBlank()) return PushResult.Skipped
        val t = try { token() } catch (e: Exception) { null } ?: return PushResult.Failed
        val message = buildJsonObject {
            put("message", buildJsonObject {
                put("token", device.pushToken)
                put("data", buildJsonObject { put("type", "wake") })
                put("android", buildJsonObject { put("priority", "HIGH") })
            })
        }.toString()
        val (code, body) = try {
            http.send("POST", "$sendBase/v1/projects/$projectId/messages:send", mapOf("Authorization" to "Bearer $t", "Content-Type" to "application/json"), message)
        } catch (e: Exception) {
            return PushResult.Failed
        }
        return when {
            code in 200..299 -> PushResult.Sent
            code == 404 || (code == 400 && body.contains("INVALID_ARGUMENT")) || body.contains("UNREGISTERED") -> { onTokenInvalid(device); PushResult.TokenInvalid }
            else -> PushResult.Failed
        }
    }
}
