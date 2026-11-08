package com.pocketnode.core

/**
 * Which features and tools are switched off in this build. The owner sets two lists when building (see docs/developers/feature-switches.md):
 * features by id, and tools by name, where a name ending in `*` stands for every tool that starts with it. A feature that is off does not
 * show in the screens and does nothing underneath. Tools that belong to an off feature go off with it.
 */
class FeatureSwitch(features: String = "", tools: String = "") {
    private val offFeatures = split(features).map { it.lowercase() }.toSet()
    private val offTools = split(tools).map { it.lowercase() }

    /** Ids that were asked for but do not exist, so a typo can be shown instead of silently doing nothing. */
    val unknown: List<String> = offFeatures.filter { it !in ALL }.sorted()

    fun enabled(id: String): Boolean = id.lowercase() !in offFeatures

    fun toolEnabled(name: String): Boolean {
        val n = name.lowercase()
        if (OWNED.any { (feature, prefix) -> !enabled(feature) && n.startsWith(prefix) }) return false
        return offTools.none { p -> if (p.endsWith("*")) n.startsWith(p.dropLast(1)) else n == p }
    }

    fun summary(): String = if (offFeatures.isEmpty() && offTools.isEmpty()) "nothing switched off"
        else "features off: ${offFeatures.sorted().joinToString().ifEmpty { "none" }}; tools off: ${offTools.joinToString().ifEmpty { "none" }}"

    companion object {
        /** Every feature id that can be switched off, with what it covers. */
        val ALL: Map<String, String> = linkedMapOf(
            "voice_call" to "The voice call screen, the call button, the Calls list, and the call engine underneath.",
            "voice_talk" to "The Talk quick settings tile and the assistant gesture that start dictation in a new chat.",
            "bank_alerts" to "Reading bank alert notifications, the Bank alerts screen and the bank.* tools.",
            "agents" to "The Agents tab and the agent.* tools.",
        )

        /** Tool name prefixes that belong to a feature. */
        private val OWNED = listOf("bank_alerts" to "bank.", "agents" to "agent.")

        private fun split(s: String) = s.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
