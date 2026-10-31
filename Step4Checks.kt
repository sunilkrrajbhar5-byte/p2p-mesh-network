// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

private class BufferInput(private val buffer: ByteBuffer) : InputStream() {
    override fun read(): Int = if (buffer.hasRemaining()) buffer.get().toInt() and 255 else -1
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!buffer.hasRemaining()) return -1
        val count = minOf(len, buffer.remaining()); buffer.get(b, off, count); return count
    }
}

fun main(args: Array<String>) = runBlocking {
    val large = args.contains("--large")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val queue = PriorityQueueManager(scope)
    val order = Collections.synchronizedList(mutableListOf<String>())
    val started = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    val initial = launch {
        queue.execute(PacketType.HEARTBEAT) { order += "in-flight"; started.complete(Unit); gate.await() }
    }
    started.await()
    check(queue.offer(PacketType.MEDIA_CHUNK) { order += "file" })
    check(queue.offer(PacketType.PTT_VOICE) { order += "voice" })
    check(queue.offer(PacketType.DELIVERY_ACK) { order += "ack" })
    gate.complete(Unit)
    initial.join()
    withTimeout(5_000) { while (order.size < 4) delay(1) }
    check(order.toList() == listOf("in-flight", "voice", "ack", "file"))
    val writing = CompletableDeferred<Unit>()
    val finishWrite = CompletableDeferred<Unit>()
    var wrote = false
    val active = launch {
        queue.execute(PacketType.MEDIA_CHUNK) { writing.complete(Unit); finishWrite.await(); wrote = true }
    }
    writing.await()
    active.cancel()
    delay(20)
    check(!active.isCompleted) // Borrowed buffer must not be released while an actual write owns it.
    finishWrite.complete(Unit); active.join(); check(wrote)
    queue.close()

    check(ChunkManager.fragmentOffset(4_096, 0) == 2_147_483_648L)
    check(runCatching { ChunkManager.fragmentOffset(Long.MAX_VALUE, 15) }.isFailure)
    check(runCatching { ChunkManager.fragmentOffset(0, 16) }.isFailure)

    val root = Files.createTempDirectory("mesh-step4-checks-").toFile()
    val cache = java.io.File(root, "cache")
    val source = java.io.File(root, "source.bin")
    val senderId = "Sender#000001"
    val destId = "Receiver#000001"
    val bridgeId = "Bridge#000001"
    val group = UUID.randomUUID()
    val towardSender = UUID.randomUUID()
    val towardDest = UUID.randomUUID()
    val ab = PriorityQueueManager(scope)
    val bd = PriorityQueueManager(scope)
    val db = PriorityQueueManager(scope)
    val ba = PriorityQueueManager(scope)
    val senderAcks = AckManager(senderId, { _, _ -> error("Sender should not reply") })
    val bridgeAcks = AckManager(bridgeId, { _, _ -> error("Bridge should only relay ACKs") })
    lateinit var bridge: RelayPacketFilter
    lateinit var destination: RelayPacketFilter
    val destAcks = AckManager(destId, { session, packet ->
        check(session == towardDest)
        db.execute(packet.type) {
            bridge.receive(towardDest, ByteArrayInputStream(packet.forNextHop().toByteArray())) {}
        }
    })
    val receiver = ChunkManager(destId, cache, destAcks, { error("Receiver must use reverse ACK sender") })
    destination = RelayPacketFilter(destId, { header, payload -> check(receiver.consume(header, payload)) },
        { _, _ -> error("Destination should not forward file") }, { _, _, _ -> false },
        onFrame = destAcks::observe, routeOverride = destAcks::reverseRoute)
    bridge = RelayPacketFilter(bridgeId, { _, _ -> error("Transit bytes must not reach local file writer") },
        { _, _ -> listOf(towardDest) }, { session, frame, _ ->
            val type = PacketType.fromWireCode(frame.get(1).toInt() and 255)
            if (session == towardDest) bd.execute(type) {
                destination.receive(towardDest, BufferInput(frame.duplicate())) {}
            } else {
                check(session == towardSender)
                ba.execute(type) {
                    val h = MeshPacket.inspectFrame(frame)
                    val p = frame.duplicate(); p.position(h.payloadOffset); p.limit(h.payloadOffset + h.payloadLength)
                    check(senderAcks.accept(h, p.slice()))
                }
            }
            true
        }, onFrame = bridgeAcks::observe, routeOverride = bridgeAcks::reverseRoute)
    var prealerts = 0
    var fragments = 0L
    var corruptNext = false
    var lastTransfer: UUID? = null
    val sender = ChunkManager(senderId, java.io.File(root, "unused-sender-cache"), senderAcks, { packet ->
        if (packet.type == PacketType.PRE_ALERT) {
            check(packet.payload.size == 1_024)
            lastTransfer = ChunkManager.decodeMetadata(ByteBuffer.wrap(packet.payload)).id
            prealerts++
        }
        var outgoing = packet
        if (packet.type == PacketType.MEDIA_CHUNK) {
            check(prealerts > 0 && packet.payload.size <= ChunkManager.FRAGMENT_BYTES + ChunkManager.FRAGMENT_HEADER_BYTES)
            fragments++
            if (corruptNext) {
                val modified = packet.payload
                modified[ChunkManager.FRAGMENT_HEADER_BYTES] = (modified[ChunkManager.FRAGMENT_HEADER_BYTES].toInt() xor 1).toByte()
                outgoing = MeshPacket(packet.type, packet.senderId, packet.clusterId, modified, packet.targetId,
                    packet.packetId, packet.hopLimit, packet.currentHops, packet.timestamp)
                corruptNext = false
            }
        }
        ab.execute(outgoing.type) {
            bridge.receive(towardSender, ByteArrayInputStream(outgoing.forNextHop().toByteArray())) {}
        }
    })
    try {
        // Validate offset/bitmap assembly independently of ordered TCP delivery.
        val edgeCache = java.io.File(root, "edge-cache")
        val codes = Collections.synchronizedList(mutableListOf<Int>())
        val edgeAcks = AckManager(destId, { _, packet -> codes += packet.payload[20].toInt() })
        val edge = ChunkManager(destId, edgeCache, edgeAcks, { error("Unexpected source send") })
        val bytes = ByteArray(2 * ChunkManager.FRAGMENT_BYTES + 7) { (it % 251).toByte() }
        val metadata = ChunkManager.Metadata(UUID.randomUUID(), bytes.size.toLong(), "../../display-only.bin",
            MessageDigest.getInstance("SHA-256").digest(bytes))
        suspend fun deliver(type: PacketType, payload: ByteArray) {
            val frame = ByteBuffer.wrap(MeshPacket(type, senderId, group, payload, destId).forNextHop().toByteArray())
            val header = MeshPacket.inspectFrame(frame)
            frame.position(header.payloadOffset); frame.limit(header.payloadOffset + header.payloadLength)
            val view = frame.slice()
            edgeAcks.observe(towardSender, header, view)
            check(edge.consume(header, view))
        }
        fun fragment(index: Int): ByteArray {
            val offset = index * ChunkManager.FRAGMENT_BYTES
            val count = minOf(ChunkManager.FRAGMENT_BYTES, bytes.size - offset)
            return ByteBuffer.allocate(ChunkManager.FRAGMENT_HEADER_BYTES + count).order(ByteOrder.BIG_ENDIAN)
                .putInt(0x43484b31).putLong(metadata.id.mostSignificantBits).putLong(metadata.id.leastSignificantBits)
                .putLong(0).putInt(index).putInt(count).put(bytes, offset, count).array()
        }
        try {
            deliver(PacketType.PRE_ALERT, ChunkManager.encodeMetadata(metadata))
            for (index in listOf(2, 0, 0)) deliver(PacketType.MEDIA_CHUNK, fragment(index))
            check(edge.completed.value.isEmpty())
            deliver(PacketType.MEDIA_CHUNK, fragment(1))
            withTimeout(5_000) { while (edge.completed.value.isEmpty() || AckManager.Code.VERIFIED.wire !in codes) delay(1) }
            val assembled = checkNotNull(edge.completed.value[metadata.id]).file
            check(assembled.parentFile?.canonicalFile == edgeCache.canonicalFile)
            check(assembled.readBytes().contentEquals(bytes))
            check(!java.io.File(root.parentFile, "display-only.bin").exists())
        } finally { edge.close(true); edgeAcks.close(); edgeCache.delete() }

        val length = if (large) 1024L * 1024 * 1024 + 12_345 else 2L * ChunkManager.CHUNK_BYTES + 12_345
        RandomAccessFile(source, "rw").use {
            it.setLength(length)
            it.writeLong(0x102030405060708L)
            it.seek(ChunkManager.CHUNK_BYTES.toLong() - 3); it.writeInt(0x12345678)
            it.seek(length - 1); it.writeByte(0x7e)
        }
        var lastProgress = -1L
        var progressCount = 0
        val transfer = withTimeout(if (large) 600_000 else 30_000) {
            sender.sendFile(source, destId, group, onProgress = { sent, total ->
                check(total == length && sent in 0..length && sent > lastProgress)
                check(sent == 0L || fragments >= (sent + ChunkManager.FRAGMENT_BYTES - 1) / ChunkManager.FRAGMENT_BYTES)
                lastProgress = sent
                progressCount++
            })
        }
        check(lastProgress == length && progressCount >= 2)
        check(senderAcks.states.value[transfer] == AckManager.DeliveryState.DELIVERED)
        val result = checkNotNull(receiver.completed.value[transfer])
        check(result.file.length() == length)
        check(fragments == ChunkManager.fragmentCount(length))
        RandomAccessFile(result.file, "r").use {
            check(it.readLong() == 0x102030405060708L)
            it.seek(ChunkManager.CHUNK_BYTES.toLong() - 3); check(it.readInt() == 0x12345678)
            it.seek(length - 1); check(it.readUnsignedByte() == 0x7e)
        }
        check(!java.io.File(root, "unused-sender-cache").exists())
        receiver.release(transfer)
        println("Verified $length bytes through a memory-only bridge; reverse READY + VERIFIED ACKs passed")

        RandomAccessFile(source, "rw").use { it.setLength(1_111) }
        corruptNext = true
        val failedUi = mutableListOf<FileTransferPhase>()
        check(runCatching { withTimeout(20_000) {
            sendChatFile(source, destId, group, sender) { failedUi += it.phase }
        } }.isFailure)
        check(failedUi.last() == FileTransferPhase.FAILED && FileTransferPhase.DELIVERED !in failedUi)
        check(senderAcks.states.value[lastTransfer] == AckManager.DeliveryState.FAILED)
        check(receiver.completed.value.isEmpty())

        RandomAccessFile(source, "rw").use { it.setLength(0) }
        val emptyUi = mutableListOf<FileTransferPhase>()
        val empty = withTimeout(20_000) {
            sendChatFile(source, destId, group, sender) {
                emptyUi += it.phase
                if (it.phase == FileTransferPhase.DELIVERED) {
                    check(senderAcks.states.value[lastTransfer] == AckManager.DeliveryState.DELIVERED)
                    check(it.fraction == 1f)
                }
            }
        }
        check(emptyUi.last() == FileTransferPhase.DELIVERED)
        check(receiver.completed.value[empty]?.file?.length() == 0L)
        check(senderAcks.states.value[empty] == AckManager.DeliveryState.DELIVERED)
        receiver.release(empty)
        println("Step 4 checks passed: priority, 64-bit offsets, metadata, SHA rejection, reverse ACK, empty file")
    } finally {
        sender.close(true); receiver.close(true)
        bridge.close(); destination.close()
        bridge.awaitWiped(); destination.awaitWiped()
        senderAcks.close(); bridgeAcks.close(); destAcks.close()
        listOf(ab, bd, db, ba).forEach { it.close() }
        scope.cancel()
        // Only known test files/directories under the unique temporary root are removed.
        source.delete()
        cache.listFiles()?.forEach { check(it.parentFile?.canonicalFile == cache.canonicalFile); it.delete() }
        cache.delete(); root.delete()
    }
}
