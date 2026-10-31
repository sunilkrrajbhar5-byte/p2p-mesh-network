// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** JVM checks for admission, bridge roles, route lifetime and real TCP frame streaming. */
fun main() = runBlocking {
    val group = UUID.randomUUID()
    val owner = "Owner#000001"
    val controller = ClusterController(owner, group, owner)
    val pool = Executors.newFixedThreadPool(12)
    val accepted = try {
        pool.invokeAll((1..12).map { index -> Callable {
            runCatching { controller.admitClient("Node#${index.toString().padStart(6, '0')}", UUID.randomUUID()) }
                .getOrNull()
        } }).mapNotNull { it.get() }
    } finally { pool.shutdownNow() }
    check(accepted.size == 4)
    check(accepted.count { it.role == ClusterController.Role.BRIDGE } == 1)
    val bridge = accepted.single { it.role == ClusterController.Role.BRIDGE }
    check(controller.state.value.clients.size + 1 == 5)
    check(runCatching { controller.admitClient(bridge.nodeId, UUID.randomUUID()) }.isFailure)
    controller.release(UUID.randomUUID())
    check(controller.state.value.clients.size == 4)
    controller.release(bridge.sessionId)
    val replacement = controller.admitClient("Replacement#123456", UUID.randomUUID())
    check(replacement.role == ClusterController.Role.BRIDGE)

    val client = ClusterController(replacement.nodeId, group, owner)
    val homeSession = UUID.randomUUID()
    val adjacentSession = UUID.randomUUID()
    check(runCatching { client.attachAdjacent(UUID.randomUUID(), "Other#000001", adjacentSession) }.isFailure)
    client.attachOwner(owner, homeSession, ClusterController.Role.BRIDGE)
    client.attachAdjacent(UUID.randomUUID(), "Other#000001", adjacentSession)
    check(client.state.value.adjacent?.sessionId == adjacentSession)
    client.release(UUID.randomUUID())
    check(client.state.value.ownerSession == homeSession)
    client.release(homeSession)
    check(client.state.value.adjacent == null && client.state.value.localRole == ClusterController.Role.CLIENT)

    var now = 0L
    val routes = MeshRoutingTable { now }
    routes.refreshDirect(owner, group, homeSession)
    val far = MeshRoutingTable.Destination("Far#000001", group)
    check(routes.learn(far, homeSession, 2))
    now = 29_999
    check(routes.lookup(owner, group) != null && routes.lookup(far.nodeId, group) != null)
    now = 30_000
    check(routes.evictExpired() == setOf(homeSession))
    check(routes.routes.value.isEmpty())
    check(!routes.learn(far, homeSession, 2))
    routes.refreshDirect(owner, group, homeSession)
    check(routes.learn(far, homeSession, 2))
    now += 20_000
    routes.refreshDirect(owner, group, homeSession)
    now += 10_000
    check(routes.lookup(far.nodeId, group) == null) // A next-hop ping cannot renew a distant advertisement.
    check(routes.lookup(owner, group) != null)
    routes.removeSession(homeSession)
    check(routes.routes.value.isEmpty())

    // Verify the maintenance cadence on a real coroutine scheduler (about 3 seconds).
    val pings = kotlinx.coroutines.channels.Channel<Long>(4)
    val timer = routes.startMaintenance(this, { pings.send(System.nanoTime()) }, {})
    withTimeout(8_000) {
        val first = pings.receive()
        val second = pings.receive()
        check((second - first) / 1_000_000 in 2_900..4_500)
    }
    timer.cancel()

    // Test the codec over actual raw TCP, including small fragmented writes and back-to-back frames.
    ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { listener ->
        var serverError: Throwable? = null
        val serverThread = thread {
            try {
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    repeat(2) { MeshPacket.readFrom(socket.getInputStream()).writeTo(socket.getOutputStream()) }
                }
            } catch (e: Throwable) { serverError = e }
        }
        Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
            socket.soTimeout = 5_000
            val packet = MeshPacket(PacketType.TEXT, owner, group, "hello".toByteArray()).forNextHop()
            val frame = packet.toByteArray()
            repeat(2) { frame.asList().chunked(3).forEach { socket.getOutputStream().write(it.toByteArray()) } }
            repeat(2) { check(MeshPacket.readFrom(socket.getInputStream()).toByteArray().contentEquals(frame)) }
        }
        serverThread.join(6_000)
        check(!serverThread.isAlive)
        serverError?.let { throw it }
    }
    println("Step 2 checks passed: concurrent admission, bridge lifecycle, route expiration, heartbeat cadence, TCP frames")
}
