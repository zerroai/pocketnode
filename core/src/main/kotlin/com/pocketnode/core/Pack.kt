package com.pocketnode.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolDecl(
    val name: String,
    val description: String,
    val schema: JsonObject = JsonObject(emptyMap()),
    val idempotent: Boolean = false,
)

@Serializable
data class PackManifest(
    val id: String,
    val name: String = "",
    val version: String,
    val kind: String,
    val summary: String = "",
    val minCore: String = "0.0.0",
    val hostApi: Int = 1,
    val permissions: Map<String, List<String>> = emptyMap(),
    val tools: List<ToolDecl> = emptyList(),
    /** One or two sentences on how to set the pack up (for example where to get an API key), shown with its settings. */
    val setup: String = "",
) {
    val displayName: String get() = name.ifBlank { id.substringAfterLast('.') }

    /** The manifest's permissions as broker permissions, e.g. `net` becomes `net:api.telegram.org`. */
    fun declared(): Set<Permission> =
        permissions.flatMap { (scope, targets) -> if (targets.isEmpty()) listOf(Permission(scope)) else targets.map { Permission(scope, it) } }.toSet()

    fun toolSpecs(): List<ToolSpec> = tools.map { ToolSpec(it.name, it.description, it.schema.toString(), it.idempotent) }
}

data class InstalledPack(val manifest: PackManifest, val previousVersion: String?)

/** A bundled pack with its files. */
class LoadedPack(val manifest: PackManifest, val files: Map<String, ByteArray>) {
    val script: String? get() = files["pack.js"]?.decodeToString()
}

/**
 * The packs that ship inside the app. They are plain source (a manifest and a script) read from the app's assets;
 * there is no catalog, no download and no install step.
 */
class BundledPacks(private val ids: List<String>, private val read: (path: String) -> ByteArray?) {
    private val json = Json { ignoreUnknownKeys = true }

    private val manifests: List<PackManifest> by lazy {
        ids.mapNotNull { id -> read("packs/$id/pack.json")?.let { runCatching { json.decodeFromString<PackManifest>(it.decodeToString()) }.getOrNull() } }
    }

    fun list(): List<InstalledPack> = manifests.map { InstalledPack(it, null) }

    fun load(id: String): LoadedPack? {
        val m = manifests.firstOrNull { it.id == id } ?: return null
        val dir = ids.firstOrNull { read("packs/$it/pack.json")?.decodeToString()?.contains("\"id\": \"$id\"") == true } ?: return null
        val script = read("packs/$dir/pack.js") ?: return null
        return LoadedPack(m, mapOf("pack.js" to script))
    }
}
