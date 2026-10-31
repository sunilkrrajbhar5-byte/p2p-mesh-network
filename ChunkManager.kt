// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.BitSet
import java.util.UUID

/**
 * Endpoint-only adaptation of TrebleShot/uprotocol's streaming + offset-based transfer lifecycle.
 * 512 KiB disk reads become sixteen <=32 KiB fragments to fit the EXISTING u16 mesh frame.
 * Relays never instantiate an incoming file. Pass an app-private cache directory on Android.
 */
class ChunkManager(
    private val localId: String,
    private val cacheDirectory: File,
    private val acknowledgements: AckManager,
    private val sendPacket: suspend (MeshPacket) -> Unit,
    private val maxFileBytes: Long = 8L * 1024 * 1024 * 1024,
    private val maxReservedBytes: Long = 8L * 1024 * 1024 * 1024,
    private val acceptTransfer: (Metadata) -> Boolean = { true },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    class Metadata(val id: UUID, val size: Long, val fileName: String, sha256: ByteArray) {
        private val digest = sha256.copyOf()
        val sha256: ByteArray get() = digest.copyOf()
        init {
            require(size >= 0 && size <= Int.MAX_VALUE.toLong() * FRAGMENT_BYTES)
            require(digest.size == 32 && fileName.isNotBlank() && '\u0000' !in fileName)
            require(fileName.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES)
            require(Charsets.UTF_8.newEncoder().canEncode(fileName))
        }
    }
    data class Completed(val metadata: Metadata, val file: File)
    private class Incoming(val metadata: Metadata, val sender: String, val cluster: UUID,
        val file: File, val handle: RandomAccessFile, var touched: Long) {
        val fragments = BitSet()
        var received = 0
        var verifying = false
        var complete = false
        var verification: Job? = null
    }
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val receiveLock = Mutex()
    private val senderLock = Mutex()
    private val incoming = LinkedHashMap<UUID, Incoming>()
    @Volatile private var closed = false
    private val mutableCompleted = MutableStateFlow<Map<UUID, Completed>>(emptyMap())
    val completed: StateFlow<Map<UUID, Completed>> = mutableCompleted.asStateFlow()

    init {
        require(MeshPacket.isValidIdentity(localId) && maxFileBytes > 0 && maxReservedBytes >= maxFileBytes)
        scope.launch {
            while (isActive) {
                delay(15_000)
                receiveLock.withLock {
                    val expired = incoming.values.filter { !it.complete && !it.verifying && now() - it.touched >= RECEIVE_TIMEOUT_MS }
                    expired.forEach { discard(it) }
                }
            }
        }
    }

    /** Returns only after destination VERIFIED ACK (double tick), not merely local socket writes. */
    suspend fun sendFile(
        source: File, targetId: String, clusterId: UUID,
        readyTimeoutMs: Long = 60_000, deliveryTimeoutMs: Long = 120_000,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): UUID {
        val task = scope.async { sendFileInternal(source, targetId, clusterId, readyTimeoutMs, deliveryTimeoutMs, onProgress) }
        try { return task.await() } finally { if (!task.isCompleted) task.cancel() }
    }

    private suspend fun sendFileInternal(
        source: File, targetId: String, clusterId: UUID, readyTimeoutMs: Long, deliveryTimeoutMs: Long,
        onProgress: (Long, Long) -> Unit
    ): UUID = senderLock.withLock {
        withContext(Dispatchers.IO) {
            check(!closed)
            require(MeshPacket.isValidIdentity(targetId) && targetId != localId)
            FileInputStream(source).channel.use { channel ->
                val size = channel.size()
                require(size <= maxFileBytes) { "File exceeds transfer policy" }
                onProgress(0L, size) // IO thread; consumer must be fast and nonblocking.
                val block = ByteBuffer.allocate(CHUNK_BYTES)
                val metadata = Metadata(UUID.randomUUID(), size, source.name, hash(channel, size, block))
                val ticket = acknowledgements.register(metadata, targetId, clusterId)
                try {
                    sendPacket(MeshPacket(PacketType.PRE_ALERT, localId, clusterId,
                        encodeMetadata(metadata), targetId)) // Exactly 1,024 metadata payload bytes.
                    withTimeout(readyTimeoutMs) { acknowledgements.awaitReady(ticket) }
                    val secondDigest = MessageDigest.getInstance("SHA-256")
                    var position = 0L
                    while (position < size) {
                        currentCoroutineContext().ensureActive()
                        check(!closed)
                        val length = minOf(CHUNK_BYTES.toLong(), size - position).toInt()
                        block.clear(); block.limit(length)
                        readFully(channel, block, position)
                        block.flip()
                        secondDigest.update(block.asReadOnlyBuffer())
                        val chunkIndex = position / CHUNK_BYTES
                        var offset = 0
                        while (offset < length) {
                            val count = minOf(FRAGMENT_BYTES, length - offset)
                            val payload = ByteBuffer.allocate(FRAGMENT_HEADER_BYTES + count).order(ByteOrder.BIG_ENDIAN)
                                .putInt(FRAGMENT_MAGIC).putLong(metadata.id.mostSignificantBits)
                                .putLong(metadata.id.leastSignificantBits).putLong(chunkIndex)
                                .putInt(offset / FRAGMENT_BYTES).putInt(count)
                            val view = block.duplicate(); view.position(offset); view.limit(offset + count)
                            payload.put(view)
                            try {
                                sendPacket(MeshPacket(PacketType.MEDIA_CHUNK, localId, clusterId, payload.array(), targetId))
                            } finally { payload.array().fill(0) }
                            offset += count
                        }
                        position += length
                        onProgress(position, size) // All fragments in this block have finished writing.
                    }
                    check(channel.size() == size && MessageDigest.isEqual(secondDigest.digest(), metadata.sha256)) {
                        "Source changed during transfer; use a stable source file"
                    }
                    acknowledgements.markSent(metadata.id)
                    withTimeout(deliveryTimeoutMs) { acknowledgements.awaitVerified(ticket) }
                    metadata.id
                } catch (e: Throwable) {
                    acknowledgements.fail(metadata.id, e)
                    throw e
                } finally { block.array().fill(0); acknowledgements.forget(metadata.id) }
            }
        }
    }

    /** Called only by the destination's local consumer. The borrowed payload is never retained. */
    suspend fun consume(header: MeshPacket.FrameHeader, payload: ByteBuffer): Boolean {
        if (header.targetId != localId || header.type !in listOf(PacketType.PRE_ALERT, PacketType.MEDIA_CHUNK)) return false
        if (header.type == PacketType.PRE_ALERT) {
            val metadata = decodeMetadata(payload)
            var accepted = false
            var alreadyComplete = false
            try {
                withContext(Dispatchers.IO) {
                    receiveLock.withLock {
                        check(!closed)
                        val existing = incoming[metadata.id]
                        if (existing != null) {
                            require(matches(existing, header, metadata)) { "Conflicting pre-alert" }
                            existing.touched = now(); alreadyComplete = existing.complete
                        } else {
                            require(metadata.size <= maxFileBytes && acceptTransfer(metadata)) { "Transfer rejected by policy" }
                            require(incoming.size < MAX_INCOMING) { "Too many incoming transfers" }
                            val reserved = incoming.values.sumOf { it.metadata.size }
                            require(metadata.size <= maxReservedBytes - reserved) { "Cache budget exhausted" }
                            check(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) { "Cannot create app cache directory" }
                            require(metadata.size <= cacheDirectory.usableSpace - reserved) { "Insufficient cache space" }
                            // Never use the remote file name in a filesystem path.
                            val file = File.createTempFile("mesh-${metadata.id}-", ".part", cacheDirectory)
                            val raf = try { RandomAccessFile(file, "rw") } catch (e: Throwable) { file.delete(); throw e }
                            try {
                                raf.setLength(metadata.size)
                                incoming[metadata.id] = Incoming(metadata, header.senderId, header.clusterId, file, raf, now())
                            } catch (e: Throwable) { raf.close(); file.delete(); throw e }
                        }
                        accepted = true
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Fail this transfer without tearing down other traffic. */ }
            acknowledgements.reply(metadata.id, if (accepted) AckManager.Code.READY else AckManager.Code.REJECTED)
            if (alreadyComplete) acknowledgements.reply(metadata.id, AckManager.Code.VERIFIED)
            else if (accepted && metadata.size == 0L) receiveLock.withLock { incoming[metadata.id]?.let(::verifyLater) }
            return true
        }
        val fragment = decodeFragment(payload)
        var rejected = false
        withContext(Dispatchers.IO) {
            receiveLock.withLock {
                check(!closed)
                val transfer = incoming[fragment.id] ?: return@withLock // PRE_ALERT/READY is mandatory.
                require(transfer.sender == header.senderId && transfer.cluster == header.clusterId)
                if (transfer.complete || transfer.verifying) return@withLock
                try {
                    val offset = fragmentOffset(fragment.chunkIndex, fragment.fragmentIndex)
                    require(offset < transfer.metadata.size)
                    val expected = minOf(FRAGMENT_BYTES.toLong(), transfer.metadata.size - offset).toInt()
                    require(fragment.data.remaining() == expected) { "Invalid fragment extent" }
                    val bit = (offset / FRAGMENT_BYTES).toInt()
                    if (!transfer.fragments[bit]) {
                        transfer.handle.seek(offset)
                        val bytes = fragment.data.duplicate()
                        while (bytes.hasRemaining()) {
                            if (transfer.handle.channel.write(bytes) <= 0) throw IOException("Incomplete cache write")
                        }
                        transfer.fragments.set(bit); transfer.received++
                    }
                    transfer.touched = now()
                    if (transfer.received.toLong() == fragmentCount(transfer.metadata.size)) verifyLater(transfer)
                } catch (e: Exception) { discard(transfer); rejected = true }
            }
        }
        if (rejected) acknowledgements.reply(fragment.id, AckManager.Code.REJECTED)
        return true
    }

    private fun matches(t: Incoming, h: MeshPacket.FrameHeader, m: Metadata): Boolean =
        t.sender == h.senderId && t.cluster == h.clusterId && t.metadata.size == m.size &&
            t.metadata.fileName == m.fileName && MessageDigest.isEqual(t.metadata.sha256, m.sha256)

    /** Hashing is detached from the socket reader, so 1GB+ verification cannot starve heartbeats. */
    private fun verifyLater(transfer: Incoming) {
        if (transfer.verifying || transfer.complete) return
        transfer.verifying = true
        transfer.verification = scope.launch {
            var verified = false
            try {
                transfer.handle.channel.force(true)
                val block = ByteBuffer.allocate(CHUNK_BYTES)
                val digest = try { hash(transfer.handle.channel, transfer.metadata.size, block) }
                    finally { block.array().fill(0) }
                require(MessageDigest.isEqual(digest, transfer.metadata.sha256)) { "SHA-256 mismatch" }
                receiveLock.withLock {
                    check(!closed && incoming[transfer.metadata.id] === transfer)
                    transfer.handle.close()
                    transfer.complete = true
                    mutableCompleted.value = mutableCompleted.value +
                        (transfer.metadata.id to Completed(transfer.metadata, transfer.file))
                    verified = true
                }
                acknowledgements.reply(transfer.metadata.id, AckManager.Code.VERIFIED)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (!verified) {
                    receiveLock.withLock { discard(transfer) }
                    runCatching { acknowledgements.reply(transfer.metadata.id, AckManager.Code.REJECTED) }
                } // ACK failure does not undo a verified cache file or invent a sender tick.
            }
        }
    }

    /** Explicit cache ownership release. The app must finish using the file first. */
    suspend fun release(id: UUID) = withContext(Dispatchers.IO) {
        receiveLock.withLock {
            val transfer = incoming[id] ?: return@withLock
            check(transfer.complete || !transfer.verifying) { "Verification in progress" }
            discard(transfer)
            mutableCompleted.value = mutableCompleted.value - id
        }
    }
    private fun discard(t: Incoming) {
        incoming.remove(t.metadata.id)
        runCatching { t.handle.close() }
        t.file.delete()
    }

    suspend fun close(deleteCompleted: Boolean = false) = withContext(NonCancellable + Dispatchers.IO) {
        receiveLock.withLock { closed = true }
        job.cancelAndJoin()
        withContext(Dispatchers.IO) {
            receiveLock.withLock {
                incoming.values.toList().forEach { t ->
                    runCatching { t.handle.close() }
                    if (deleteCompleted || !t.complete) t.file.delete()
                }
                incoming.clear()
                if (deleteCompleted) mutableCompleted.value = emptyMap()
            }
        }
    }

    private suspend fun hash(channel: FileChannel, size: Long, block: ByteBuffer): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0L
        while (offset < size) {
            currentCoroutineContext().ensureActive()
            block.clear(); block.limit(minOf(block.capacity().toLong(), size - offset).toInt())
            readFully(channel, block, offset)
            offset += block.position()
            block.flip(); digest.update(block)
        }
        return digest.digest()
    }
    private suspend fun readFully(channel: FileChannel, buffer: ByteBuffer, offset: Long) {
        var position = offset
        while (buffer.hasRemaining()) {
            currentCoroutineContext().ensureActive()
            val read = channel.read(buffer, position)
            if (read < 0) throw EOFException("Source truncated")
            if (read == 0) { delay(1); continue }
            position += read
        }
    }

    private data class Fragment(val id: UUID, val chunkIndex: Long, val fragmentIndex: Int, val data: ByteBuffer)
    companion object {
        const val CHUNK_BYTES = 512 * 1024
        const val FRAGMENT_BYTES = 32 * 1024
        const val FRAGMENTS_PER_CHUNK = CHUNK_BYTES / FRAGMENT_BYTES
        const val PRE_ALERT_BYTES = 1024
        const val FRAGMENT_HEADER_BYTES = 36
        const val MAX_NAME_BYTES = 255
        const val MAX_INCOMING = 2
        const val RECEIVE_TIMEOUT_MS = 10 * 60 * 1_000L
        private const val METADATA_MAGIC = 0x50524131 // PRA1
        private const val FRAGMENT_MAGIC = 0x43484b31 // CHK1

        fun fragmentOffset(chunkIndex: Long, fragmentIndex: Int): Long {
            require(chunkIndex >= 0 && fragmentIndex in 0 until FRAGMENTS_PER_CHUNK)
            return Math.addExact(Math.multiplyExact(chunkIndex, CHUNK_BYTES.toLong()), fragmentIndex.toLong() * FRAGMENT_BYTES)
        }
        fun fragmentCount(size: Long): Long = size / FRAGMENT_BYTES + if (size % FRAGMENT_BYTES == 0L) 0 else 1

        fun encodeMetadata(metadata: Metadata): ByteArray {
            val name = metadata.fileName.toByteArray(Charsets.UTF_8)
            return ByteBuffer.allocate(PRE_ALERT_BYTES).order(ByteOrder.BIG_ENDIAN).putInt(METADATA_MAGIC)
                .putLong(metadata.id.mostSignificantBits).putLong(metadata.id.leastSignificantBits)
                .putLong(metadata.size).putInt(CHUNK_BYTES).putInt(FRAGMENT_BYTES).put(metadata.sha256)
                .putShort(name.size.toShort()).put(name).array() // Remaining bytes are zero padding.
        }
        fun decodeMetadata(payload: ByteBuffer): Metadata {
            require(payload.remaining() == PRE_ALERT_BYTES)
            val b = payload.slice().order(ByteOrder.BIG_ENDIAN)
            require(b.int == METADATA_MAGIC)
            val id = UUID(b.long, b.long)
            val size = b.long
            require(b.int == CHUNK_BYTES && b.int == FRAGMENT_BYTES)
            val digest = ByteArray(32).also { b.get(it) }
            val length = b.short.toInt() and 0xffff
            require(length in 1..MAX_NAME_BYTES)
            val name = ByteArray(length).also { b.get(it) }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(name)).toString()
            while (b.hasRemaining()) require(b.get() == 0.toByte()) { "Nonzero reserved metadata" }
            return Metadata(id, size, text, digest)
        }
        fun fragmentTransferId(payload: ByteBuffer): UUID? {
            if (payload.remaining() < FRAGMENT_HEADER_BYTES) return null
            val b = payload.slice().order(ByteOrder.BIG_ENDIAN)
            if (b.int != FRAGMENT_MAGIC) return null
            return UUID(b.long, b.long)
        }
        private fun decodeFragment(payload: ByteBuffer): Fragment {
            require(payload.remaining() >= FRAGMENT_HEADER_BYTES)
            val b = payload.slice().order(ByteOrder.BIG_ENDIAN)
            require(b.int == FRAGMENT_MAGIC)
            val id = UUID(b.long, b.long)
            val index = b.long
            val fragment = b.int
            val length = b.int
            require(index >= 0 && fragment in 0 until FRAGMENTS_PER_CHUNK && length in 1..FRAGMENT_BYTES && b.remaining() == length)
            return Fragment(id, index, fragment, b.slice().asReadOnlyBuffer())
        }
    }
}
