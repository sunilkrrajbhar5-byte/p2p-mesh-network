// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import kotlinx.coroutines.*
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/** Desktop owner on an existing LAN/hotspot. OS access-point creation is not portable JVM functionality. */
class DesktopMeshHost(
    val identity: String,
    val clusterId: UUID,
    private val bindAddress: String,
    cache: File,
    html: String,
    origin: String,
    joinSecret: String,
    tls: SSLServerSocketFactory?,
    private val tcpPort: Int = 8888,
    webPort: Int = 8080,
    private val onLocalPacket: RelayPacketFilter.LocalConsumer = RelayPacketFilter.LocalConsumer { _, _ -> }
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controller = ClusterController(identity, clusterId, identity)
    private val routes = MeshRoutingTable()
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val slots = Semaphore(4)
    private val timer = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private var server: ServerSocket? = null
    private var gatewayRef: UniversalMeshGateway? = null
    private class Session(val socket: Socket, val id: UUID, val peer: String, scope: CoroutineScope) {
        val queue = PriorityQueueManager(scope)
        @Volatile var heartbeat = System.nanoTime() / 1_000_000
    }
    private val acks = AckManager(identity, { session, packet -> send(session, packet) })
    val files = ChunkManager(identity, File(cache, "native-host"), acks, { packet ->
        val session = gatewayRef?.routes(header(packet))?.firstOrNull()
            ?: routes.lookup(checkNotNull(packet.targetId), packet.clusterId)?.sessionId
            ?: error("No route")
        send(session, packet)
    })
    private val relay = RelayPacketFilter(identity, RelayPacketFilter.LocalConsumer { h, p ->
        if (h.type == PacketType.DELIVERY_ACK) acks.accept(h, p)
        else if (!files.consume(h, p)) onLocalPacket.consume(h, p)
    }, nextHops = { h, inbound ->
        if (h.targetId == null || h.targetId == MeshPacket.BROADCAST) sessions.keys.filter { it != inbound }
        else routes.lookup(h.targetId, h.clusterId)?.sessionId?.let(::listOf).orEmpty()
    }, writer = RelayPacketFilter.FrameWriter { session, frame, scratch -> writeRaw(session, frame, scratch) },
        onFrame = acks::observe, routeOverride = acks::reverseRoute)
    val gateway = UniversalMeshGateway(identity, clusterId, relay, File(cache, "browser"), html,
        origin, joinSecret, tls, bindAddress, webPort,
        nativePeers = { sessions.values.map { UniversalMeshGateway.Peer(it.peer, clusterId, 1) } },
        onJoin = { id, session -> controller.admitClient(id, session, bridgeCapable = false); Unit },
        onLeave = { controller.release(it) }, advertiseHost = false).also { gatewayRef = it }

    fun start() {
        check(server == null)
        val listener = ServerSocket(tcpPort, 8, InetAddress.getByName(bindAddress))
        server = listener
        try { gateway.startGateway() } catch (e: Exception) { close(); throw e }
        scope.launch {
            while (isActive && !listener.isClosed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                if (!slots.tryAcquire()) { socket.close(); continue }
                launch { readClient(socket) }
            }
        }
        scope.launch {
            while (isActive) {
                delay(3_000)
                for (session in sessions.values) {
                    if (System.nanoTime() / 1_000_000 - session.heartbeat > 30_000) { session.socket.close(); continue }
                    val role = controller.roleFor(session.id) ?: continue
                    launch { runCatching { send(session.id, heartbeat(session.peer, role)) }.onFailure { session.socket.close() } }
                }
            }
        }
    }
    private fun heartbeat(target: String, role: ClusterController.Role) = MeshPacket(PacketType.HEARTBEAT,
        identity, clusterId, byteArrayOf(role.code.toByte()), target, hopLimit = 1)
    private suspend fun readClient(socket: Socket) {
        val id = UUID.randomUUID()
        try {
            socket.tcpNoDelay = true; socket.soTimeout = 10_000
            val hello = relay.readControl(socket.getInputStream())
            validateHello(hello)
            val member = controller.admitClient(hello.senderId, id)
            heartbeat(hello.senderId, member.role).forNextHop().writeTo(socket.getOutputStream())
            val session = Session(socket, id, hello.senderId, scope)
            sessions[id] = session; routes.refreshDirect(session.peer, clusterId, id)
            socket.soTimeout = 30_000
            while (currentCoroutineContext().isActive) relay.receive(id, socket.getInputStream()) { packet ->
                validateHello(packet); require(packet.senderId == session.peer)
                session.heartbeat = System.nanoTime() / 1_000_000; routes.refreshDirect(session.peer, clusterId, id)
            }
        } catch (_: Exception) { /* Close only this invalid/timed-out session. */ }
        finally {
            socket.close(); sessions.remove(id)?.queue?.close(); controller.release(id); routes.removeSession(id); slots.release()
        }
    }
    private fun validateHello(packet: MeshPacket) {
        require(packet.type == PacketType.HEARTBEAT && packet.targetId == identity && packet.clusterId == clusterId &&
            packet.payload.isEmpty() && packet.hopLimit == 0 && packet.currentHops == 1 && packet.senderId != identity)
    }
    suspend fun send(session: UUID, packet: MeshPacket) {
        if (gatewayRef?.sendFromHost(session, packet) == true) return
        val bytes = packet.forNextHop().toByteArray()
        try { check(writeRaw(session, ByteBuffer.wrap(bytes), ByteArray(8_192))) { "Session unavailable" } }
        finally { bytes.fill(0) }
    }
    private suspend fun writeRaw(id: UUID, frame: ByteBuffer, scratch: ByteArray): Boolean {
        val session = sessions[id] ?: return false
        val h = MeshPacket.inspectFrame(frame)
        session.queue.execute(h.type) {
            withContext(NonCancellable + Dispatchers.IO) {
                val deadline = timer.schedule({ runCatching { session.socket.close() } }, 10, TimeUnit.SECONDS)
                try {
                    val view = frame.duplicate()
                    val output = session.socket.getOutputStream()
                    while (view.hasRemaining()) { val size = minOf(scratch.size, view.remaining()); view.get(scratch, 0, size); output.write(scratch, 0, size) }
                    output.flush()
                } finally { deadline.cancel(false); scratch.fill(0) }
            }
        }
        return true
    }
    override fun close() = runBlocking {
        server?.close(); sessions.values.forEach { it.socket.close() }
        gateway.shutdown(); relay.close(); scope.cancel(); files.close(true); acks.close(); relay.awaitWiped(); timer.shutdownNow()
        Unit
    }
    private fun header(p: MeshPacket) = MeshPacket.FrameHeader(p.type, p.packetId, p.senderId, p.targetId, p.clusterId,
        p.hopLimit, p.currentHops, p.timestamp, 0, 0)
}

/** Args: bindIP origin identity clusterUUID WebMeshClient.html cacheDirectory server.p12 */
fun main(args: Array<String>) {
    require(args.size == 7) { "bindIP https://name:8080 Nickname#123456 clusterUUID html cache server.p12" }
    val password = checkNotNull(System.getenv("MESH_TLS_PASSWORD")).toCharArray()
    val store = KeyStore.getInstance("PKCS12")
    val context = try {
        File(args[6]).inputStream().use { store.load(it, password) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
    } finally { password.fill('\u0000') }
    val host = DesktopMeshHost(args[2], UUID.fromString(args[3]), args[0], File(args[5]), File(args[4]).readText(),
        args[1], checkNotNull(System.getenv("MESH_JOIN_SECRET")), context.serverSocketFactory)
    Runtime.getRuntime().addShutdownHook(Thread { host.close() })
    host.start()
    println("Mesh host ${args[2]} at ${args[1]}; raw TCP 8888. Join the same local network.")
    java.util.concurrent.CountDownLatch(1).await()
}
