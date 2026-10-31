// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/** Bounded transfer-level reverse breadcrumbs; independent of the relay's 1,000 packet-ID LRU. */
class AckManager(
    private val localId: String,
    private val sendOnSession: suspend (UUID, MeshPacket) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) : Closeable {
    enum class Code(val wire: Int) { READY(1), VERIFIED(2), REJECTED(3) }
    enum class DeliveryState { PREPARING, ACCEPTED, SENT, DELIVERED, FAILED }
    class Ticket internal constructor(
        val transferId: UUID, internal val target: String, internal val cluster: UUID,
        internal val size: Long, internal val sha: ByteArray
    ) {
        internal val ready = CompletableDeferred<Unit>()
        internal val verified = CompletableDeferred<Unit>()
    }
    private data class BackPath(val sender: String, val target: String, val cluster: UUID,
        val session: UUID, val size: Long, val sha: ByteArray, var touched: Long)
    private val lock = Any()
    private val paths = LinkedHashMap<UUID, BackPath>()
    private val pending = LinkedHashMap<UUID, Ticket>()
    private val mutableStates = MutableStateFlow<Map<UUID, DeliveryState>>(emptyMap())
    val states: StateFlow<Map<UUID, DeliveryState>> = mutableStates.asStateFlow()
    private var closed = false

    fun register(transfer: ChunkManager.Metadata, target: String, cluster: UUID): Ticket = synchronized(lock) {
        check(!closed && pending.size < MAX_TRANSFERS && transfer.id !in pending)
        require(MeshPacket.isValidIdentity(target) && target != localId)
        Ticket(transfer.id, target, cluster, transfer.size, transfer.sha256.copyOf()).also {
            pending[transfer.id] = it; state(it.transferId, DeliveryState.PREPARING)
        }
    }

    /** Called on every relay before local delivery/forwarding; payload is a borrowed view. */
    fun observe(inbound: UUID, header: MeshPacket.FrameHeader, payload: ByteBuffer) = synchronized(lock) {
        if (closed) return@synchronized
        evict()
        if (header.type == PacketType.PRE_ALERT) {
            val metadata = ChunkManager.decodeMetadata(payload)
            val target = header.targetId ?: return@synchronized
            if (!MeshPacket.isValidIdentity(target)) return@synchronized
            val old = paths[metadata.id]
            if (old == null) {
                if (paths.size >= MAX_TRANSFERS) return@synchronized // Do not evict an active transfer's path.
                paths[metadata.id] = BackPath(header.senderId, target, header.clusterId, inbound,
                    metadata.size, metadata.sha256.copyOf(), now())
            } else if (old.sender == header.senderId && old.target == target && old.cluster == header.clusterId &&
                old.size == metadata.size && MessageDigest.isEqual(old.sha, metadata.sha256)) old.touched = now()
        } else if (header.type == PacketType.MEDIA_CHUNK) {
            val id = ChunkManager.fragmentTransferId(payload) ?: return@synchronized
            paths[id]?.takeIf { it.sender == header.senderId && it.target == header.targetId && it.cluster == header.clusterId }
                ?.touched = now()
        }
    }

    /** null means not an ACK; empty means an ACK has no valid reverse path and must be dropped. */
    fun reverseRoute(header: MeshPacket.FrameHeader, payload: ByteBuffer, inbound: UUID): List<UUID>? = synchronized(lock) {
        if (header.type != PacketType.DELIVERY_ACK) return@synchronized null
        val ack = decode(payload)
        evict()
        val path = paths[ack.id] ?: return@synchronized emptyList()
        if (header.targetId != path.sender || header.senderId != path.target || header.clusterId != path.cluster ||
            ack.size != path.size || !MessageDigest.isEqual(ack.sha, path.sha) || path.session == inbound)
            return@synchronized emptyList()
        path.touched = now()
        listOf(path.session)
    }

    /** Destination sends READY after opening cache, VERIFIED only after full-file hash validation. */
    suspend fun reply(id: UUID, code: Code) {
        val path = synchronized(lock) { evict(); paths[id]?.copy() } ?: throw IOException("Reverse ACK path missing")
        check(path.target == localId) { "Only the destination may acknowledge this transfer" }
        val bytes = ByteBuffer.allocate(ACK_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
            .put(code.wire.toByte()).putLong(path.size).put(path.sha).array()
        try {
            sendOnSession(path.session, MeshPacket(PacketType.DELIVERY_ACK, localId, path.cluster,
                bytes, path.sender))
        } finally { bytes.fill(0) }
    }

    /** Sender-local consumer; unrelated/mismatched ACKs cannot change a delivery tick. */
    fun accept(header: MeshPacket.FrameHeader, payload: ByteBuffer): Boolean = synchronized(lock) {
        if (header.type != PacketType.DELIVERY_ACK || header.targetId != localId) return@synchronized false
        val ack = decode(payload)
        val ticket = pending[ack.id] ?: return@synchronized false
        if (header.senderId != ticket.target || header.clusterId != ticket.cluster || ack.size != ticket.size ||
            !MessageDigest.isEqual(ack.sha, ticket.sha)) return@synchronized false
        if (mutableStates.value[ack.id] == DeliveryState.FAILED) return@synchronized false
        if (mutableStates.value[ack.id] == DeliveryState.DELIVERED) return@synchronized true
        when (ack.code) {
            Code.READY -> {
                ticket.ready.complete(Unit)
                if (mutableStates.value[ack.id] == DeliveryState.PREPARING) state(ack.id, DeliveryState.ACCEPTED)
            }
            Code.VERIFIED -> {
                ticket.ready.complete(Unit); ticket.verified.complete(Unit); state(ack.id, DeliveryState.DELIVERED)
            }
            Code.REJECTED -> fail(ack.id, IOException("Destination rejected transfer"))
        }
        true
    }

    suspend fun awaitReady(ticket: Ticket) { ticket.ready.await() }
    suspend fun awaitVerified(ticket: Ticket) { ticket.verified.await() }
    fun markSent(id: UUID) = synchronized(lock) {
        if (mutableStates.value[id] !in listOf(DeliveryState.DELIVERED, DeliveryState.FAILED)) state(id, DeliveryState.SENT)
    }
    fun fail(id: UUID, cause: Throwable) = synchronized(lock) {
        pending[id]?.let { it.ready.completeExceptionally(cause); it.verified.completeExceptionally(cause) }
        if (mutableStates.value[id] != DeliveryState.DELIVERED) state(id, DeliveryState.FAILED)
    }
    fun forget(id: UUID) = synchronized(lock) { pending.remove(id)?.sha?.fill(0); Unit }
    private fun state(id: UUID, value: DeliveryState) {
        val copy = LinkedHashMap(mutableStates.value)
        copy[id] = value
        while (copy.size > MAX_TRANSFERS) copy.remove(copy.keys.first())
        mutableStates.value = copy
    }
    private fun evict() { val time = now(); paths.entries.removeAll { time - it.value.touched >= PATH_TTL_MS } }
    override fun close() = synchronized(lock) {
        closed = true
        pending.keys.toList().forEach { fail(it, IOException("ACK manager closed")) }
        pending.values.forEach { it.sha.fill(0) }; pending.clear()
        paths.values.forEach { it.sha.fill(0) }; paths.clear()
    }

    private data class Ack(val id: UUID, val code: Code, val size: Long, val sha: ByteArray)
    private fun decode(payload: ByteBuffer): Ack {
        require(payload.remaining() == ACK_BYTES)
        val b = payload.slice().order(ByteOrder.BIG_ENDIAN)
        require(b.int == MAGIC)
        val id = UUID(b.long, b.long)
        val wire = b.get().toInt() and 0xff
        val code = Code.values().firstOrNull { it.wire == wire }
        return decodeTail(id, code, b)
    }
    private fun decodeTail(id: UUID, code: Code?, b: ByteBuffer): Ack {
        requireNotNull(code)
        val size = b.long
        require(size >= 0)
        return Ack(id, code, size, ByteArray(32).also { b.get(it) })
    }
    companion object {
        private const val MAGIC = 0x41434b31 // ACK1
        const val ACK_BYTES = 61
        const val MAX_TRANSFERS = 256
        const val PATH_TTL_MS = 2 * 60 * 60 * 1_000L
    }
}
