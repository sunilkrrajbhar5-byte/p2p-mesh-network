// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.concurrent.thread

fun main() = runBlocking {
    val local = "Relay#000001"
    val sender = "Source#000001"
    val destination = "Target#000001"
    val cluster = UUID.randomUUID()
    val inbound = UUID.randomUUID()
    val next = UUID.randomUUID()
    val third = UUID.randomUUID()
    fun packet(target: String? = destination, ttl: Int = 25, id: UUID = UUID.randomUUID()) =
        MeshPacket(PacketType.MEDIA_CHUNK, sender, cluster, ByteArray(700) { 0x5a },
            target, id, hopLimit = ttl).forNextHop()
    fun input(packet: MeshPacket) = ByteArrayInputStream(packet.toByteArray())
    fun isWiped(view: ByteBuffer): Boolean {
        val bytes = view.duplicate()
        bytes.clear()
        while (bytes.hasRemaining()) if (bytes.get() != 0.toByte()) return false
        return true
    }
    val received = mutableListOf<MeshPacket>()
    val writtenTo = mutableListOf<UUID>()
    var localCalls = 0
    var loan: ByteBuffer? = null
    var localLoan: ByteBuffer? = null
    var scratchLoan: ByteArray? = null
    val filter = RelayPacketFilter(local,
        RelayPacketFilter.LocalConsumer { _, payload ->
            check(payload.isDirect && payload.isReadOnly)
            check(payload.get(0) == 0x5a.toByte())
            localLoan = payload
            localCalls++
        },
        { _, _ -> listOf(inbound, next, next, third) },
        RelayPacketFilter.FrameWriter { session, bytes, scratch ->
            check(bytes.isDirect && bytes.isReadOnly)
            loan = bytes
            scratchLoan = scratch
            val copy = ByteArray(bytes.remaining()).also { bytes.duplicate().get(it) }
            received += MeshPacket.fromByteArray(copy) // Test-only copy validates updated CRC and TTL.
            copy.fill(0)
            scratch.fill(0x7f)
            writtenTo += session
            true
        }, poolSlots = 1)
    val original = packet()
    val result = filter.receive(inbound, input(original)) { error("Unexpected heartbeat") }
    check(result.forwardedTo == 1 && localCalls == 0 && writtenTo == listOf(next))
    check(received.single().packetId == original.packetId)
    check(received.single().hopLimit == original.hopLimit - 1 && received.single().currentHops == 2)
    check(received.single().payload.contentEquals(original.payload))
    check(isWiped(checkNotNull(loan)) && checkNotNull(scratchLoan).all { it == 0.toByte() })
    check(filter.receive(inbound, input(original)) {}.reason == RelayPacketFilter.Reason.DUPLICATE)

    val localResult = filter.receive(inbound, input(packet(local, ttl = 1))) {}
    check(localResult.deliveredLocally && localCalls == 1 && isWiped(checkNotNull(localLoan)))
    check(filter.receive(inbound, input(packet(ttl = 1))) {}.reason == RelayPacketFilter.Reason.TTL_EXPIRED)
    for (broadcast in listOf(null, MeshPacket.BROADCAST)) {
        val broadcastResult = filter.receive(inbound, input(packet(broadcast))) {}
        check(broadcastResult.deliveredLocally && broadcastResult.forwardedTo == 2)
        check(isWiped(checkNotNull(loan)) && isWiped(checkNotNull(localLoan)))
    }
    check(localCalls == 3)
    val corrupt = packet().toByteArray().also { it[50] = (it[50].toInt() xor 1).toByte() }
    check(runCatching { filter.receive(inbound, ByteArrayInputStream(corrupt)) {} }.isFailure)
    val oldVersion = packet().toByteArray().also { it[0] = 1 }
    check(runCatching { filter.receive(inbound, ByteArrayInputStream(oldVersion)) {} }.isFailure)
    check(runCatching { filter.readControl(input(packet())) }.isFailure)
    val heartbeat = MeshPacket(PacketType.HEARTBEAT, sender, cluster, targetId = local, hopLimit = 1).forNextHop()
    check(filter.readControl(input(heartbeat)).hopLimit == 0)
    check(filter.receive(inbound, input(heartbeat)) { check(it.type == PacketType.HEARTBEAT) }
        .reason == RelayPacketFilter.Reason.CONTROL)
    filter.close(); filter.awaitWiped()
    check(filter.cachedPacketIds == 0)

    // True access-order LRU: touching the first ID protects it while the second ID is evicted.
    val lru = RelayPacketFilter(local, { _, _ -> }, { _, _ -> emptyList() }, { _, _, _ -> true }, 1)
    val ids = List(1001) { UUID.randomUUID() }
    for (id in ids.take(1000)) lru.receive(inbound, input(packet(id = id))) {}
    check(lru.cachedPacketIds == 1000)
    check(lru.receive(inbound, input(packet(id = ids[0]))) {}.reason == RelayPacketFilter.Reason.DUPLICATE)
    lru.receive(inbound, input(packet(id = ids[1000]))) {}
    check(lru.receive(inbound, input(packet(id = ids[0]))) {}.reason == RelayPacketFilter.Reason.DUPLICATE)
    check(lru.receive(inbound, input(packet(id = ids[1]))) {}.reason != RelayPacketFilter.Reason.DUPLICATE)
    check(lru.cachedPacketIds == 1000)
    lru.close(); lru.awaitWiped()

    val max = RelayPacketFilter(local, { _, _ -> }, { _, _ -> emptyList() }, { _, _, _ -> true },
        RelayPacketFilter.MAX_POOL_SLOTS)
    check(max.reservedPayloadBytes <= 2 * 1024 * 1024)
    max.close(); max.awaitWiped()
    check(runCatching {
        RelayPacketFilter(local, { _, _ -> }, { _, _ -> emptyList() }, { _, _, _ -> true },
            RelayPacketFilter.MAX_POOL_SLOTS + 1)
    }.isFailure)

    var failedLoan: ByteBuffer? = null
    val failure = RelayPacketFilter(local, { _, _ -> }, { _, _ -> listOf(next) }, { _, bytes, _ ->
        failedLoan = bytes; throw IOException("Injected socket failure")
    }, 1)
    check(runCatching { failure.receive(inbound, input(packet())) {} }.isFailure)
    check(isWiped(checkNotNull(failedLoan)))
    failure.close(); failure.awaitWiped()

    // A cancelled caller cannot wipe a frame while a non-cancellable writer still owns it.
    val entered = CompletableDeferred<Unit>()
    var cancellationLoan: ByteBuffer? = null
    var wroteAfterCancel = false
    val cancelling = RelayPacketFilter(local, { _, _ -> }, { _, _ -> listOf(next) }, { _, bytes, _ ->
        cancellationLoan = bytes
        withContext(NonCancellable) {
            entered.complete(Unit)
            delay(100)
            check(!isWiped(bytes))
            wroteAfterCancel = true
            true
        }
    }, 1)
    val running = launch { cancelling.receive(inbound, input(packet())) {} }
    entered.await()
    running.cancelAndJoin()
    check(wroteAfterCancel && isWiped(checkNotNull(cancellationLoan)))
    cancelling.close(); cancelling.awaitWiped()

    // Real next-hop TCP write from the direct frame (test endpoint uses ephemeral port).
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
        val remote = CompletableDeferred<MeshPacket>()
        val reader = thread {
            try {
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    remote.complete(MeshPacket.readFrom(socket.getInputStream()))
                }
            } catch (e: Exception) { remote.completeExceptionally(e) }
        }
        Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
            val tcp = RelayPacketFilter(local, { _, _ -> }, { _, _ -> listOf(next) }, { _, bytes, scratch ->
                while (bytes.hasRemaining()) {
                    val count = minOf(bytes.remaining(), scratch.size)
                    bytes.get(scratch, 0, count)
                    socket.getOutputStream().write(scratch, 0, count)
                    scratch.fill(0)
                }
                true
            }, 1)
            val before = packet()
            tcp.receive(inbound, input(before)) {}
            val after = remote.await()
            check(after.hopLimit == before.hopLimit - 1 && after.packetId == before.packetId)
            tcp.close(); tcp.awaitWiped()
        }
        reader.join(5_000)
        check(!reader.isAlive)
    }
    println("Step 3 checks passed: routing, TTL/CRC, direct buffers, wiping, cancellation, LRU, memory cap, TCP")
}
