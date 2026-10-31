// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** One instance per TCP session. Preemption is at complete frame boundaries, never mid-frame. */
class PriorityQueueManager(scope: CoroutineScope, fastCapacity: Int = 16, slowCapacity: Int = 4) : Closeable {
    private class Work(operation: suspend () -> Unit) {
        val done = CompletableDeferred<Unit>()
        private var action: (suspend () -> Unit)? = operation
        private var started = false
        @Synchronized fun claim(): (suspend () -> Unit)? {
            if (done.isCompleted) return null
            started = true
            return action.also { action = null }
        }
        @Synchronized fun discard(cause: Throwable) {
            if (!started) { action = null; done.completeExceptionally(cause) }
        }
    }
    private val closed = AtomicBoolean(false)
    private val fast = Channel<Work>(fastCapacity, onUndeliveredElement = { it.discard(IOException("Queue closed")) })
    private val slow = Channel<Work>(slowCapacity, onUndeliveredElement = { it.discard(IOException("Queue closed")) })
    init { require(fastCapacity > 0 && slowCapacity > 0) }

    private val worker = scope.launch(Dispatchers.IO) {
        var pendingSlow: Work? = null
        try {
            while (isActive) {
                var work = fast.tryReceive().getOrNull()
                if (work == null) {
                    if (pendingSlow != null) { work = pendingSlow; pendingSlow = null }
                    else {
                        val selected = select<Pair<Boolean, Work>> {
                            fast.onReceive { true to it } // Biased select: ready fast work wins.
                            slow.onReceive { false to it }
                        }
                        work = selected.second
                        if (!selected.first) {
                            val urgent = fast.tryReceive().getOrNull()
                            if (urgent != null) { pendingSlow = work; work = urgent }
                        }
                    }
                }
                val task = checkNotNull(work)
                val action = task.claim() ?: continue
                try { action(); task.done.complete(Unit) }
                catch (e: Throwable) {
                    task.done.completeExceptionally(e)
                    if (e is CancellationException) throw e
                }
            }
        } finally {
            pendingSlow?.discard(IOException("Queue stopped"))
            closed.set(true)
            fast.cancel(); slow.cancel()
        }
    }
    init {
        worker.invokeOnCompletion { closed.set(true); fast.cancel(); slow.cancel() }
    }

    /** Bounded backpressure; returns ONLY after the operation finishes. Safe for borrowed buffers. */
    suspend fun execute(type: PacketType, operation: suspend () -> Unit) {
        if (closed.get()) throw IOException("Queue closed")
        val work = Work(operation)
        try { lane(type).send(work); work.done.await() }
        catch (e: CancellationException) {
            work.discard(e) // Pending work cannot later read the caller's returned buffer.
            withContext(NonCancellable) { runCatching { work.done.await() } } // Active I/O must finish first.
            throw e
        }
    }

    /** For owned packets/heartbeats only. Never use this API for a borrowed relay frame. */
    fun offer(type: PacketType, operation: suspend () -> Unit): Boolean {
        if (closed.get()) return false
        val work = Work(operation)
        val accepted = lane(type).trySend(work).isSuccess
        if (!accepted) work.discard(IOException("Queue full"))
        return accepted
    }

    private fun lane(type: PacketType) = if (type == PacketType.MEDIA_CHUNK) slow else fast

    override fun close() {
        if (closed.compareAndSet(false, true)) { fast.cancel(); slow.cancel(); worker.cancel() }
    }
}
