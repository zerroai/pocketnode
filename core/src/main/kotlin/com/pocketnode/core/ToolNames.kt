package com.pocketnode.core

/** Model APIs only accept `[a-zA-Z0-9_-]` in tool names; ours use dots (for example `clock.now`). */
object ToolNames {
    fun wire(name: String) = name.replace(".", "__")
    fun core(wire: String) = wire.replace("__", ".")
}
