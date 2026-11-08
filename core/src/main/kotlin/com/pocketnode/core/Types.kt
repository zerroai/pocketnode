package com.pocketnode.core

import kotlinx.serialization.Serializable

/** A model's request to run one tool. */
@Serializable
data class ToolCall(val id: String, val name: String, val args: String)

/** Conversation messages shown to the model. */
sealed interface Msg {
    data class User(val text: String) : Msg
    data class Assistant(val text: String, val calls: List<ToolCall> = emptyList()) : Msg
    data class ToolResult(val callId: String, val name: String, val text: String) : Msg
}

/** What a tool offers the model. */
data class ToolSpec(val name: String, val description: String, val schema: String, val idempotent: Boolean)

/** What the model returned for one call. `calls` empty means a final answer. */
data class ModelReply(val text: String, val calls: List<ToolCall> = emptyList())

fun interface ModelClient {
    /** Pass an empty `tools` list to force a plain answer. */
    fun complete(messages: List<Msg>, tools: List<ToolSpec>): ModelReply

    /**
     * Like [complete], but tells [onText] the answer so far (the whole text, growing) while it is being written. A client that
     * cannot stream gives it once, at the end. [onText] may throw to stop: the connection is closed and the exception comes out.
     */
    fun stream(messages: List<Msg>, tools: List<ToolSpec>, onText: (String) -> Unit): ModelReply =
        complete(messages, tools).also { if (it.text.isNotEmpty()) onText(it.text) }
}

/** Thrown from a streaming callback to end a reply that is still being written, for example when the owner pressed Stop. */
class StreamStopped(val reason: String) : RuntimeException(reason)

interface Tool {
    val spec: ToolSpec

    /** Permissions this tool needs, checked by the [PermissionBroker] before every call. */
    val needs: Set<Permission>

    fun call(args: String): String
}

enum class StepKind { MODEL, TOOL }
enum class StepStatus { PLANNED, RUNNING, DONE, FAILED, NEEDS_CONFIRM }

/** One committed unit of work inside a run. A step is written as PLANNED before it executes. */
data class Step(
    val idx: Int,
    val kind: StepKind,
    val status: StepStatus,
    val text: String = "",
    val calls: List<ToolCall> = emptyList(),
    val call: ToolCall? = null,
    /** MODEL steps only: the call offers no tools, forcing a plain answer. */
    val toolFree: Boolean = false,
)

enum class RunOutcome { ANSWERED, NEEDS_CONFIRMATION, FAILED }

data class RunInfo(val runId: String, val sessionId: String, val fence: Long)

sealed interface RunEvent {
    data class ToolStarted(val runId: String, val call: ToolCall) : RunEvent
    data class ToolFinished(val runId: String, val call: ToolCall, val ok: Boolean) : RunEvent
    data class Ended(val runId: String, val outcome: RunOutcome, val text: String) : RunEvent
    /** The answer so far, while the model is still writing it. Not saved; the saved answer arrives with [Ended]. */
    data class Partial(val runId: String, val text: String) : RunEvent
}

fun interface EventSink {
    fun emit(event: RunEvent)

    companion object {
        val None = EventSink { }
    }
}

/** Limits that depend on the device's RAM tier. */
data class Budget(
    val maxModelCalls: Int,
    val maxToolResultChars: Int,
    val maxHistoryMessages: Int,
) {
    companion object {
        val TierA = Budget(maxModelCalls = 20, maxToolResultChars = 16_000, maxHistoryMessages = 80)
        val TierB = Budget(maxModelCalls = 12, maxToolResultChars = 8_000, maxHistoryMessages = 40)
        val TierC = Budget(maxModelCalls = 6, maxToolResultChars = 3_000, maxHistoryMessages = 16)
    }
}
