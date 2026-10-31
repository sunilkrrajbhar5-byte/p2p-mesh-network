// SPDX-License-Identifier: GPL-2.0-or-later
@file:Suppress("DEPRECATION")
package com.example.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * API 26+. A visible connectedDevice foreground service; "silent" means no
 * received-file saving or per-packet alerts, not a hidden Android service.
 * Uses foreground execution priority, not real-time scheduling. OEM/Doze rules
 * still apply. Run in the default app process with android:exported="false".
 * Transit payloads and relay state are never persisted. Destination file caching
 * is separately enabled through enableFileTransfers(); identity is supplied by the app.
 */
class SilentRelayService : Service() {
    private var gateway: UniversalMeshGateway? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val binder = LocalBinder()
    private var manager: WifiDirectManager? = null
    private var filter: RelayPacketFilter? = null
    private var acks: AckManager? = null
    @Volatile private var chunks: ChunkManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var renewLock: Job? = null
    private var running = false
    private var destroyed = false
    @Volatile private var consumer: RelayPacketFilter.LocalConsumer? = null

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            promoteToForeground() // Before network startup, permission dialogs, or any blocking I/O.
            if (!running) {
                running = true
                acquireLocks()
            }
        } catch (_: RuntimeException) {
            running = false
            releaseLocks()
            stopSelf()
        }
        // No Intent redelivery, persisted backlog, or automatic restart carrying sensitive data.
        return START_NOT_STICKY
    }

    inner class LocalBinder : Binder() {
        /** Configure once before opening sockets. Supply an app-owned, non-retaining consumer. */
        suspend fun configure(
            localMyId: String,
            clusterId: UUID,
            ownerId: String,
            localConsumer: RelayPacketFilter.LocalConsumer
        ) = owned {
            check(running && !destroyed) { "Start the foreground service before configuring it" }
            check(manager == null) { "Relay already configured; stop and restart to change identity" }
            val controller = ClusterController(localMyId, clusterId, ownerId)
            val transport = WifiDirectManager(applicationContext, controller)
            val acknowledgements = AckManager(localMyId, { session, packet ->
                if (gateway?.sendFromHost(session, packet) != true) transport.sendAwait(session, packet)
            },
                android.os.SystemClock::elapsedRealtime)
            acks = acknowledgements
            consumer = localConsumer
            try {
                val relay = RelayPacketFilter(
                    localMyId = localMyId,
                    localConsumer = RelayPacketFilter.LocalConsumer { header, bytes ->
                        if (header.type == PacketType.DELIVERY_ACK) acknowledgements.accept(header, bytes)
                        else if (chunks?.consume(header, bytes) != true)
                            checkNotNull(consumer) { "Local consumer unavailable" }.consume(header, bytes)
                    },
                    nextHops = { header, inbound -> chooseNextHops(transport, header, inbound) },
                    writer = RelayPacketFilter.FrameWriter { session, bytes, scratch ->
                        transport.writeRelayFrame(session, bytes, scratch)
                    },
                    onFrame = acknowledgements::observe,
                    routeOverride = acknowledgements::reverseRoute
                )
                filter = relay
                transport.installRelay(relay)
                manager = transport
            } catch (e: Exception) {
                filter?.close(); filter = null
                acknowledgements.close(); acks = null
                consumer = null
                transport.close()
                throw e
            }
        }

        fun missingPermissions(): Array<String> = transport().missingPermissions()
        val state: StateFlow<WifiDirectManager.State> get() = transport().state
        val connections: StateFlow<List<WifiDirectManager.Connection>> get() = transport().connections
        val clusterState: StateFlow<ClusterController.Snapshot> get() = transport().cluster.state
        val routes: MeshRoutingTable get() = transport().routingTable
        val acknowledgements: AckManager get() = checkNotNull(acks)

        /** Opt in only on endpoints. Transit packets never reach this app-cache writer. */
        suspend fun enableFileTransfers(): ChunkManager = owned {
            chunks ?: run {
                val transport = transport()
                ChunkManager(transport.cluster.localId, File(cacheDir, "mesh-transfers"), checkNotNull(acks),
                    sendPacket = { packet ->
                        val header = MeshPacket.FrameHeader(packet.type, packet.packetId, packet.senderId, packet.targetId,
                            packet.clusterId, packet.hopLimit, packet.currentHops, packet.timestamp, 0, 0)
                        val next = gateway?.routes(header)?.firstOrNull()
                            ?: chooseNextHops(transport, header, UUID(0, 0)).firstOrNull()
                            ?: throw IOException("No route to file recipient")
                        if (gateway?.sendFromHost(next, packet) != true) transport.sendAwait(next, packet)
                    }, now = android.os.SystemClock::elapsedRealtime).also { chunks = it }
            }
        }

        suspend fun startOwner(): WifiDirectManager.State = owned { transport().startOwner() }
        suspend fun startLocalHotspot(): WifiDirectManager.State = owned { transport().startLocalHotspot() }

        /** Start after configure() and startLocalHotspot(); serve the bundled HTML from app assets. */
        suspend fun enableUniversalGateway(
            origin: String, joinSecret: String, tls: javax.net.ssl.SSLServerSocketFactory, html: String
        ): UniversalMeshGateway = owned {
            check(gateway == null)
            val transport = transport()
            check(transport.cluster.isOwner)
            val value = UniversalMeshGateway(transport.cluster.localId, transport.cluster.clusterId,
                checkNotNull(filter), File(cacheDir, "gateway"), html, origin, joinSecret, tls,
                nativePeers = { transport.routingTable.routes.value.map {
                    UniversalMeshGateway.Peer(it.destination.nodeId, it.destination.clusterId, it.hops)
                } },
                onJoin = { id, session -> transport.cluster.admitClient(id, session, bridgeCapable = false); Unit },
                onLeave = { transport.cluster.release(it) })
            try { withContext(Dispatchers.IO) { value.startGateway() }; gateway = value; value }
            catch (e: Exception) { value.shutdown(); throw e }
        }
        suspend fun discoverPeers() = owned { transport().discoverPeers() }
        val peers get() = transport().peers
        suspend fun connectDirect(address: String, fallback: WifiDirectManager.HotspotInvitation? = null) =
            owned { transport().connectDirect(address, fallback) }
        suspend fun joinHotspot(invitation: WifiDirectManager.HotspotInvitation) =
            owned { transport().joinHotspot(invitation) }
        suspend fun connectAdjacent(invitation: WifiDirectManager.HotspotInvitation) =
            owned { transport().connectAdjacent(invitation) }
        suspend fun connectOnNetwork(network: Network, ownerAddress: InetAddress) =
            owned { transport().connectOnNetwork(network, ownerAddress) }

        /** Locally originated traffic uses the regular send API; forwarding uses raw buffers. */
        fun send(sessionId: UUID, packet: MeshPacket): Boolean = transport().send(sessionId, packet)
        suspend fun sendAwait(sessionId: UUID, packet: MeshPacket) = owned {
            if (gateway?.sendFromHost(sessionId, packet) != true) transport().sendAwait(sessionId, packet)
        }
        fun stop() { stopSelf() }
    }

    private fun transport(): WifiDirectManager {
        check(!destroyed)
        return checkNotNull(manager) { "Call configure() first" }
    }

    /** Operations are cancelled on both caller cancellation and service destruction. */
    private suspend fun <T> owned(block: suspend () -> T): T {
        val task = serviceScope.async { check(!destroyed); block() }
        try { return task.await() } finally { if (!task.isCompleted) task.cancel() }
    }

    private fun chooseNextHops(
        transport: WifiDirectManager, header: MeshPacket.FrameHeader, inbound: UUID
    ): List<UUID> {
        val adjacent = transport.cluster.state.value.adjacent?.sessionId
        val connected = transport.connections.value.filter {
            it.sessionId != inbound && (!it.adjacent || it.sessionId == adjacent)
        }
        if (header.targetId == null || header.targetId == MeshPacket.BROADCAST)
            return connected.map { it.sessionId }
        // Prefer a current route in this frame's cluster namespace.
        val route = transport.routingTable.lookup(header.targetId, header.clusterId)
        if (route != null && connected.any { it.sessionId == route.sessionId }) return listOf(route.sessionId)
        // A bridge may directly reach a peer in the adjacent cluster. Never guess if IDs collide.
        val direct = connected.filter { it.peerId == header.targetId }.filter {
            transport.routingTable.lookup(it.peerId, it.clusterId)?.sessionId == it.sessionId
        }
        return if (direct.size == 1) listOf(direct.single().sessionId) else emptyList()
    }

    private fun promoteToForeground() {
        val notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Offline mesh relay", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Status of the user-started in-memory relay"
            setSound(null, null)
            enableVibration(false)
        })
        val stopIntent = PendingIntent.getService(this, 0,
            Intent(this, SilentRelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Offline mesh relay running")
            .setContentText("Transit stays in memory • Local files only when enabled")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun acquireLocks() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:SilentRelayCpu").apply {
            setReferenceCounted(false)
            acquire(WAKE_TIMEOUT_MS)
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Android 14+ maps HIGH_PERF to LOW_LATENCY, with the platform's screen/foreground restrictions.
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:SilentRelayWifi").apply {
            setReferenceCounted(false)
            acquire()
        }
        renewLock = serviceScope.launch {
            while (isActive) {
                delay(WAKE_RENEW_MS)
                if (running) wakeLock?.acquire(WAKE_TIMEOUT_MS)
            }
        }
    }

    private fun releaseLocks() {
        renewLock?.cancel(); renewLock = null
        wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        serviceScope.cancel()
        val transport = manager
        val relay = filter
        val files = chunks
        val web = gateway
        gateway = null
        web?.stop()
        chunks = null
        manager = null; filter = null
        relay?.close() // Free slots are wiped now; in-flight slots remain owned until I/O exits.
        acks?.close(); acks = null
        transport?.abortRelaySockets() // Synchronous socket close unblocks pending reads/writes.
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // This scope exists only for bounded platform teardown and completion of in-flight wipes.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try { try { web?.shutdown(); files?.close() } finally { transport?.close() } }
            finally {
                relay?.awaitWiped()
                consumer = null
                cancel()
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "offline_mesh_relay"
        private const val NOTIFICATION_ID = 8888
        private const val ACTION_START = "com.example.mesh.START_RELAY"
        private const val ACTION_STOP = "com.example.mesh.STOP_RELAY"
        private const val WAKE_TIMEOUT_MS = 10 * 60 * 1_000L
        private const val WAKE_RENEW_MS = 5 * 60 * 1_000L

        /** Call from a visible Activity/user action; then bind and configure through LocalBinder. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, SilentRelayService::class.java).setAction(ACTION_START))
        }
        fun stop(context: Context) { context.stopService(Intent(context, SilentRelayService::class.java)) }
    }
}
