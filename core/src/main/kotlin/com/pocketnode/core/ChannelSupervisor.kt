package com.pocketnode.core

sealed interface SupervisorState {
    data object Running : SupervisorState
    data object WaitingForNetwork : SupervisorState
    data class Problem(val message: String) : SupervisorState
}

/** Sleeps for a while but can be woken early. Replaced by a fake in tests. */
interface Waiter {
    /** Returns true if woken before [ms] passed. */
    fun await(ms: Long): Boolean
    fun wake()
}

class MonitorWaiter : Waiter {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val changed = lock.newCondition()
    private var woken = false

    override fun await(ms: Long): Boolean {
        lock.lock()
        try {
            var left = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(ms)
            while (!woken) {
                if (left <= 0) return false
                left = changed.awaitNanos(left)
            }
            woken = false
            return true
        } finally {
            lock.unlock()
        }
    }

    override fun wake() {
        lock.lock()
        try { woken = true; changed.signalAll() } finally { lock.unlock() }
    }
}

/**
 * Keeps a polling job healthy on a phone. While there is no network it does not poll at all, and it
 * resumes the moment [wake] is called (for example when the network returns). After a real failure
 * it waits, doubling up to [maxBackoffMs], and starts over after a success.
 */
class ChannelSupervisor(
    private val online: () -> Boolean,
    private val poll: () -> Unit,
    private val onState: (SupervisorState) -> Unit,
    private val waiter: Waiter = MonitorWaiter(),
    private val minBackoffMs: Long = 2_000,
    private val maxBackoffMs: Long = 300_000,
    private val offlineRecheckMs: Long = 60_000,
) {
    @Volatile private var stopped = false

    /** Blocks until [stop] is called or the thread is interrupted. */
    fun run() {
        var backoff = minBackoffMs
        var wasWaiting = false
        while (!stopped && !Thread.currentThread().isInterrupted) {
            if (!online()) {
                onState(SupervisorState.WaitingForNetwork)
                wasWaiting = true
                waiter.await(offlineRecheckMs)
                continue
            }
            // A poll can last half a minute; say we are back right away rather than after it ends.
            if (wasWaiting) { onState(SupervisorState.Running); wasWaiting = false }
            try {
                poll()
                backoff = minBackoffMs
                onState(SupervisorState.Running)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                if (stopped) return
                onState(SupervisorState.Problem(e.message.orEmpty()))
                waiter.await(backoff)
                backoff = minOf(backoff * 2, maxBackoffMs)
            }
        }
    }

    fun wake() = waiter.wake()

    fun stop() { stopped = true; waiter.wake() }
}
