// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * A borrowed-buffer relay, replacing a transfer engine's save-to-file consumer.
 * Uses MeshPacket's existing frame parser/CRC; no new packet format or media engine.
 * No persistence APIs, payload logging, payload queues, or MeshPacket payload copies.
 *
 * LocalConsumer may read the payload ONLY during its callback. It must neither retain
 * the view nor launch asynchronous work that uses it after returning. The same rule
 * applies to FrameWriter, which MUST return only after the actual socket write ends.
 */
class RelayPacketFilter(
    private val localMyId: String,
    private val localConsumer: LocalConsumer,
    private val nextHops: (MeshPacket.FrameHeader, UUID) -> List<UUID>,
    private val writer: FrameWriter,
    poolSlots: Int = DEFAULT_POOL_SLOTS,
    private val onFrame: (UUID, MeshPacket.FrameHeader, ByteBuffer) -> Unit = { _, _, _ -> },
    private val routeOverride: (MeshPacket.FrameHeader, ByteBuffer, UUID) -> List<UUID>? = { _, _, _ -> null }
) : Closeable {
    fun interface LocalConsumer {
        suspend fun consume(header: MeshPacket.FrameHeader, payload: ByteBuffer)
    }
    fun interface FrameWriter {
        /** Frame TTL is already decremented. Do not queue, retain, or advance it again. */
        suspend fun write(sessionId: UUID, frame: ByteBuffer, scratch: ByteArray): Boolean
    }
    /** Optional leaf transport. The existing parser, TTL, CRC and duplicate cache remain authoritative. */
    interface TransportBridge {
        fun routes(header: MeshPacket.FrameHeader): List<UUID>
        fun owns(sessionId: UUID): Boolean
        suspend fun write(sessionId: UUID, frame: ByteBuffer): Boolean
    }
    private val bridge = AtomicReference<TransportBridge?>()
    fun attachBridge(value: TransportBridge) { check(bridge.compareAndSet(null, value)) { "Bridge already attached" } }
    fun detachBridge(value: TransportBridge) { bridge.compareAndSet(value, null) }
    enum class Reason { CONTROL, DUPLICATE, LOCAL, TTL_EXPIRED, NO_ROUTE, FORWARDED, WRITE_FAILED }
    data class Result(val reason: Reason, val deliveredLocally: Boolean = false, val forwardedTo: Int = 0)

    private class Slot {
        val bytes: ByteBuffer = ByteBuffer.allocateDirect(MeshPacket.MAX_FRAME_SIZE)
        val scratch = ByteArray(SCRATCH_BYTES)
        fun wipe() {
            // clear() alone only changes indices; overwrite the ENTIRE owned capacity.
            bytes.clear()
            while (bytes.remaining() >= java.lang.Long.BYTES) bytes.putLong(0L)
            while (bytes.hasRemaining()) bytes.put(0.toByte())
            bytes.clear()
            scratch.fill(0)
        }
    }

    private val lifecycleLock = Any()
    private var closed = false
    private var borrowed = 0
    private val drained = CompletableDeferred<Unit>()
    private val cacheLock = Any()
    private val seen = object : LinkedHashMap<UUID, Boolean>(LRU_CAPACITY + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Boolean>?): Boolean = size > LRU_CAPACITY
    }
    private val slots: Channel<Slot>

    /** Sum of explicitly owned direct buffers AND heap I/O scratch, excluding object metadata. */
    val reservedPayloadBytes: Int

    init {
        require(MeshPacket.isValidIdentity(localMyId))
        require(poolSlots in 1..MAX_POOL_SLOTS) { "Relay payload buffers must fit in 2 MiB" }
        reservedPayloadBytes = poolSlots * SLOT_BYTES
        slots = Channel(poolSlots, onUndeliveredElement = ::recycleUndelivered)
        repeat(poolSlots) { check(slots.trySend(Slot()).isSuccess) }
    }

    /**
     * Reads exactly one frame into a direct buffer. Invoke on each admitted socket's
     * existing single-reader pipeline. A lease is held through local delivery and all
     * synchronous socket writes, then wiped in finally, including failures/cancellation.
     */
    suspend fun receive(
        inboundSession: UUID,
        input: InputStream,
        onHeartbeat: (MeshPacket) -> Unit
    ): Result {
        val slot = acquireSlot()
        try {
            val header = withContext(Dispatchers.IO) { MeshPacket.readFrame(input, slot.bytes, slot.scratch) }
            require(header.currentHops > 0) { "Unsent frame" }
            if (header.type == PacketType.HEARTBEAT) {
                onHeartbeat(controlPacket(header, slot))
                return Result(Reason.CONTROL)
            }
            if (isDuplicate(header.packetId)) return Result(Reason.DUPLICATE)
            fun payloadView(): ByteBuffer {
                val view = slot.bytes.asReadOnlyBuffer()
                view.position(header.payloadOffset); view.limit(header.payloadOffset + header.payloadLength)
                return view.slice().asReadOnlyBuffer()
            }
            onFrame(inboundSession, header, payloadView())
            val broadcast = header.targetId == null || header.targetId == MeshPacket.BROADCAST
            val local = broadcast || header.targetId == localMyId
            if (local) {
                val payload = slot.bytes.asReadOnlyBuffer()
                payload.position(header.payloadOffset)
                payload.limit(header.payloadOffset + header.payloadLength)
                localConsumer.consume(header, payload.slice().asReadOnlyBuffer())
                if (!broadcast) return Result(Reason.LOCAL, deliveredLocally = true)
            }
            if (header.hopLimit == 0) return Result(Reason.TTL_EXPIRED, local)
            // Never echo onto the incoming socket. Unicast has one next hop; broadcasts fan out.
            val extension = bridge.get()
            val edgeRoutes = extension?.routes(header).orEmpty()
            val candidates = (routeOverride(header, payloadView(), inboundSession) ?:
                if (broadcast) edgeRoutes + nextHops(header, inboundSession)
                else edgeRoutes.ifEmpty { nextHops(header, inboundSession) })
                .distinct().filter { it != inboundSession }
            val targets = if (broadcast) candidates else candidates.take(1)
            if (targets.isEmpty()) return Result(Reason.NO_ROUTE, local)
            MeshPacket.advanceFrame(slot.bytes, header) // TTL - 1, currentHops + 1, refreshed CRC32.
            var forwarded = 0
            for (target in targets) {
                val written = if (extension?.owns(target) == true)
                    extension.write(target, slot.bytes.asReadOnlyBuffer())
                else writer.write(target, slot.bytes.asReadOnlyBuffer(), slot.scratch)
                if (written) forwarded++
            }
            return Result(if (forwarded > 0) Reason.FORWARDED else Reason.WRITE_FAILED, local, forwarded)
        } finally { releaseSlot(slot) }
    }

    /** Handshakes use the same bounded direct pool; reject data before it can be dispatched. */
    suspend fun readControl(input: InputStream): MeshPacket {
        val slot = acquireSlot()
        try {
            val header = withContext(Dispatchers.IO) { MeshPacket.readFrame(input, slot.bytes, slot.scratch) }
            return controlPacket(header, slot)
        } finally { releaseSlot(slot) }
    }

    private fun controlPacket(header: MeshPacket.FrameHeader, slot: Slot): MeshPacket {
        require(header.type == PacketType.HEARTBEAT && header.payloadLength <= 1 && header.currentHops > 0)
        val control = ByteArray(header.payloadLength)
        if (control.isNotEmpty()) control[0] = slot.bytes.get(header.payloadOffset)
        return try {
            MeshPacket(header.type, header.senderId, header.clusterId, control,
                header.targetId, header.packetId, header.hopLimit, header.currentHops, header.timestamp)
        } finally { control.fill(0) }
    }

    private suspend fun acquireSlot(): Slot {
        val slot = slots.receive()
        synchronized(lifecycleLock) {
            if (closed) { slot.wipe(); throw IllegalStateException("Relay closed") }
            borrowed++
        }
        return slot
    }

    private fun releaseSlot(slot: Slot) {
        slot.wipe()
        synchronized(lifecycleLock) {
            borrowed--
            if (!closed) check(slots.trySend(slot).isSuccess)
            else if (borrowed == 0) {
                synchronized(cacheLock) { seen.clear() }
                drained.complete(Unit)
            }
        }
    }

    private fun recycleUndelivered(slot: Slot) {
        slot.wipe()
        synchronized(lifecycleLock) { if (!closed) slots.trySend(slot) }
    }

    private fun isDuplicate(id: UUID): Boolean = synchronized(cacheLock) {
        if (seen[id] != null) true else { seen[id] = true; false }
    }

    /** No payload retry backlog: failed/no-route packets remain deduplicated until LRU eviction. */
    val cachedPacketIds: Int get() = synchronized(cacheLock) { seen.size }

    override fun close() = synchronized(lifecycleLock) {
        if (!closed) {
            closed = true
            slots.close()
            while (true) { val slot = slots.tryReceive().getOrNull() ?: break; slot.wipe() }
            synchronized(cacheLock) { seen.clear() }
            if (borrowed == 0) drained.complete(Unit)
        }
    }

    /** Close the transport first to unblock reads/writes; then await completion of all wipes. */
    suspend fun awaitWiped() { drained.await() }

    companion object {
        const val MAX_BUFFER_BYTES = 2 * 1024 * 1024
        const val LRU_CAPACITY = 1_000
        const val SCRATCH_BYTES = 8 * 1024
        const val SLOT_BYTES = MeshPacket.MAX_FRAME_SIZE + SCRATCH_BYTES
        const val MAX_POOL_SLOTS = MAX_BUFFER_BYTES / SLOT_BYTES
        const val DEFAULT_POOL_SLOTS = 8 // 589,880 bytes; enough for the five-link Step 2 topology.
    }
}
