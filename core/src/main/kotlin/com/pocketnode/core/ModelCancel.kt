package com.pocketnode.core

import java.io.IOException
import java.net.HttpURLConnection
import java.util.Collections

/**
 * Stops the model request that is in flight, for a voice call where the owner speaks over a reply that is still being thought up.
 * A request belongs to the epoch that was current when its turn was queued ([begin]); [cancel] moves the epoch on and cuts the open
 * connections, so those requests fail at once and are not retried, and a request still waiting its turn never starts.
 */
object ModelCancel {
    @Volatile private var epoch = 0

    /** Where the app notes how long a request took to connect and to begin answering. */
    @Volatile var timing: ((String) -> Unit)? = null
    private val mine = ThreadLocal<Int>()
    private val open: MutableSet<HttpURLConnection> = Collections.synchronizedSet(mutableSetOf())

    /** The epoch to hand to [enter] on the thread that will make the request. */
    fun begin(): Int = epoch

    fun enter(token: Int) { mine.set(token) }

    fun leave() { mine.remove() }

    fun cancel() { epoch++; synchronized(open) { open.toList() }.forEach { runCatching { it.disconnect() } } }

    internal fun cancelled(): Boolean = mine.get()?.let { it != epoch } ?: false
    internal fun check() { if (cancelled()) throw IOException("Cancelled.") }
    internal fun register(c: HttpURLConnection) { open.add(c) }
    internal fun unregister(c: HttpURLConnection) { open.remove(c) }
}
