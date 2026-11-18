package com.nocrunch.rider.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Collects changed paths and hands them to [send] in batches, at most one per [delayMs]. */
class FileChangeBatcher(private val scope: CoroutineScope, private val delayMs: Long, private val send: (List<String>) -> Unit) {
    private val pending = LinkedHashSet<String>()
    private var flushScheduled = false // guarded by `pending`

    fun add(paths: Collection<String>) {
        synchronized(pending) {
            pending += paths
            if (flushScheduled) return
            flushScheduled = true
        }
        scope.launch(Dispatchers.IO) {
            delay(delayMs)
            // Taking the batch and clearing the flag in one step means paths added while this batch is being sent
            // schedule their own flush, instead of seeing a flush still running and being left behind.
            val batch = synchronized(pending) {
                flushScheduled = false
                pending.toList().also { pending.clear() }
            }
            send(batch)
        }
    }
}
