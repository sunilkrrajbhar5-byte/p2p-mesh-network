package com.example.mesh

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Activity-owned direct-peer session coordinator. Radio association precedes app consent;
 * application payload is admitted only after consent. Existing relay/service wiring is separate. */
class MeshEngine(context: Context, val localId: String) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val consent = PeerConsentController()
    private val operation = Mutex()
    private var transport: WifiDirectManager? = null
    private var collectors = listOf<Job>()
    private var scanJob: Job? = null
    private var voiceErrors: Job? = null
    private val messageChannel = Channel<String>(32)
    val messages = messageChannel.receiveAsFlow()
    private val nearbyState = MutableStateFlow<List<WifiDirectManager.NearbyDevice>>(emptyList())
    val nearby = nearbyState.asStateFlow()
    private val connectionsState = MutableStateFlow<List<WifiDirectManager.Connection>>(emptyList())
    val connections = connectionsState.asStateFlow()
    private val statusState = MutableStateFlow("Starting nearby discovery…")
    val status = statusState.asStateFlow()
    private val textState = MutableStateFlow<List<String>>(emptyList())
    val chat = textState.asStateFlow()
    private val progressState = MutableStateFlow<FileTransferUi?>(null)
    val transfer = progressState.asStateFlow()
    private data class VoiceWait(val session: UUID, val peer: String, val result: CompletableDeferred<Boolean>)
    private data class VoiceGrant(val session: UUID, val peer: String, val stream: UUID, val expires: Long)
    private val voiceWaits = ConcurrentHashMap<UUID, VoiceWait>()
    private val voiceGrants = ConcurrentHashMap<UUID, VoiceGrant>()
    private val requests = ConcurrentHashMap<UUID, Job>()
    private var ack: AckManager? = null
    private var chunks: ChunkManager? = null
    var voice: PttVoiceEngine? = null
        private set

    fun notify(message: String) { messageChannel.trySend(message) }
    private fun launchOperation(block: suspend () -> Unit) = scope.launch {
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { statusState.value = e.message ?: "Connection failed"; if (e !is RequestDeclinedException) notify(statusState.value) }
    }
    fun start() { launchOperation { operation.withLock { if (transport == null) host() } } }
    fun refresh() { launchOperation { transport?.discoverIdentities(); statusState.value = "Searching nearby devices" } }
    private suspend fun host() {
        val manager = createTransport(ClusterController(localId, UUID.randomUUID(), localId))
        val state = manager.startOwner()
        statusState.value = if (state is WifiDirectManager.State.DirectOwner) "Visible to nearby devices" else
            "Wi-Fi Direct unavailable. Hotspot requires a system Wi-Fi invitation."
        if (state is WifiDirectManager.State.DirectOwner) startDiscovery(manager)
    }
    private fun startDiscovery(manager: WifiDirectManager) {
        scanJob?.cancel()
        scanJob = scope.launch {
            while (isActive && transport === manager) {
                try { manager.discoverIdentities() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { statusState.value = e.message ?: "Discovery unavailable" }
                delay(12_000)
            }
        }
    }
    fun connect(peer: WifiDirectManager.NearbyDevice) { launchOperation {
        operation.withLock {
            if (connections.value.isNotEmpty()) throw IOException("Disconnect current peers before joining another host")
            stopTransport()
            val manager = createTransport(ClusterController(localId, peer.clusterId, peer.identity))
            statusState.value = "Waiting for ${peer.identity} to accept…"
            try {
                val state = manager.connectDirect(peer.address)
                if (state !is WifiDirectManager.State.DirectClient) throw IOException("Direct connection unavailable. Retry nearby discovery.")
                statusState.value = "Connected to ${peer.identity}"
            } catch (e: Exception) {
                if (e is RequestDeclinedException) notify("Request Declined")
                stopTransport()
                if (e !is CancellationException) host()
                throw e
            }
        }
    } }
    fun disconnect() { launchOperation { operation.withLock { stopTransport(); host() } } }

    private fun createTransport(cluster: ClusterController): WifiDirectManager {
        val manager = WifiDirectManager(app, cluster, consent = consent)
        transport = manager
        val acknowledgements = AckManager(localId, { session, packet -> manager.sendAwait(session, packet) })
        val files = ChunkManager(localId, File(app.cacheDir, "received-mesh"), acknowledgements,
            sendPacket = { packet ->
                val connection = manager.connections.value.singleOrNull { it.peerId == packet.targetId }
                    ?: throw IOException("Peer disconnected")
                manager.sendAwait(connection.sessionId, packet)
            })
        ack = acknowledgements; chunks = files
        collectors = listOf(
            scope.launch { manager.nearby.collect { nearbyState.value = it } },
            scope.launch { manager.connections.collect { list ->
                val removed = connectionsState.value.filter { old -> list.none { it.sessionId == old.sessionId } }
                connectionsState.value = list
                removed.forEach { lost ->
                    consent.cancelPeer(lost.peerId)
                    voiceWaits.values.filter { it.session == lost.sessionId }.forEach { it.result.complete(false) }
                    voiceGrants.entries.removeAll { it.value.session == lost.sessionId }
                }
            } },
            scope.launch(Dispatchers.IO) { manager.packets.collect { received ->
                try { receive(manager, files, acknowledgements, received) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { manager.disconnect(received.sessionId); notify("Invalid peer request; disconnected") }
            } }
        )
        return manager
    }
    private suspend fun receive(manager: WifiDirectManager, files: ChunkManager, acknowledgements: AckManager,
        received: WifiDirectManager.ReceivedPacket) {
        val p = received.packet
        // This screen owns direct endpoints. Do not accept another identity asserted over this channel.
        require(p.senderId == received.peerId && p.clusterId == manager.cluster.clusterId)
        require(p.targetId == localId)
        val frame = ByteBuffer.wrap(p.toByteArray())
        val h = MeshPacket.inspectFrame(frame)
        val payload = ByteBuffer.wrap(p.payload)
        acknowledgements.observe(received.sessionId, h, payload.duplicate())
        when (p.type) {
            PacketType.CONSENT_RESPONSE -> {
                val (id, accepted) = ConsentWire.decode(p.payload)
                val waiting = voiceWaits[id]?.takeIf { it.session == received.sessionId && it.peer == p.senderId }
                if (waiting != null) waiting.result.complete(accepted)
                else if (!accepted) {
                    if (consent.pending.value.any { it.id == id && it.peerId == p.senderId && it.kind == PeerConsentController.Kind.VOICE }) consent.cancel(id)
                    voiceGrants[id]?.takeIf { it.session == received.sessionId }?.let { voiceGrants.remove(id) }
                }
            }
            PacketType.CONSENT_REQUEST -> {
                val (id, flag) = ConsentWire.decode(p.payload)
                require(!flag)
                handleRequest(id, manager, received.sessionId) {
                    voiceGrants.entries.removeAll { it.value.expires < SystemClock.elapsedRealtime() }
                    val accepted = voiceGrants.size < 4 && consent.ask(PeerConsentController.Request(id, p.senderId, PeerConsentController.Kind.VOICE))
                    if (accepted) voiceGrants[id] = VoiceGrant(received.sessionId, p.senderId, id, SystemClock.elapsedRealtime() + 90_000)
                    manager.sendAwait(received.sessionId, MeshPacket(PacketType.CONSENT_RESPONSE, localId,
                        p.clusterId, ConsentWire.encode(id, accepted), p.senderId))
                    if (!accepted) manager.disconnect(received.sessionId)
                }
            }
            PacketType.PRE_ALERT -> {
                val metadata = ChunkManager.decodeMetadata(payload.duplicate())
                handleRequest(metadata.id, manager, received.sessionId) {
                    val accepted = consent.ask(PeerConsentController.Request(metadata.id, p.senderId,
                        PeerConsentController.Kind.FILE, metadata.fileName, metadata.size))
                    if (accepted) files.consume(h, payload.duplicate())
                    else {
                        acknowledgements.reply(metadata.id, AckManager.Code.REJECTED)
                        manager.disconnect(received.sessionId)
                    }
                }
            }
            PacketType.MEDIA_CHUNK -> files.consume(h, payload)
            PacketType.DELIVERY_ACK -> acknowledgements.accept(h, payload)
            PacketType.PTT_VOICE -> voice?.consume(h, payload)
            PacketType.TEXT -> textState.value = (textState.value + "${p.senderId}: ${p.payload.toString(Charsets.UTF_8)}").takeLast(100)
            else -> throw IOException("Unexpected control packet")
        }
    }
    private fun handleRequest(id: UUID, manager: WifiDirectManager, session: UUID, block: suspend () -> Unit) {
        synchronized(requests) {
            if (requests.containsKey(id)) return
            check(requests.size < 4) { "Too many requests" }
            val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try { block() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { manager.disconnect(session) }
                finally { requests.remove(id) }
            }
            requests[id] = job; job.start()
        }
    }
    suspend fun requestVoice(peer: String, stream: UUID): Boolean {
        val manager = transport ?: return false
        val connection = manager.connections.value.singleOrNull { it.peerId == peer } ?: return false
        check(voiceWaits.size < 4)
        val wait = VoiceWait(connection.sessionId, peer, CompletableDeferred())
        voiceWaits[stream] = wait
        return try {
            manager.sendAwait(connection.sessionId, MeshPacket(PacketType.CONSENT_REQUEST, localId,
                connection.clusterId, ConsentWire.encode(stream), peer))
            val accepted = withTimeoutOrNull(45_000) { wait.result.await() } == true
            if (!accepted) { notify("Request Declined"); manager.disconnect(connection.sessionId) }
            accepted
        } finally {
            voiceWaits.remove(stream)
            if (!wait.result.isCompleted) withContext(NonCancellable) {
                runCatching { manager.sendAwait(connection.sessionId, MeshPacket(PacketType.CONSENT_RESPONSE,
                    localId, connection.clusterId, ConsentWire.encode(stream, false), peer)) }
            }
        }
    }
    fun createVoice(owner: LifecycleOwner) {
        voiceErrors?.cancel(); voice?.close()
        val manager = transport ?: return
        voice = PttVoiceEngine(app, owner, localId, manager.cluster.clusterId,
            sendFast = { send(it) }, authorizeVoice = ::requestVoice,
            mayPlayVoice = { peer, stream ->
                voiceGrants.entries.removeAll { it.value.expires < SystemClock.elapsedRealtime() }
                voiceGrants[stream]?.let { it.peer == peer && connections.value.any { c -> c.sessionId == it.session } } == true
            })
        voiceErrors = scope.launch { voice?.errors?.collect { notify(it.message ?: "Voice failed") } }
    }
    private suspend fun send(packet: MeshPacket) {
        val manager = transport ?: throw IOException("Not connected")
        val connection = manager.connections.value.singleOrNull { it.peerId == packet.targetId } ?: throw IOException("Peer disconnected")
        manager.sendAwait(connection.sessionId, packet)
    }
    fun sendText(peer: String, text: String) { launchOperation {
        val manager = transport ?: return@launchOperation
        send(MeshPacket(PacketType.TEXT, localId, manager.cluster.clusterId, text.toByteArray(), peer))
        textState.value = (textState.value + "You: $text").takeLast(100)
    } }
    suspend fun sendFile(file: File, peer: String, displayName: String = file.name) {
        val manager = transport ?: throw IOException("Not connected")
        val files = chunks ?: throw IOException("Not connected")
        try { sendChatFile(file, peer, manager.cluster.clusterId, files, displayName = displayName) { progressState.value = it } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { notify(if (e.message == "Destination rejected transfer") "Request Declined" else e.message ?: "Transfer failed"); throw e }
    }
    private suspend fun stopTransport() {
        scanJob?.cancelAndJoin(); scanJob = null
        voiceErrors?.cancel(); voiceErrors = null; voice?.close(); voice = null
        transport?.close(); transport = null
        collectors.forEach { it.cancelAndJoin() }; collectors = emptyList()
        requests.values.toList().forEach { it.cancelAndJoin() }; requests.clear()
        chunks?.close(); chunks = null; ack?.close(); ack = null
        voiceWaits.values.forEach { it.result.complete(false) }; voiceWaits.clear(); voiceGrants.clear()
        connectionsState.value = emptyList(); nearbyState.value = emptyList()
    }
    fun shutdown() { scope.launch { close() } }
    suspend fun close() { consent.setUiVisible(false); operation.withLock { stopTransport() }; scope.cancel(); messageChannel.close() }
}
