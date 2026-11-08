package com.pocketnode.core

/** A tool provided by an installed pack. The pack's code starts on first use. */
private class InstalledPackTool(override val spec: ToolSpec, private val packId: String, private val runtime: PackRuntime) : Tool {
    override val needs: Set<Permission> = emptySet()
    override fun call(args: String): String = runtime.sandbox(packId).callTool(spec.name, args)
}

/**
 * Turns installed packs into things the agent can use: tools from tool packs, and sandboxes for
 * channel packs. Sandboxes are created lazily and dropped when a pack changes.
 */
class PackRuntime(
    private val manager: BundledPacks,
    private val broker: PermissionBroker,
    private val net: HostNet,
    private val kv: PackKv,
    /** Told about every request a pack makes to a website, for the owner's activity record. */
    private val onFetch: ((packId: String, host: String, method: String, status: Int?, error: String?) -> Unit)? = null,
    /** Looks up a pack's named secret, e.g. its bot token. */
    private val secrets: (packId: String, name: String) -> String?,
) {
    /** What packs' phone requests run against. The app sets the real one; until then phone requests fail clearly. */
    @Volatile var device: DeviceHost = DeviceHost.None

    private val sandboxes = mutableMapOf<String, Pair<String, PackSandbox>>() // id to (version, sandbox)
    private var toolCache: List<RegisteredTool>? = null

    @Synchronized
    fun tools(): List<RegisteredTool> = toolCache ?: manager.list().filter { it.manifest.kind == "tool" }.flatMap { p ->
        val declared = p.manifest.declared()
        broker.grantDefaults(p.manifest.id, declared)
        p.manifest.toolSpecs().map { RegisteredTool(InstalledPackTool(it, p.manifest.id, this), p.manifest.id, declared) }
    }.also { toolCache = it }

    /** The pack's sandbox, started if needed. A new version replaces the old one. */
    @Synchronized
    fun sandbox(packId: String): PackSandbox {
        val loaded = manager.load(packId) ?: throw IllegalStateException("The pack $packId is not installed or did not verify.")
        sandboxes[packId]?.let { (v, sb) -> if (v == loaded.manifest.version) return sb else sb.close() }
        val declared = loaded.manifest.declared()
        broker.grantDefaults(packId, declared)
        val sb = PackSandbox(packId, declared, broker, net, loaded.script ?: "pack = { tools: {} };", kv = kv, secrets = { name -> secrets(packId, name) }, device = device, onFetch = onFetch)
        sandboxes[packId] = loaded.manifest.version to sb
        return sb
    }

    /** A separate sandbox for a long-running job such as a channel poller, so it never blocks the shared one. */
    fun newSandbox(packId: String, secretOverride: ((String) -> String?)? = null): PackSandbox {
        val loaded = manager.load(packId) ?: throw IllegalStateException("The pack $packId is not installed or did not verify.")
        val declared = loaded.manifest.declared()
        broker.grantDefaults(packId, declared)
        return PackSandbox(packId, declared, broker, net, loaded.script ?: "pack = { tools: {} };", kv = kv, secrets = secretOverride ?: { name -> secrets(packId, name) }, device = device, onFetch = onFetch)
    }

    /** Call after install, update, rollback or uninstall. */
    @Synchronized
    fun changed(packId: String? = null) {
        toolCache = null
        if (packId == null) { sandboxes.values.forEach { it.second.close() }; sandboxes.clear() }
        else sandboxes.remove(packId)?.second?.close()
    }
}
