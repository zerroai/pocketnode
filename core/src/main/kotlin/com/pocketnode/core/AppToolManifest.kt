package com.pocketnode.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Serializable
data class AppInfo(val name: String, val summary: String = "")

@Serializable
data class AppToolDecl(
    val name: String,
    val description: String,
    val schema: JsonObject = JsonObject(emptyMap()),
    /** `read`, `change` or `external`. */
    val effect: String = "change",
    val confirm: Boolean = false,
)

/** The tool file an app ships so the agent can use it (see docs/design/app-protocol.md). */
@Serializable
data class AppToolManifest(val protocol: Int, val app: AppInfo, val tools: List<AppToolDecl> = emptyList())

sealed interface AppToolParse {
    data class Ok(val manifest: AppToolManifest) : AppToolParse
    data class Bad(val problems: List<String>) : AppToolParse
}

/** Reads and checks an app's tool file. A file with any problem is refused whole, so a half-valid file never exposes tools. */
object AppTools {
    const val PROTOCOL = 1
    const val MAX_FILE_BYTES = 64 * 1024
    const val MAX_TOOLS = 64
    const val MAX_DESCRIPTION = 400
    val EFFECTS = setOf("read", "change", "external")
    private val NAME = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(bytes: ByteArray): AppToolParse {
        if (bytes.size > MAX_FILE_BYTES) return AppToolParse.Bad(listOf("The tool file is larger than $MAX_FILE_BYTES bytes."))
        val m = try { json.decodeFromString(AppToolManifest.serializer(), bytes.decodeToString()) } catch (e: Exception) { return AppToolParse.Bad(listOf("The tool file is not valid JSON in the expected shape.")) }
        val problems = mutableListOf<String>()
        if (m.protocol != PROTOCOL) problems += "Protocol ${m.protocol} is not supported (this app reads protocol $PROTOCOL)."
        if (m.app.name.isBlank() || m.app.name.length > 60) problems += "The app name must be 1 to 60 characters."
        if (m.app.summary.length > 200) problems += "The app summary is longer than 200 characters."
        if (m.tools.size > MAX_TOOLS) problems += "More than $MAX_TOOLS tools."
        val seen = mutableSetOf<String>()
        for (t in m.tools) {
            if (!NAME.matches(t.name) || t.name.length > 48) problems += "Tool name \"${t.name.take(30)}\" must be lower case words joined by dots or underscores, at most 48 characters."
            if (!seen.add(t.name)) problems += "Tool \"${t.name}\" is listed twice."
            if (t.description.isBlank() || t.description.length > MAX_DESCRIPTION) problems += "Tool \"${t.name}\" needs a description of 1 to $MAX_DESCRIPTION characters."
            if (t.effect !in EFFECTS) problems += "Tool \"${t.name}\" has effect \"${t.effect}\"; use read, change or external."
            if (t.schema.isNotEmpty() && (t.schema["type"] as? JsonPrimitive)?.content != "object") problems += "Tool \"${t.name}\": the schema must describe an object."
            if (t.effect == "external" && !t.confirm) problems += "Tool \"${t.name}\" has effect external and must set confirm to true."
        }
        return if (problems.isEmpty()) AppToolParse.Ok(m) else AppToolParse.Bad(problems)
    }

    /** The name the model sees: one app's tool cannot be mistaken for another app's or for a pack tool. */
    fun modelName(packageName: String, tool: String) = "app.${packageName.replace('.', '_')}.$tool"
}
