package com.pocketnode.core

import java.util.UUID

class RegisteredTool(val tool: Tool, val packId: String, val declared: Set<Permission>)

data class RunResult(val runId: String, val outcome: RunOutcome, val text: String)

/**
 * Runs one message to a final answer. Every model call and tool call is written to the
 * [RunStore] as PLANNED before it executes, so [recover] can continue after the process dies.
 *
 * A non-idempotent tool found RUNNING after a restart is not run again; the run stops with
 * [RunOutcome.NEEDS_CONFIRMATION] because the side effect may already have happened.
 */
class AgentRuntime(
    private val store: RunStore,
    private val model: ModelClient,
    /** Read on every step so tools from packs installed or removed meanwhile are picked up. */
    private val tools: () -> List<RegisteredTool>,
    private val broker: PermissionBroker,
    private val budget: Budget,
    private val guard: RunGuard = RunGuard.None,
    private val log: ActivityLog? = null,
) {
    constructor(store: RunStore, model: ModelClient, tools: List<RegisteredTool>, broker: PermissionBroker, budget: Budget) :
        this(store, model, { tools }, broker, budget)

    fun run(sessionId: String, userText: String, sink: EventSink = EventSink.None): RunResult {
        val info = store.claimRun(sessionId, UUID.randomUUID().toString(), userText)
        return execute(info, sink)
    }

    /**
     * Tries again after a model error, from where the run stopped: tools that already ran are not run again (a photo already
     * taken stays taken). Returns null when the session has nothing to retry.
     */
    fun retry(sessionId: String, sink: EventSink = EventSink.None): RunResult? =
        store.reopenFailed(sessionId)?.let { execute(it, sink) }

    /** Continues every run that was claimed but not finished. Call once at service start. */
    fun recover(sink: EventSink = EventSink.None): List<RunResult> =
        store.unfinishedRuns().map { execute(it, sink) }

    private fun execute(run: RunInfo, sink: EventSink): RunResult {
        while (true) {
            guard.check(run.runId)?.let { reason ->
                log?.add(ActivityKind.SYSTEM, "Run stopped", reason, problem = true)
                return finish(run, RunOutcome.FAILED, reason, sink)
            }
            val steps = store.steps(run.runId)
            val pending = steps.firstOrNull { it.status == StepStatus.PLANNED || it.status == StepStatus.RUNNING }
            if (pending != null) {
                val done = when (pending.kind) {
                    StepKind.MODEL -> runModelStep(run, pending, sink)
                    StepKind.TOOL -> runToolStep(run, pending, sink)
                }
                if (done != null) return done
                continue
            }
            val last = steps.lastOrNull()
            if (last != null && last.kind == StepKind.MODEL && last.status == StepStatus.DONE && last.calls.isEmpty() && last.text.isNotBlank()) {
                return finish(run, RunOutcome.ANSWERED, last.text, sink)
            }
            val modelCalls = steps.count { it.kind == StepKind.MODEL }
            val forcePlain = modelCalls >= budget.maxModelCalls || (last?.kind == StepKind.MODEL && last.calls.isEmpty())
            if (last?.toolFree == true) {
                return finish(run, RunOutcome.FAILED, "I could not produce an answer.", sink)
            }
            store.putStep(run, Step(steps.size, StepKind.MODEL, StepStatus.PLANNED, toolFree = forcePlain))
        }
    }

    private fun runModelStep(run: RunInfo, step: Step, sink: EventSink): RunResult? {
        val reply = try {
            model.stream(buildMessages(run), if (step.toolFree) emptyList() else tools().map { it.tool.spec }) { partial ->
                // Stop can come while the answer is being written: end it now instead of at the next step.
                guard.check(run.runId)?.let { throw StreamStopped(it) }
                sink.emit(RunEvent.Partial(run.runId, partial))
            }
        } catch (e: StaleFenceException) {
            throw e
        } catch (e: StreamStopped) {
            log?.add(ActivityKind.SYSTEM, "Run stopped", e.reason, problem = true)
            return finish(run, RunOutcome.FAILED, e.reason, sink)
        } catch (e: Exception) {
            return finish(run, RunOutcome.FAILED, "$MODEL_ERROR${ModelErrors.friendly(e)}", sink)
        }
        val calls = if (step.toolFree) emptyList() else reply.calls
        store.putStep(run, step.copy(status = StepStatus.DONE, text = reply.text, calls = calls))
        var idx = step.idx + 1
        for (c in calls) {
            store.putStep(run, Step(idx++, StepKind.TOOL, StepStatus.PLANNED, call = c))
        }
        return null
    }

    private fun runToolStep(run: RunInfo, step: Step, sink: EventSink): RunResult? {
        val call = checkNotNull(step.call)
        val reg = tools().firstOrNull { it.tool.spec.name == call.name }
        if (reg == null) {
            store.putStep(run, step.copy(status = StepStatus.FAILED, text = "Unknown tool ${call.name}"))
            return null
        }
        if (step.status == StepStatus.RUNNING && !reg.tool.spec.idempotent) {
            store.putStep(run, step.copy(status = StepStatus.NEEDS_CONFIRM))
            return finish(
                run,
                RunOutcome.NEEDS_CONFIRMATION,
                "I was interrupted while running ${call.name}. It may already have happened; please confirm before I retry.",
                sink,
            )
        }
        val decision = broker.authorize(reg.packId, reg.declared, reg.tool.needs)
        if (decision is Decision.Denied) {
            log?.add(ActivityKind.TOOL, call.name, "${why(run)}Refused: ${decision.reason}".take(380), problem = true)
            store.putStep(run, step.copy(status = StepStatus.FAILED, text = "Denied: ${decision.reason}"))
            sink.emit(RunEvent.ToolFinished(run.runId, call, ok = false))
            return null
        }
        store.putStep(run, step.copy(status = StepStatus.RUNNING))
        sink.emit(RunEvent.ToolStarted(run.runId, call))
        val result = try {
            truncate(reg.tool.call(call.args)) to true
        } catch (e: StaleFenceException) {
            throw e
        } catch (e: Exception) {
            "Error: ${e.message}" to false
        }
        log?.add(ActivityKind.TOOL, call.name, "${why(run)}${call.args.replace(Regex("\\s+"), " ").take(150)}  ->  ${result.first.replace(Regex("\\s+"), " ").removePrefix("Error: ").take(120)}", problem = !result.second)
        store.putStep(run, step.copy(status = if (result.second) StepStatus.DONE else StepStatus.FAILED, text = result.first))
        sink.emit(RunEvent.ToolFinished(run.runId, call, result.second))
        return null
    }

    /** The start of the message that led to a step, so the Activity record can say why a tool ran. Photos and file references are left out. */
    private fun why(run: RunInfo): String {
        val last = runCatching { store.history(run.sessionId).lastOrNull { it is Msg.User } as? Msg.User }.getOrNull() ?: return ""
        val words = Attachments.preview(last.text).replace(Regex("\\s+"), " ").trim()
        return if (words.isEmpty()) "" else "For \u201c${if (words.length > 70) words.take(69) + "\u2026" else words}\u201d: "
    }

    private fun buildMessages(run: RunInfo): List<Msg> {
        val history = store.history(run.sessionId).takeLast(budget.maxHistoryMessages).dropWhile { it !is Msg.User }
        val fromSteps = store.steps(run.runId).mapNotNull { s ->
            when {
                s.kind == StepKind.MODEL && s.status == StepStatus.DONE -> Msg.Assistant(s.text, s.calls)
                s.kind == StepKind.TOOL && (s.status == StepStatus.DONE || s.status == StepStatus.FAILED) ->
                    Msg.ToolResult(s.call!!.id, s.call.name, s.text)
                else -> null
            }
        }
        return history + fromSteps
    }

    private fun truncate(text: String): String =
        if (text.length <= budget.maxToolResultChars) text
        else text.take(budget.maxToolResultChars) + "\n[truncated ${text.length - budget.maxToolResultChars} chars]"

    private fun finish(run: RunInfo, outcome: RunOutcome, text: String, sink: EventSink): RunResult {
        store.finishRun(run, outcome, text)
        sink.emit(RunEvent.Ended(run.runId, outcome, text))
        return RunResult(run.runId, outcome, text)
    }
}
