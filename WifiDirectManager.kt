/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Hotspot lifecycle adapted from TrebleShot HotspotManager.kt:
 * Copyright (C) 2019 Veli Tasalı. P2pDaemon.kt: Copyright (C) 2020 Veli Tasalı.
 * Source revision f9c927e5b82f3f9cd968e5ddf31109f8e5e8b9e4.
 * TCP accept/configure/dispatch/finally-close structure adapted from Monora
 * CoolSocket 2.0-rc16 (MIT). See THIRD_PARTY_NOTICES.txt and licenses/.
 * Changes (2026): Kotlin coroutines, modern permission checks, bounded fallback,
 * binary MeshPacket sessions, membership admission, heartbeat and bridge links.
 * No warranty; distributed under GPL version 2 or, at your option, any later version.
 */
@file:Suppress("DEPRECATION")

package com.example.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * API 26+, compileSdk/targetSdk 34+. Own from a visible Activity or connectedDevice
 * foreground service. Lifecycle methods are serialized on Main; socket I/O uses IO.
 * P2P and LOHS are alternative home transports. A second Wi-Fi Network is optional
 * for a bridge. Never binds the entire process to a Network or assumes an OEM IP.
 * Raw TCP carries the Step 1 frame; it is NOT TrebleShot's TLS/uprotocol wire format.
 */
@SuppressLint("MissingPermission") // Each public Wi-Fi operation checks grants first.
class WifiDirectManager(
    context: Context,
    val cluster: ClusterController,
    val routingTable: MeshRoutingTable = MeshRoutingTable(SystemClock::elapsedRealtime),
    val consent: PeerConsentController = PeerConsentController()
) {
    sealed class State {
        data object Idle : State()
        data class Starting(val operation: String) : State()
        data class DirectOwner(val address: String) : State()
        data class DirectClient(val owner: String) : State()
        data class HotspotOwner(val invitation: HotspotInvitation) : State()
        data class HotspotClient(val owner: String) : State()
        data class NeedsHotspotInvitation(val reason: String) : State()
        data class Failed(val reason: String) : State()
        data object Closed : State()
    }

    /** Exchange via QR/BLE/manual UI. Credentials are never written to logs. */
    class HotspotInvitation(
        val ssid: String,
        val passphrase: String,
        val ownerId: String,
        val clusterId: UUID,
        val ownerAddress: InetAddress? = null
    ) {
        override fun toString() = "HotspotInvitation(credentials redacted, cluster=$clusterId)"
    }

    data class ReceivedPacket(val sessionId: UUID, val peerId: String, val packet: MeshPacket)
    data class Connection(val sessionId: UUID, val peerId: String, val clusterId: UUID, val adjacent: Boolean)

    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val p2p = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycle = Mutex()
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val mutablePeers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = mutablePeers.asStateFlow()
    private val mutableConnections = MutableStateFlow<List<Connection>>(emptyList())
    val connections: StateFlow<List<Connection>> = mutableConnections.asStateFlow()
    private val incoming = Channel<ReceivedPacket>(64)
    @Volatile private var relay: RelayPacketFilter? = null
    /** One application collector; bounded backpressure, no silent packet dropping. */
    val packets: Flow<ReceivedPacket> = incoming.receiveAsFlow()
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(ClusterController.MAX_CLIENTS)
    private val publishing = Any()
    private val transportEpoch = AtomicLong()
    private var server: ServerSocket? = null
    private var serverJob: Job? = null
    private var channel: WifiP2pManager.Channel? = null
    private var registered = false
    private var closed = false
    private var directReady = false
    private var hotspotEpoch = 0L
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val requests = mutableSetOf<ConnectivityManager.NetworkCallback>()
    private val requestedNetworks = mutableMapOf<ConnectivityManager.NetworkCallback, Network>()
    private var homeRequest: ConnectivityManager.NetworkCallback? = null
    private var bridgeRequest: ConnectivityManager.NetworkCallback? = null

    private class Session(
        val id: UUID,
        val socket: Socket,
        val clusterId: UUID,
        val accepted: Boolean,
        val adjacent: Boolean,
        val network: Network?,
        scope: CoroutineScope
    ) {
        @Volatile var peerId: String = ""
        @Volatile var lastHeartbeat = SystemClock.elapsedRealtime()
        val priority = PriorityQueueManager(scope)
        val closed = AtomicBoolean(false)
        var job: Job? = null
    }

    private val maintenance = routingTable.startMaintenance(scope, sendHeartbeats = {
        val now = SystemClock.elapsedRealtime()
        sessions.values.forEach { session ->
            if (now - session.lastHeartbeat >= MeshRoutingTable.ROUTE_TIMEOUT_MS) {
                disconnect(session.id)
            } else {
                val role = if (session.accepted) cluster.roleFor(session.id) else null
                if (session.accepted && role == null) disconnect(session.id)
                else enqueue(session, heartbeat(session.clusterId, session.peerId, role))
            }
        }
    }, onExpired = { ids -> ids.forEach(::disconnect) })

    /** Request these from an Activity Result permission launcher before calling start/join. */
    fun missingPermissions(): Array<String> = requiredPermissions().filter {
        app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()

    private fun checkPermissions() {
        check(!closed) { "Manager closed" }
        require(Build.VERSION.SDK_INT >= 26) { "Local-only hotspot requires API 26" }
        val missing = missingPermissions()
        if (missing.isNotEmpty()) throw SecurityException("Missing runtime permissions: ${missing.joinToString()}")
    }

    private fun checkLocationMode() {
        if (!wifi.isWifiEnabled) throw NetworkResolutionException(NetworkResolution.WIFI)
        val location = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = if (Build.VERSION.SDK_INT >= 28) location.isLocationEnabled
        else location.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!enabled) throw NetworkResolutionException(NetworkResolution.LOCATION)
    }

    suspend fun discoverPeers() = onMain {
        checkPermissions()
        checkLocationMode()
        val ch = initializeP2p()
        action { p2p!!.discoverPeers(ch, it) }
    }


    data class NearbyDevice(val identity: String, val address: String, val clusterId: UUID, val seenAt: Long)
    private val nearbyState = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val nearby: StateFlow<List<NearbyDevice>> = nearbyState.asStateFlow()
    private var serviceInfo: WifiP2pDnsSdServiceInfo? = null
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null

    /** DNS-SD identity is a display claim, not cryptographic authentication. No group secret. */
    suspend fun discoverIdentities(advertise: Boolean = cluster.isOwner) = onMain {
        checkPermissions(); checkLocationMode()
        val ch = initializeP2p()
        p2p!!.setDnsSdResponseListeners(ch, { _, _, _ -> }, { domain, record, device ->
            if (domain.equals("srmesh._srmesh._tcp.local.", ignoreCase = true) && record["consent"] == "1") {
                val id = record["identity"]
                val group = runCatching { UUID.fromString(record["cluster"]) }.getOrNull()
                if (id != null && MeshPacket.isValidIdentity(id) && id != cluster.localId && group != null) {
                    val now = SystemClock.elapsedRealtime()
                    nearbyState.value = (nearbyState.value.filter { it.address != device.deviceAddress && now - it.seenAt < 30_000 }
                        + NearbyDevice(id, device.deviceAddress, group, now)).takeLast(64)
                }
            }
        })
        if (advertise && serviceInfo == null) {
            val info = WifiP2pDnsSdServiceInfo.newInstance("srmesh", "_srmesh._tcp",
                mapOf("identity" to cluster.localId, "cluster" to cluster.clusterId.toString(), "consent" to "1"))
            action { p2p!!.addLocalService(ch, info, it) }; serviceInfo = info
        }
        if (serviceRequest == null) {
            val request = WifiP2pDnsSdServiceRequest.newInstance("_srmesh._tcp")
            action { p2p!!.addServiceRequest(ch, request, it) }; serviceRequest = request
        }
        nearbyState.value = nearbyState.value.filter { SystemClock.elapsedRealtime() - it.seenAt < 30_000 }
        action { p2p!!.discoverServices(ch, it) }
    }

    /** Explicit AP mode for browser clients; they join the SSID through system Wi-Fi settings. */
    suspend fun startLocalHotspot(): State = onMain {
        checkPermissions()
        check(cluster.isOwner && mutableState.value is State.Idle)
        try { startHotspotOwner() }
        catch (e: Exception) { resetHome(); throw e }
    }

    /** Owner startup automatically falls back after a bounded Direct failure/timeout. */
    suspend fun startOwner(): State = onMain {
        checkPermissions()
        checkLocationMode()
        check(cluster.isOwner) { "This identity is not the configured owner" }
        check(mutableState.value is State.Idle) { "Stop the previous home connection first" }
        mutableState.value = State.Starting("Wi-Fi Direct owner")
        try {
            val ch = initializeP2p()
            action { p2p!!.createGroup(ch, it) }
            val info = awaitGroup(owner = true)
            val address = requireNotNull(info.groupOwnerAddress)
            startServer(address)
            directReady = true
            State.DirectOwner(address.hostAddress ?: address.toString()).also { mutableState.value = it }
        } catch (e: CancellationException) {
            resetHome()
            throw e
        } catch (e: RequestDeclinedException) {
            resetHome(); throw e
        } catch (e: SecurityException) {
            resetHome(); mutableState.value = State.Failed(e.message ?: "Permission denied"); throw e
        } catch (e: Exception) {
            releaseP2p()
            startHotspotOwner()
        }
    }

    /**
     * Join an existing owner, never silently become a competing owner. On Direct
     * failure, a provided hotspot invitation is used; otherwise UI must obtain one.
     */
    suspend fun connectDirect(deviceAddress: String, fallback: HotspotInvitation? = null): State = onMain {
        checkPermissions()
        checkLocationMode()
        check(!cluster.isOwner)
        check(mutableState.value is State.Idle)
        mutableState.value = State.Starting("Wi-Fi Direct client")
        try {
            val ch = initializeP2p()
            val config = WifiP2pConfig().apply {
                this.deviceAddress = deviceAddress
                wps.setup = WpsInfo.PBC
                groupOwnerIntent = 0
            }
            action { p2p!!.connect(ch, config, it) }
            val info = awaitGroup(owner = false)
            connectSocket(requireNotNull(info.groupOwnerAddress), null, cluster.clusterId, cluster.ownerId, false)
            directReady = true
            State.DirectClient(cluster.ownerId).also { mutableState.value = it }
        } catch (e: CancellationException) {
            resetHome(); throw e
        } catch (e: RequestDeclinedException) {
            resetHome(); throw e
        } catch (e: SecurityException) {
            resetHome(); mutableState.value = State.Failed(e.message ?: "Permission denied"); throw e
        } catch (e: Exception) {
            releaseP2p()
            if (fallback == null) State.NeedsHotspotInvitation(e.message ?: "Direct connection failed")
                .also { mutableState.value = it }
            else joinHotspotInternal(fallback)
        }
    }

    suspend fun joinHotspot(invitation: HotspotInvitation): State = onMain {
        checkPermissions()
        check(!cluster.isOwner)
        check(mutableState.value is State.Idle || mutableState.value is State.NeedsHotspotInvitation)
        joinHotspotInternal(invitation)
    }

    private suspend fun joinHotspotInternal(invitation: HotspotInvitation): State {
        require(invitation.clusterId == cluster.clusterId && invitation.ownerId == cluster.ownerId)
        mutableState.value = State.Starting("Local hotspot client; approve Android's Wi-Fi dialog")
        try {
            val endpoint = requestHotspotNetwork(invitation)
            homeRequest = endpoint.callback
            connectSocket(endpoint.address, endpoint.network, invitation.clusterId, invitation.ownerId, false)
            return State.HotspotClient(invitation.ownerId).also { mutableState.value = it }
        } catch (e: Exception) {
            releaseRequest(homeRequest); homeRequest = null
            if (e is CancellationException) { mutableState.value = State.Idle; throw e }
            mutableState.value = State.Failed(e.message ?: "Hotspot join failed")
            throw e
        }
    }

    /**
     * Optional bridge attempt using a separate Network-bound socket, never a second
     * P2P connect(). OEM concurrency is not assumed. The home owner must issue another
     * heartbeat after the adjacent handshake before the link is marked active.
     * On unsupported hardware report failure; store-and-forward requires a later layer.
     */
    suspend fun connectAdjacent(invitation: HotspotInvitation): UUID = onMain {
        checkPermissions()
        check(cluster.state.value.localRole == ClusterController.Role.BRIDGE)
        check(cluster.state.value.adjacent == null && bridgeRequest == null)
        require(invitation.clusterId != cluster.clusterId)
        val homeId = requireNotNull(cluster.state.value.ownerSession)
        val home = requireNotNull(sessions[homeId])
        var adjacentSession: UUID? = null
        try {
            val endpoint = requestHotspotNetwork(invitation)
            bridgeRequest = endpoint.callback
            adjacentSession = connectSocket(endpoint.address, endpoint.network,
                invitation.clusterId, invitation.ownerId, true)
            val heartbeatBefore = home.lastHeartbeat
            withTimeout(10_000) {
                while (sessions[homeId] !== home || home.lastHeartbeat <= heartbeatBefore) {
                    check(sessions[homeId] === home) { "Home link lost: concurrent Wi-Fi unsupported" }
                    delay(100)
                }
            }
            synchronized(publishing) {
                check(sessions[homeId] === home && sessions.containsKey(adjacentSession)) {
                    "A bridge link closed during verification"
                }
                cluster.attachAdjacent(invitation.clusterId, invitation.ownerId, adjacentSession)
            }
            adjacentSession
        } catch (e: Exception) {
            adjacentSession?.let(::disconnect)
            releaseRequest(bridgeRequest); bridgeRequest = null
            throw e
        }
    }

    /** Consumes one hop from the packet budget. Completion means queued, not delivered. */
    fun send(sessionId: UUID, packet: MeshPacket): Boolean {
        val session = sessions[sessionId] ?: return false
        require(packet.type != PacketType.HEARTBEAT) { "Heartbeats are owned by the connection layer" }
        return enqueue(session, packet)
    }

    /** Endpoint transfer API: await actual write, rather than building an unbounded chunk backlog. */
    suspend fun sendAwait(sessionId: UUID, packet: MeshPacket) {
        val session = sessions[sessionId] ?: throw IOException("Next-hop session missing")
        require(packet.type != PacketType.HEARTBEAT && packet.canForward)
        val outgoing = packet.forNextHop()
        session.priority.execute(packet.type) { writePacketNow(session, outgoing) }
    }

    /** Install before starting any sockets. Relay mode bypasses the heap-backed packets Flow. */
    fun installRelay(filter: RelayPacketFilter) {
        check(server == null && sockets.isEmpty() && sessions.isEmpty() && relay == null)
        relay = filter
    }

    /**
     * Writes an already-advanced raw frame to the next hop's established IP socket.
     * Shares the per-socket priority scheduler with heartbeats. The caller's lease
     * remains held until completion; cancellation cannot release it during I/O.
     */
    suspend fun writeRelayFrame(sessionId: UUID, frame: ByteBuffer, scratch: ByteArray): Boolean {
        val session = sessions[sessionId] ?: return false
        require(frame.position() == 0 && frame.remaining() <= MeshPacket.MAX_FRAME_SIZE && scratch.isNotEmpty())
        return try {
            val type = PacketType.fromWireCode(frame.get(1).toInt() and 0xff)
            session.priority.execute(type) {
                withContext(Dispatchers.IO + NonCancellable) {
                    if (session.closed.get()) throw IOException("Session closed")
                    val timeout = scope.launch { delay(WRITE_TIMEOUT_MS); runCatching { session.socket.close() } }
                    try {
                        val bytes = frame.duplicate()
                        val output = session.socket.getOutputStream()
                        while (bytes.hasRemaining()) {
                            val size = minOf(bytes.remaining(), scratch.size)
                            bytes.get(scratch, 0, size)
                            try { output.write(scratch, 0, size) }
                            finally { scratch.fill(0) }
                        }
                    } catch (e: IOException) {
                        closeSession(session)
                        throw e
                    } finally { scratch.fill(0); timeout.cancel() }
                }
            }
            true
        } catch (_: IOException) { false }
    }

    private fun enqueue(session: Session, packet: MeshPacket): Boolean {
        if (session.closed.get() || !packet.canForward) return false
        val outgoing = packet.forNextHop()
        if (session.priority.offer(packet.type) { writePacketNow(session, outgoing) }) return true
        disconnect(session.id) // Bounded queue: a slow peer must not consume unbounded memory.
        return false
    }

    private suspend fun writePacketNow(session: Session, packet: MeshPacket) = withContext(Dispatchers.IO + NonCancellable) {
        if (session.closed.get()) throw IOException("Session closed")
        val timeout = scope.launch { delay(WRITE_TIMEOUT_MS); runCatching { session.socket.close() } }
        val bytes = packet.toByteArray()
        try { session.socket.getOutputStream().write(bytes) }
        catch (e: IOException) { closeSession(session); throw e }
        finally { bytes.fill(0); timeout.cancel() }
    }

    private fun heartbeat(group: UUID, target: String, role: ClusterController.Role? = null) = MeshPacket(
        type = PacketType.HEARTBEAT, senderId = cluster.localId, clusterId = group,
        targetId = target, hopLimit = 1,
        payload = role?.let { byteArrayOf(it.code.toByte()) } ?: byteArrayOf()
    )

    private suspend fun startServer(address: InetAddress?) {
        check(server == null)
        val listener = ServerSocket()
        val epoch = transportEpoch.get()
        try {
            withContext(Dispatchers.IO) {
                listener.reuseAddress = true
                listener.bind(InetSocketAddress(address, PORT), ClusterController.MAX_CLIENTS)
            }
            server = listener
            serverJob = scope.launch(Dispatchers.IO) {
                try {
                    while (isActive && !listener.isClosed) {
                        val socket = listener.accept()
                        if (epoch != transportEpoch.get()) { socket.close(); break }
                        if (!slots.tryAcquire()) { socket.close(); continue }
                        sockets.add(socket)
                        launchSession(socket, cluster.clusterId, accepted = true, adjacent = false,
                            network = null, expectedOwner = null, releaseSlot = true, epoch = epoch)
                    }
                } catch (e: IOException) {
                    if (!listener.isClosed) scope.launch { mutableState.value = State.Failed("TCP listener failed: ${e.message}") }
                } finally { runCatching { listener.close() } }
            }
        } catch (e: Exception) { listener.close(); throw e }
    }

    private suspend fun connectSocket(
        address: InetAddress, network: Network?, group: UUID, owner: String, adjacent: Boolean
    ): UUID {
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        sockets.add(socket)
        val deadline = scope.launch { delay(CONNECT_TIMEOUT_MS); runCatching { socket.close() } }
        try {
            withContext(Dispatchers.IO) { socket.connect(InetSocketAddress(address, PORT), CONNECT_TIMEOUT_MS.toInt()) }
            deadline.cancel()
            val id = deadline(HANDSHAKE_TIMEOUT_MS) {
                launchSession(socket, group, false, adjacent, network, owner, false, transportEpoch.get()).await()
            }
            check(sessions.containsKey(id) && !socket.isClosed) { "Connection closed during startup" }
            return id
        } catch (e: Exception) {
            runCatching { socket.close() }; sockets.remove(socket); throw e
        } finally { deadline.cancel() }
    }

    /** CoolSocket-style per-client dispatch, with explicit ownership/finally-close. */
    private fun launchSession(
        socket: Socket, group: UUID, accepted: Boolean, adjacent: Boolean,
        network: Network?, expectedOwner: String?, releaseSlot: Boolean, epoch: Long
    ): CompletableDeferred<UUID> {
        val ready = CompletableDeferred<UUID>()
        val session = Session(UUID.randomUUID(), socket, group, accepted, adjacent, network, scope)
        session.job = scope.launch(Dispatchers.IO) {
            val handshakeDeadline = scope.launch { delay(HANDSHAKE_TIMEOUT_MS); runCatching { socket.close() } }
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS.toInt()
                if (accepted) {
                    val hello = relay?.readControl(socket.getInputStream()) ?: MeshPacket.readFrom(socket.getInputStream())
                    validateHeartbeat(hello, group, cluster.localId)
                    require(hello.payload.contentEquals(byteArrayOf(67))) { "Peer must support explicit admission" }
                    session.peerId = hello.senderId
                    if (!consent.ask(PeerConsentController.Request(session.id, hello.senderId,
                            PeerConsentController.Kind.CONNECTION))) {
                        MeshPacket(PacketType.HEARTBEAT, cluster.localId, group, byteArrayOf(127),
                            hello.senderId, hopLimit = 1).forNextHop().writeTo(socket.getOutputStream())
                        socket.getOutputStream().flush()
                        throw RequestDeclinedException()
                    }
                    val member = cluster.admitClient(hello.senderId, session.id)
                    session.peerId = member.nodeId
                    heartbeat(group, session.peerId, member.role).forNextHop().writeTo(socket.getOutputStream())
                } else {
                    val owner = requireNotNull(expectedOwner)
                    MeshPacket(PacketType.HEARTBEAT, cluster.localId, group, byteArrayOf(67), owner, hopLimit = 1)
                        .forNextHop().writeTo(socket.getOutputStream())
                    val welcome = relay?.readControl(socket.getInputStream()) ?: MeshPacket.readFrom(socket.getInputStream())
                    validateHeartbeat(welcome, group, cluster.localId)
                    require(welcome.senderId == owner) { "Unexpected cluster owner" }
                    if (welcome.payload.contentEquals(byteArrayOf(127))) throw RequestDeclinedException()
                    val role = readRole(welcome)
                    if (!adjacent) cluster.attachOwner(owner, session.id, role)
                    session.peerId = owner
                }
                handshakeDeadline.cancel()
                socket.soTimeout = MeshRoutingTable.ROUTE_TIMEOUT_MS.toInt()
                session.lastHeartbeat = SystemClock.elapsedRealtime()
                synchronized(publishing) {
                    check(epoch == transportEpoch.get() && !socket.isClosed) { "Transport stopped during handshake" }
                    sessions[session.id] = session
                    routingTable.refreshDirect(session.peerId, group, session.id)
                    publishConnections()
                }
                ready.complete(session.id)
                while (isActive) {
                    val rawRelay = relay
                    if (rawRelay != null) {
                        rawRelay.receive(session.id, socket.getInputStream()) { processHeartbeat(session, it) }
                        continue
                    }
                    val packet = MeshPacket.readFrom(socket.getInputStream())
                    require(packet.currentHops > 0) { "Unsent frame" }
                    if (packet.type == PacketType.HEARTBEAT) {
                        processHeartbeat(session, packet)
                    } else {
                        incoming.send(ReceivedPacket(session.id, session.peerId, packet))
                    }
                }
            } catch (e: Exception) {
                ready.completeExceptionally(e)
            } finally {
                handshakeDeadline.cancel()
                closeSession(session)
                if (releaseSlot) slots.release()
            }
        }
        return ready
    }

    private fun processHeartbeat(session: Session, packet: MeshPacket) {
        validateHeartbeat(packet, session.clusterId, cluster.localId)
        require(packet.senderId == session.peerId) { "Heartbeat identity changed" }
        if (session.accepted) require(packet.payload.isEmpty())
        else {
            val role = readRole(packet)
            if (!session.adjacent) {
                cluster.updateRole(session.id, role)
                if (role != ClusterController.Role.BRIDGE)
                    sessions.values.filter { it.adjacent }.forEach(::closeSession)
            }
        }
        synchronized(publishing) {
            if (!session.closed.get() && sessions[session.id] === session) {
                session.lastHeartbeat = SystemClock.elapsedRealtime()
                routingTable.refreshDirect(session.peerId, session.clusterId, session.id)
            }
        }
    }

    private fun validateHeartbeat(packet: MeshPacket, group: UUID, target: String) {
        require(packet.type == PacketType.HEARTBEAT && packet.clusterId == group && packet.targetId == target &&
            packet.hopLimit == 0 && packet.currentHops == 1) { "Invalid connection heartbeat" }
        require(packet.senderId != cluster.localId)
    }
    private fun readRole(packet: MeshPacket): ClusterController.Role {
        val payload = packet.payload
        require(payload.size == 1) { "Missing role assignment" }
        return ClusterController.Role.fromCode(payload[0].toInt() and 0xff).also {
            require(it != ClusterController.Role.OWNER)
        }
    }

    fun disconnect(sessionId: UUID) { sessions[sessionId]?.let(::closeSession) }

    private fun closeSession(session: Session) {
        if (!session.closed.compareAndSet(false, true)) return
        consent.cancel(session.id)
        session.priority.close()
        runCatching { session.socket.close() }
        session.job?.cancel()
        synchronized(publishing) {
            sessions.remove(session.id, session)
            routingTable.removeSession(session.id)
        }
        sockets.remove(session.socket)
        cluster.release(session.id)
        publishConnections()
        if (!session.accepted && !session.adjacent && cluster.state.value.ownerSession == null) {
            sessions.values.filter { it.adjacent }.forEach(::closeSession)
            scope.launch {
                if (cluster.state.value.ownerSession != null) return@launch
                releaseRequest(bridgeRequest); bridgeRequest = null
                if (!closed && (mutableState.value is State.DirectClient || mutableState.value is State.HotspotClient))
                    mutableState.value = State.Failed("Home owner connection closed")
            }
        } else if (session.adjacent) scope.launch {
            if (requestedNetworks[bridgeRequest] == session.network) {
                releaseRequest(bridgeRequest); bridgeRequest = null
            }
        }
    }

    private fun publishConnections() = synchronized(publishing) {
        mutableConnections.value = sessions.values.map { Connection(it.id, it.peerId, it.clusterId, it.adjacent) }
    }

    private suspend fun startHotspotOwner(): State {
        mutableState.value = State.Starting("Local-only hotspot fallback")
        val epoch = ++hotspotEpoch
        val ready = CompletableDeferred<HotspotInvitation>()
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                if (epoch != hotspotEpoch || closed || !ready.isActive) { value.close(); return }
                reservation = value
                try {
                    val ssid: String
                    val passphrase: String
                    if (Build.VERSION.SDK_INT >= 30) {
                        val config = value.softApConfiguration
                        ssid = requireNotNull(config.ssid)
                        passphrase = requireNotNull(config.passphrase)
                    } else {
                        val config = requireNotNull(value.wifiConfiguration)
                        ssid = requireNotNull(config.SSID)
                        passphrase = requireNotNull(config.preSharedKey)
                    }
                    ready.complete(HotspotInvitation(ssid, passphrase, cluster.localId, cluster.clusterId))
                } catch (e: Exception) { ready.completeExceptionally(e) }
            }
            override fun onStopped() {
                if (epoch != hotspotEpoch) return
                reservation = null
                ready.completeExceptionally(IOException("Hotspot stopped"))
                scope.launch {
                    stopSockets()
                    mutableState.value = State.Failed("Local-only hotspot stopped")
                }
            }
            override fun onFailed(reason: Int) {
                if (epoch != hotspotEpoch || closed || !ready.isActive) return
                val failure = if (reason == WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE)
                    NetworkResolutionException(NetworkResolution.HOTSPOT)
                else IOException("Hotspot unavailable / हॉटस्पॉट उपलब्ध नहीं है। Check Wi-Fi settings and retry.")
                ready.completeExceptionally(failure)
            }
        }
        try {
            if (Build.VERSION.SDK_INT < 33) checkLocationMode()
            wifi.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))
            val invitation = deadline(GROUP_TIMEOUT_MS) { ready.await() }
            startServer(null) // LOHS exposes no portable host-interface API; bind all local addresses.
            return State.HotspotOwner(invitation).also { mutableState.value = it }
        } catch (e: Exception) {
            ++hotspotEpoch
            reservation?.close(); reservation = null
            ready.cancel()
            mutableState.value = if (e is CancellationException) State.Idle else State.Failed(e.message ?: "Hotspot failure")
            throw e
        }
    }

    private data class Endpoint(
        val network: Network, val address: InetAddress, val callback: ConnectivityManager.NetworkCallback
    )

    private suspend fun requestHotspotNetwork(invitation: HotspotInvitation): Endpoint {
        check(Build.VERSION.SDK_INT >= 29) {
            "API 26-28: join the hotspot in system Wi-Fi settings, then use connectOnNetwork()"
        }
        val result = CompletableDeferred<Endpoint>()
        val specifier = WifiNetworkSpecifier.Builder().setSsid(invitation.ssid)
            .setWpa2Passphrase(invitation.passphrase).build()
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (this !in requests) return
                requestedNetworks[this] = network
                val address = invitation.ownerAddress ?: properties.routes
                    .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
                if (address != null) result.complete(Endpoint(network, address, this))
            }
            override fun onUnavailable() { result.completeExceptionally(IOException("Wi-Fi request denied/unavailable")) }
            override fun onLost(network: Network) {
                result.completeExceptionally(IOException("Hotspot network lost"))
                sessions.values.filter { it.network == network }.forEach { disconnect(it.id) }
            }
        }
        requests.add(callback)
        try {
            // Android prompts the user. No Internet capability and no process-wide binding.
            connectivity.requestNetwork(request, callback, Handler(Looper.getMainLooper()))
            return deadline(NETWORK_TIMEOUT_MS) { result.await() }
        } catch (e: Exception) { releaseRequest(callback); throw e }
    }

    /** For a user-selected existing network (including manual joining on API 26-28). */
    suspend fun connectOnNetwork(network: Network, ownerAddress: InetAddress): UUID = onMain {
        checkPermissions(); check(!cluster.isOwner)
        check(mutableState.value is State.Idle || mutableState.value is State.NeedsHotspotInvitation)
        connectSocket(ownerAddress, network, cluster.clusterId, cluster.ownerId, false).also {
            mutableState.value = State.HotspotClient(cluster.ownerId)
        }
    }

    private fun releaseRequest(callback: ConnectivityManager.NetworkCallback?) {
        if (callback != null && requests.remove(callback)) {
            requestedNetworks.remove(callback)
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }

    private fun initializeP2p(): WifiP2pManager.Channel {
        val manager = p2p ?: throw IOException("Wi-Fi Direct unavailable")
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT))
            throw IOException("Wi-Fi Direct unsupported")
        if (channel == null) channel = manager.initialize(app, Looper.getMainLooper()) {
            channel = null
            if (directReady) handleDirectLoss("Wi-Fi Direct channel disconnected")
        }
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            }
            // System-only broadcasts; exported is required for broadcasts from privileged Wi-Fi components.
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(receiver, filter)
            registered = true
        }
        return requireNotNull(channel) { "Cannot initialize Wi-Fi Direct" }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ch = channel ?: return
            try {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> p2p?.requestPeers(ch) {
                        mutablePeers.value = it.deviceList.toList()
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> if (directReady) {
                        p2p?.requestConnectionInfo(ch) {
                            if (!it.groupFormed && directReady) handleDirectLoss("Wi-Fi Direct group lost")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> if (directReady &&
                        intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_DISABLED)
                        handleDirectLoss("Wi-Fi Direct disabled")
                }
            } catch (e: SecurityException) { handleDirectLoss(e.message ?: "Permission revoked") }
        }
    }

    private fun handleDirectLoss(reason: String) {
        if (!directReady) return
        directReady = false
        scope.launch {
            lifecycle.withLock {
                if (mutableState.value !is State.DirectOwner && mutableState.value !is State.DirectClient)
                    return@withLock
                stopSockets()
                releaseP2p()
                if (closed) return@withLock
                if (cluster.isOwner && missingPermissions().isEmpty()) {
                    try { startHotspotOwner() } catch (_: Exception) { /* State already reports failure. */ }
                } else mutableState.value = State.NeedsHotspotInvitation(reason)
            }
        }
    }

    private suspend fun awaitGroup(owner: Boolean): WifiP2pInfo = deadline(GROUP_TIMEOUT_MS) {
        while (true) {
            val ch = requireNotNull(channel)
            val info = suspendCancellableCoroutine<WifiP2pInfo> { continuation ->
                p2p!!.requestConnectionInfo(ch) { if (continuation.isActive) continuation.resume(it) }
            }
            if (info.groupFormed) {
                check(info.isGroupOwner == owner) { "Unexpected Wi-Fi Direct role" }
                return@deadline info
            }
            delay(300)
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }

    private suspend fun action(invoke: (WifiP2pManager.ActionListener) -> Unit) = deadline(ACTION_TIMEOUT_MS) {
        suspendCancellableCoroutine<Unit> { continuation ->
            invoke(object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onFailure(reason: Int) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (reason == WifiP2pManager.BUSY) WifiBusyException()
                        else IOException("Nearby discovery unavailable. Check Wi-Fi and permissions / Wi-Fi और अनुमतियाँ जाँचें"))
                }
            })
        }
    }

    private suspend fun releaseP2p() {
        directReady = false
        val ch = channel ?: return
        serviceInfo?.let { info -> runCatching { action { p2p?.removeLocalService(ch, info, it) } } }
        serviceRequest?.let { request -> runCatching { action { p2p?.removeServiceRequest(ch, request, it) } } }
        serviceInfo = null; serviceRequest = null; nearbyState.value = emptyList()
        // Wait for callbacks before attempting LOHS, rather than racing both radios.
        runCatching { action { p2p?.cancelConnect(ch, it) } }
        runCatching { action { p2p?.stopPeerDiscovery(ch, it) } }
        runCatching { action { p2p?.removeGroup(ch, it) } }
    }

    private fun stopSockets() {
        synchronized(publishing) { transportEpoch.incrementAndGet() }
        runCatching { server?.close() }; server = null
        serverJob?.cancel(); serverJob = null
        sessions.values.toList().forEach(::closeSession)
        sockets.toList().forEach { runCatching { it.close() } }
        sockets.clear()
    }

    /** Main-thread service-destruction hook: unblock I/O immediately, then call close(). */
    fun abortRelaySockets() { stopSockets() }

    private suspend fun resetHome() = withContext(NonCancellable) {
        stopSockets()
        ++hotspotEpoch
        reservation?.close(); reservation = null
        requests.toList().forEach(::releaseRequest)
        homeRequest = null; bridgeRequest = null
        releaseP2p()
        mutableState.value = State.Idle
    }

    suspend fun stop() = onMain { if (!closed) resetHome() }

    suspend fun close() = onMain {
        if (!closed) {
            closed = true
            resetHome()
            if (registered) { app.unregisterReceiver(receiver); registered = false }
            channel?.close(); channel = null
            maintenance.cancel()
            incoming.close()
            mutableState.value = State.Closed
            scope.cancel()
        }
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        lifecycle.withLock { block() }
    }

    companion object {
        const val PORT = 8888
        private const val ACTION_TIMEOUT_MS = 5_000L
        private const val GROUP_TIMEOUT_MS = 25_000L
        private const val NETWORK_TIMEOUT_MS = 45_000L
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val HANDSHAKE_TIMEOUT_MS = 50_000L
        private const val WRITE_TIMEOUT_MS = 10_000L

        fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /** Own timeouts are failures eligible for fallback; caller cancellation still propagates. */
    private suspend fun <T : Any> deadline(timeoutMs: Long, block: suspend () -> T): T =
        withTimeoutOrNull(timeoutMs) { block() } ?: throw IOException("Operation timed out after ${timeoutMs}ms")
}
