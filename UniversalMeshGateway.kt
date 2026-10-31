// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocketFactory

/**
 * Portable JVM/Android HTTPS gateway. Raw TCP stays owned by WifiDirectManager or
 * DesktopMeshHost. Browser file endpoints explicitly spool to cache; transit relay
 * traffic still never touches disk. TLS must be trusted by every client device.
 * Use the bounded vendor/NanoWSD.java, NOT the unmodified websocket Maven artifact.
 */
class UniversalMeshGateway(
    val hostId: String,
    val clusterId: UUID,
    private val relay: RelayPacketFilter,
    private val cache: File,
    html: String,
    val origin: String,
    private val joinSecret: String,
    private val tls: SSLServerSocketFactory?,
    bindAddress: String = "0.0.0.0",
    port: Int = 8080,
    private val nativePeers: () -> List<Peer> = { emptyList() },
    private val onJoin: (String, UUID) -> Unit = { _, _ -> },
    private val onLeave: (UUID) -> Unit = {},
    private val maxClients: Int = 4,
    private val maxFileBytes: Long = 2L * 1024 * 1024 * 1024,
    private val advertiseHost: Boolean = true
) : NanoWSD(bindAddress, port), RelayPacketFilter.TransportBridge, Closeable {
    data class Peer(val id: String, val cluster: UUID, val hops: Int, val browser: Boolean = false)
    private val root = SupervisorJob()
    private val html = html.replace("\r\n", "\n").replace('\r', '\n')
    private val scope = CoroutineScope(root + Dispatchers.IO)
    private val clients = ConcurrentHashMap<String, Endpoint>()
    private val membership = Any()
    private val stopped = AtomicBoolean()
    private val timers = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val originUri = URI(origin)
    private val random = SecureRandom()
    private val runner = BoundedRunner()
    private val maxCacheReservation = 4L * 1024 * 1024 * 1024
    private var reservedUploads = 0L

    init {
        require(MeshPacket.isValidIdentity(hostId) && maxClients in 1..4 && maxFileBytes > 0)
        require(joinSecret.toByteArray().size >= 32) { "Use a random 256-bit admission secret" }
        require(originUri.rawPath.isNullOrEmpty() && originUri.rawQuery == null && originUri.rawFragment == null)
        require(originUri.host != null && originUri.userInfo == null)
        require(originUri.port == port)
        require((tls != null && originUri.scheme == "https") ||
            (tls == null && bindAddress == "127.0.0.1" && originUri.host == "127.0.0.1" && originUri.scheme == "http")) {
            "Remote browser access requires trusted HTTPS; HTTP is loopback-test-only"
        }
        require(cache.isDirectory || cache.mkdirs())
        if (tls != null) makeSecure(tls, arrayOf("TLSv1.2"))
        setAsyncRunner(runner)
    }

    fun startGateway() {
        check(!stopped.get())
        relay.attachBridge(this)
        try { start(30_000, true) } catch (e: Exception) { relay.detachBridge(this); throw e }
        scope.launch {
            while (isActive) {
                delay(3_000)
                for (endpoint in clients.values.toList()) {
                    if (now() - endpoint.touched > 120_000) remove(endpoint)
                    else runCatching { endpoint.emit(peersJson()) }
                }
            }
        }
    }

    fun browserPeers(): List<Peer> = clients.values.map { Peer(it.id, clusterId, 1, true) }
    private fun endpoint(session: UUID) = clients.values.firstOrNull { it.sessionId == session }
    override fun owns(sessionId: UUID) = endpoint(sessionId) != null
    override fun routes(header: MeshPacket.FrameHeader): List<UUID> = clients.values.filter {
        header.targetId == null || header.targetId == MeshPacket.BROADCAST || header.targetId == it.id
    }.map { it.sessionId }

    override suspend fun write(sessionId: UUID, frame: ByteBuffer): Boolean {
        val client = endpoint(sessionId) ?: return false
        val h = MeshPacket.inspectFrame(frame)
        val payload = frame.asReadOnlyBuffer().apply { position(h.payloadOffset); limit(h.payloadOffset + h.payloadLength) }.slice()
        client.acks.observe(UUID(0, 1), h, payload.asReadOnlyBuffer())
        if (h.type == PacketType.DELIVERY_ACK) client.acks.accept(h, payload)
        else if (!client.chunks.consume(h, payload.asReadOnlyBuffer())) {
            when (h.type) {
                PacketType.TEXT -> client.emit(JSONObject().put("type", "chat").put("id", h.packetId.toString())
                    .put("sender", h.senderId).put("text", Charsets.UTF_8.decode(payload).toString()).put("time", h.timestamp))
                PacketType.PTT_VOICE -> {
                    val bytes = ByteArray(payload.remaining()); payload.get(bytes)
                    try { client.emitBinary(bytes) } finally { bytes.fill(0) }
                }
                else -> Unit
            }
        }
        return true
    }

    /** Local-host packets need one hop; relayed packets passed to write() already have it. */
    suspend fun sendFromHost(sessionId: UUID, packet: MeshPacket): Boolean {
        if (!owns(sessionId)) return false
        val bytes = packet.forNextHop().toByteArray()
        return try { write(sessionId, ByteBuffer.wrap(bytes)) } finally { bytes.fill(0) }
    }

    private suspend fun inject(client: Endpoint, packet: MeshPacket) {
        val bytes = packet.forNextHop().toByteArray()
        try { relay.receive(client.sessionId, ByteArrayInputStream(bytes)) { error("Browser heartbeat prohibited") } }
        finally { bytes.fill(0) }
    }

    private inner class Endpoint(val id: String, val token: String) {
        val sessionId = UUID.randomUUID()
        val job = SupervisorJob(root)
        val jobs = CoroutineScope(job + Dispatchers.IO)
        @Volatile var socket: BrowserSocket? = null
        @Volatile var touched = now()
        val uploads = ConcurrentHashMap<UUID, Upload>()
        val downloads = ConcurrentHashMap.newKeySet<Download>()
        // Never re-enter the relay pool while its borrowed receive slot is held.
        private val ackOutbox = Channel<MeshPacket>(8)
        val acks = AckManager(id, { _, packet -> check(ackOutbox.trySend(packet).isSuccess) { "ACK queue full" } })
        val chunks = ChunkManager(id, File(cache, sessionId.toString()), acks,
            { packet -> inject(this, packet) }, maxFileBytes, maxFileBytes)
        init {
            jobs.launch {
                try {
                    for (packet in ackOutbox) {
                        try { inject(this@Endpoint, packet) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { /* A missing ACK remains a timeout, never a delivered tick. */ }
                    }
                } finally { ackOutbox.cancel() }
            }
            jobs.launch { chunks.completed.collect { runCatching { emit(filesJson(this@Endpoint)) } } }
        }
        fun emit(json: JSONObject) { socket?.safeSend(json.toString()) }
        fun emitBinary(bytes: ByteArray) { socket?.safeSend(bytes) }
    }
    private class Upload(val file: File, val size: Long, val target: String, val cluster: UUID) {
        var written = 0L
        var committed = false
    }
    private inner class Download(val fileId: UUID, file: File, offset: Long,
        private val client: Endpoint, private val request: IHTTPSession) : FilterInputStream(FileInputStream(file).apply { channel.position(offset) }) {
        private val closed = AtomicBoolean()
        @Volatile private var activity = now()
        private val watchdog = timers.scheduleAtFixedRate({
            if (now() - activity > 30_000) abort()
        }, 30, 10, TimeUnit.SECONDS)
        private fun touch() { activity = now(); client.touched = activity }
        override fun read(): Int { touch(); return `in`.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { touch(); return `in`.read(b, off, len) }
        fun abort() { runCatching { request.inputStream.close() }; close() }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            watchdog.cancel(false)
            try { super.close() } finally { client.downloads.remove(this) }
        }
    }

    private inner class BrowserSocket(session: IHTTPSession, val client: Endpoint) : WebSocket(session) {
        private var target: String? = null
        private var targetCluster = clusterId
        private var window = now()
        private var messages = 0
        fun abort() { runCatching { handshakeRequest.inputStream.close() } }
        fun safeSend(value: String) = timed { send(value) }
        fun safeSend(value: ByteArray) = timed { send(value) }
        private fun timed(action: () -> Unit) {
            val timeout = timers.schedule({ abort() }, 5, TimeUnit.SECONDS)
            try { action() } catch (e: IOException) { abort(); throw e } finally { timeout.cancel(false) }
        }
        override fun onOpen() {
            synchronized(client) { client.socket?.abort(); client.socket = this }
            client.emit(JSONObject().put("type", "ready").put("identity", client.id)
                .put("cluster", clusterId.toString()).put("hostId", hostId))
            client.emit(peersJson()); client.emit(filesJson(client))
        }
        override fun onMessage(frame: WebSocketFrame) {
            try {
                client.touched = now()
                if (now() - window >= 1_000) { window = now(); messages = 0 }
                require(++messages <= 40) { "Rate limit" }
                if (frame.opCode == WebSocketFrame.OpCode.Binary) {
                    val payload = frame.binaryPayload
                    BrowserVoiceWire.validate(payload)
                    runBlocking { inject(client, MeshPacket(PacketType.PTT_VOICE, client.id, targetCluster, payload, target)) }
                } else {
                    require(frame.binaryPayload.size <= 16_384)
                    val message = JSONObject(frame.textPayload)
                    when (message.getString("type")) {
                        "ping" -> client.emit(JSONObject().put("type", "pong"))
                        "voiceTarget" -> {
                            target = message.optString("target", MeshPacket.BROADCAST)
                            require(MeshPacket.isValidTarget(target))
                            targetCluster = UUID.fromString(message.optString("cluster", clusterId.toString()))
                        }
                        "chat" -> {
                            val destination = message.getString("target")
                            require(MeshPacket.isValidTarget(destination))
                            val bytes = message.getString("text").toByteArray(Charsets.UTF_8)
                            require(bytes.size in 1..8_192)
                            val group = UUID.fromString(message.optString("cluster", clusterId.toString()))
                            runBlocking { inject(client, MeshPacket(PacketType.TEXT, client.id, group, bytes, destination)) }
                        }
                        else -> error("Unknown command")
                    }
                }
            } catch (_: Exception) { abort() }
        }
        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            synchronized(client) { if (client.socket === this) client.socket = null }
        }
        override fun onPong(pong: WebSocketFrame) { client.touched = now() }
        override fun onException(exception: IOException) { abort() }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = BrowserSocket(handshake, checkNotNull(auth(handshake)))

    override fun serve(session: IHTTPSession): Response {
        return try {
            require(session.headers["host"].equals(originUri.rawAuthority, ignoreCase = true)) { "Invalid Host" }
            if (session.headers["upgrade"]?.equals("websocket", true) == true) {
                require(session.uri == "/ws" && session.headers["origin"] == origin && auth(session) != null)
                super.serve(session)
            } else secure(serveHttp(session))
        } catch (_: Exception) {
            secure(newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Request rejected")).apply { closeConnection(true) }
        }
    }

    override fun serveHttp(session: IHTTPSession): Response {
        val path = session.uri
        if (session.method == Method.GET) {
            when (path) {
                "/" -> return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
                "/sw.js" -> return newFixedLengthResponse(Response.Status.OK, "application/javascript", GatewayWebAssets.worker)
                "/manifest.webmanifest" -> return newFixedLengthResponse(Response.Status.OK, "application/manifest+json", GatewayWebAssets.manifest)
                "/icon.png" -> return newFixedLengthResponse(Response.Status.OK, "image/png",
                    ByteArrayInputStream(GatewayWebAssets.icon), GatewayWebAssets.icon.size.toLong())
                "/icon192.png" -> return newFixedLengthResponse(Response.Status.OK, "image/png",
                    ByteArrayInputStream(GatewayWebAssets.icon192), GatewayWebAssets.icon192.size.toLong())
            }
        }
        if (session.method != Method.GET) require(session.headers["origin"] == origin) { "Origin required" }
        if (path == "/login" && session.method == Method.POST) {
            val data = JSONObject(readBody(session, 4_096).toString(Charsets.UTF_8))
            val identity = data.getString("identity")
            require(MeshPacket.isValidIdentity(identity) && identity != hostId)
            val existing = auth(session)
            require((existing != null && existing.id == identity) ||
                MessageDigest.isEqual(data.getString("secret").toByteArray(), joinSecret.toByteArray()))
            val endpoint = synchronized(membership) {
                if (existing != null && existing.id == identity) existing
                else {
                    require(clients.size < maxClients && clients.values.none { it.id == identity }) { "Identity/cluster busy" }
                    require(nativePeers().none { it.id == identity })
                    val token = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
                    val created = Endpoint(identity, token)
                    try { onJoin(identity, created.sessionId) }
                    catch (e: Exception) { created.job.cancel(); created.acks.close(); scope.launch { created.chunks.close(true) }; throw e }
                    clients[token] = created
                    created
                }
            }
            endpoint.touched = now()
            return json(JSONObject().put("identity", identity)).apply {
                addHeader("Set-Cookie", "meshSession=${endpoint.token}; HttpOnly; SameSite=Strict; Path=/" + if (tls != null) "; Secure" else "")
            }
        }
        val client = auth(session) ?: return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Join the gateway first")
        client.touched = now()
        if (path == "/logout" && session.method == Method.POST) {
            runBlocking { remove(client) }
            return json(JSONObject().put("ok", true)).apply { addHeader("Set-Cookie", "meshSession=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict") }
        }
        if (path == "/files" && session.method == Method.GET) return json(filesJson(client))
        if (path == "/upload" && session.method == Method.POST) {
            val data = JSONObject(readBody(session, 4_096).toString(Charsets.UTF_8))
            val size = data.getLong("size")
            val name = data.getString("name").substringAfterLast('/').substringAfterLast('\\')
            require(name.isNotBlank() && name != "." && name != ".." && name.toByteArray().size <= 180 && name.none { it.isISOControl() })
            val target = data.getString("target")
            require(MeshPacket.isValidIdentity(target) && target != client.id)
            val group = UUID.fromString(data.getString("cluster"))
            val id = UUID.randomUUID()
            synchronized(membership) {
                require(size in 0..maxFileBytes && client.uploads.size < 2 && size <= maxCacheReservation - reservedUploads)
                require(size < cache.usableSpace - reservedUploads)
                val directory = File(cache, "upload-$id"); check(directory.mkdir())
                client.uploads[id] = Upload(File(directory, name), size, target, group)
                reservedUploads += size
            }
            return json(JSONObject().put("id", id.toString()))
        }
        val parts = path.trim('/').split('/')
        if (session.method == Method.DELETE && parts.size == 2 && parts[0] == "file") {
            val id = UUID.fromString(parts[1])
            require(client.downloads.none { it.fileId == id }) { "Download still active" }
            runBlocking { client.chunks.release(id) }
            return json(JSONObject().put("ok", true))
        }
        if (parts.size >= 2 && parts[0] == "upload") {
            val id = UUID.fromString(parts[1])
            val upload = checkNotNull(client.uploads[id])
            synchronized(upload) {
                require(!upload.committed)
                if (parts.size == 3 && session.method == Method.PUT) {
                    val offset = parts[2].toLong() * CHUNK
                    require(offset == upload.written && offset < upload.size)
                    val expected = minOf(CHUNK.toLong(), upload.size - offset).toInt()
                    val bytes = readBody(session, CHUNK)
                    require(bytes.size == expected)
                    try { RandomAccessFile(upload.file, "rw").use { it.seek(offset); it.write(bytes) }; upload.written += bytes.size }
                    finally { bytes.fill(0) }
                    return json(JSONObject().put("written", upload.written))
                }
                if (parts.size == 2 && session.method == Method.DELETE) {
                    discardUpload(client, id, upload)
                    return json(JSONObject().put("ok", true))
                }
                if (parts.size == 3 && parts[2] == "commit" && session.method == Method.POST) {
                    require(upload.written == upload.size)
                    if (upload.size == 0L) check(upload.file.createNewFile())
                    upload.committed = true
                    client.jobs.launch {
                        try {
                            val wireId = client.chunks.sendFile(upload.file, upload.target, upload.cluster, onProgress = { sent, total ->
                                client.emit(JSONObject().put("type", "transfer").put("id", id.toString())
                                    .put("sent", sent).put("total", total).put("state", if (sent == total) "verifying" else "sending"))
                            })
                            client.emit(JSONObject().put("type", "transfer").put("id", id.toString()).put("state", "delivered").put("wireId", wireId.toString()))
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { runCatching { client.emit(JSONObject().put("type", "transfer").put("id", id.toString()).put("state", "failed")) } }
                        finally { synchronized(upload) { discardUpload(client, id, upload) } }
                    }
                    return json(JSONObject().put("accepted", true))
                }
            }
        }
        if (session.method == Method.GET && parts.size in 2..3 && parts[0] in listOf("download", "save", "chunk", "file")) {
            val item = checkNotNull(client.chunks.completed.value[UUID.fromString(parts[1])])
            if (parts[0] == "file") return json(JSONObject().put("name", item.metadata.fileName).put("size", item.metadata.size))
            val offset = if (parts[0] == "chunk") Math.multiplyExact(parts[2].toLong(), CHUNK.toLong()) else 0L
            require(offset >= 0 && (offset < item.metadata.size || item.metadata.size == 0L && offset == 0L))
            val length = if (parts[0] == "chunk") minOf(CHUNK.toLong(), item.metadata.size - offset) else item.metadata.size
            val stream = Download(item.metadata.id, item.file, offset, client, session)
            client.downloads.add(stream)
            return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", stream, length).apply {
                addHeader("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(item.metadata.fileName, "UTF-8").replace("+", "%20"))
            }
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
    }

    private fun auth(s: IHTTPSession): Endpoint? {
        val token = s.headers["cookie"].orEmpty().split(';').map { it.trim() }.firstOrNull { it.startsWith("meshSession=") }?.substringAfter('=')
        return token?.let(clients::get)
    }
    private fun readBody(s: IHTTPSession, maximum: Int): ByteArray {
        require(s.headers["transfer-encoding"] == null)
        val length = s.headers["content-length"]?.toIntOrNull() ?: 0
        require(length in 0..maximum)
        return ByteArray(length).also { DataInputStream(s.inputStream).readFully(it) }
    }
    private fun json(data: JSONObject) = newFixedLengthResponse(Response.Status.OK, "application/json", data.toString())
    private fun secure(response: Response): Response = response.apply {
        addHeader("Cache-Control", "no-store")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("Permissions-Policy", "microphone=(self), camera=()")
        val scripts = Regex("<script>([\\s\\S]*?)</script>").findAll(html).map {
            "'sha256-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(it.groupValues[1].toByteArray())) + "'"
        }.joinToString(" ")
        val websocketOrigin = origin.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        addHeader("Content-Security-Policy", "default-src 'self'; script-src 'self' $scripts; style-src 'self' 'unsafe-inline'; connect-src 'self' $websocketOrigin; img-src 'self' data:; media-src 'self' blob:; worker-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
        closeConnection(true) // Reject request-body smuggling; each HTTP body has a fixed bounded length.
    }
    private fun peersJson(): JSONObject {
        val peers = ((if (advertiseHost) listOf(Peer(hostId, clusterId, 0)) else emptyList()) + nativePeers() + browserPeers()).distinctBy { it.id to it.cluster }
        return JSONObject().put("type", "peers").put("peers", JSONArray(peers.map {
            JSONObject().put("id", it.id).put("cluster", it.cluster.toString()).put("hops", it.hops).put("browser", it.browser)
        }))
    }
    private fun filesJson(client: Endpoint) = JSONObject().put("type", "files").put("files", JSONArray(client.chunks.completed.value.map { (id, value) ->
        JSONObject().put("id", id.toString()).put("name", value.metadata.fileName).put("size", value.metadata.size)
    }))
    private fun discardUpload(client: Endpoint, id: UUID, upload: Upload) {
        synchronized(membership) { if (client.uploads.remove(id, upload)) reservedUploads -= upload.size }
        upload.file.delete(); upload.file.parentFile?.delete()
    }
    private suspend fun remove(client: Endpoint) {
        if (!clients.remove(client.token, client)) return
        client.downloads.toList().forEach { runCatching { it.abort() } }
        client.socket?.abort(); client.job.cancelAndJoin()
        client.chunks.close(true); client.acks.close()
        client.uploads.entries.toList().forEach { (id, upload) -> synchronized(upload) { discardUpload(client, id, upload) } }
        File(cache, client.sessionId.toString()).delete()
        onLeave(client.sessionId)
    }
    suspend fun shutdown() {
        if (!stopped.compareAndSet(false, true)) return
        relay.detachBridge(this); stop(); runner.closeAll()
        withContext(NonCancellable + Dispatchers.IO) { clients.values.toList().forEach { remove(it) } }
        root.cancelAndJoin(); timers.shutdownNow()
    }
    override fun close() { runBlocking { shutdown() } }
    private fun now() = System.nanoTime() / 1_000_000

    private class BoundedRunner : NanoHTTPD.AsyncRunner {
        private val active = ConcurrentHashMap.newKeySet<NanoHTTPD.ClientHandler>()
        private val executor = ThreadPoolExecutor(16, 16, 0L, TimeUnit.SECONDS, ArrayBlockingQueue(16))
        override fun exec(code: NanoHTTPD.ClientHandler) {
            active.add(code)
            try { executor.execute(code) } catch (_: RejectedExecutionException) { active.remove(code); code.close() }
        }
        override fun closed(clientHandler: NanoHTTPD.ClientHandler) { active.remove(clientHandler) }
        override fun closeAll() { active.toList().forEach { it.close() }; active.clear(); executor.shutdownNow() }
    }
    companion object { const val CHUNK = 512 * 1024 }
}
