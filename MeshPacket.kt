package com.example.mesh

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.CRC32

/** Explicit wire codes: never serialize enum ordinals. */
enum class PacketType(val wireCode: Int) {
    HEARTBEAT(0), TEXT(1), PTT_VOICE(2), MEDIA_CHUNK(3), PRE_ALERT(4), DELIVERY_ACK(5);

    companion object {
        fun fromWireCode(code: Int): PacketType = values().firstOrNull { it.wireCode == code }
            ?: throw IllegalArgumentException("Unknown packet type: $code")
    }
}

/**
 * Application envelope inspired by Briar BSP's version/type/u16-length framing.
 * This is NOT a Briar BSP packet or a routing/transport implementation.
 *
 * Big-endian wire layout:
 * version:u8 | type:u8 | bodyLength:u16 | packetId:16 | clusterId:16 |
 * hopLimit:u8 | currentHops:u8 | timestamp:i64 (Unix milliseconds) |
 * senderLength:u8 | sender:UTF-8 | targetLength:u8 | target:UTF-8 |
 * payload:remaining body bytes | crc32:u32
 *
 * bodyLength excludes the four-byte header and four-byte CRC trailer.
 * CRC covers header + body. It detects corruption; it does not authenticate peers.
 * null or BROADCAST target means broadcast. Strings use standard UTF-8.
 * Wire v2: hopLimit is REMAINING TTL (0..25); each send decrements it and
 * increments currentHops. TTL zero may be consumed locally but never forwarded.
 * Version 1's fixed-budget frames are deliberately rejected; upgrade all peers.
 */
class MeshPacket(
    val type: PacketType,
    val senderId: String,
    val clusterId: UUID,
    payload: ByteArray = byteArrayOf(),
    val targetId: String? = null,
    val packetId: UUID = UUID.randomUUID(),
    val hopLimit: Int = DEFAULT_HOP_LIMIT,
    val currentHops: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    private val content = payload.copyOf()
    val payload: ByteArray get() = content.copyOf()
    val canForward: Boolean get() = hopLimit > 0

    init {
        require(isValidIdentity(senderId)) { "Invalid sender identity" }
        require(isValidTarget(targetId)) { "Invalid target identity" }
        validateHops(hopLimit, currentHops)
        require(timestamp >= 0) { "Negative timestamp" }
        require(content.size <= maxPayloadSize(senderId, targetId)) { "Payload exceeds frame limit" }
    }

    /** Call exactly once before each link traversal, including the first send. */
    fun forNextHop(): MeshPacket {
        check(canForward) { "Hop budget exhausted" }
        return MeshPacket(type, senderId, clusterId, content, targetId, packetId,
            hopLimit - 1, currentHops + 1, timestamp)
    }

    fun toByteArray(): ByteArray {
        val sender = senderId.toByteArray(Charsets.UTF_8)
        val target = targetId?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
        val bodySize = FIXED_BODY_SIZE + sender.size + target.size + content.size
        val buffer = ByteBuffer.allocate(HEADER_SIZE + bodySize + CRC_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(VERSION.toByte()).put(type.wireCode.toByte()).putShort(bodySize.toShort())
        buffer.putLong(packetId.mostSignificantBits).putLong(packetId.leastSignificantBits)
        buffer.putLong(clusterId.mostSignificantBits).putLong(clusterId.leastSignificantBits)
        buffer.put(hopLimit.toByte()).put(currentHops.toByte()).putLong(timestamp)
        buffer.put(sender.size.toByte()).put(sender)
        buffer.put(target.size.toByte()).put(target).put(content)
        val crc = CRC32().apply { update(buffer.array(), 0, buffer.position()) }
        buffer.putInt(crc.value.toInt())
        return buffer.array()
    }

    /** Caller owns stream lifecycle, flushing, and serialization of concurrent writes. */
    fun writeTo(output: OutputStream) { output.write(toByteArray()) }

    companion object {
        const val DEFAULT_HOP_LIMIT = 25
        const val VERSION = 2
        const val BROADCAST = "BROADCAST"
        const val MAX_BODY_SIZE = 65_535
        const val MAX_FRAME_SIZE = MAX_BODY_SIZE + 8
        const val MAX_NICKNAME_BYTES = 64
        private const val HEADER_SIZE = 4
        private const val CRC_SIZE = 4
        private const val FIXED_BODY_SIZE = 44

        fun isValidTarget(target: String?): Boolean =
            target == null || target == BROADCAST || isValidIdentity(target)

        private fun validateHops(ttl: Int, hops: Int) {
            require(ttl in 0..DEFAULT_HOP_LIMIT && hops in 0..DEFAULT_HOP_LIMIT &&
                ttl + hops in 1..DEFAULT_HOP_LIMIT) { "Invalid remaining TTL / hop count" }
        }

        fun isValidNickname(nickname: String): Boolean = nickname.isNotBlank() &&
            nickname == nickname.trim() && nickname.none { it == '#' || Character.isISOControl(it) } &&
            nickname.toByteArray(Charsets.UTF_8).size <= MAX_NICKNAME_BYTES &&
            // Reject unpaired surrogates instead of silently replacing them during encoding.
            Charsets.UTF_8.newEncoder().canEncode(nickname)

        fun isValidIdentity(identity: String): Boolean {
            val separator = identity.lastIndexOf('#')
            return separator > 0 && identity.length - separator - 1 == 6 &&
                identity.substring(separator + 1).all { it in '0'..'9' } &&
                isValidNickname(identity.substring(0, separator))
        }

        fun maxPayloadSize(senderId: String, targetId: String? = null): Int {
            require(isValidIdentity(senderId))
            require(isValidTarget(targetId))
            return MAX_BODY_SIZE - FIXED_BODY_SIZE - senderId.toByteArray(Charsets.UTF_8).size -
                (targetId?.toByteArray(Charsets.UTF_8)?.size ?: 0)
        }

        /** Rejects malformed, truncated, trailing, unsupported and checksum-invalid frames. */
        fun fromByteArray(frame: ByteArray): MeshPacket {
            val buffer = ByteBuffer.wrap(frame)
            val header = inspectFrame(buffer)
            val payload = ByteArray(header.payloadLength)
            buffer.position(header.payloadOffset)
            buffer.get(payload)
            return try {
                MeshPacket(header.type, header.senderId, header.clusterId, payload, header.targetId,
                    header.packetId, header.hopLimit, header.currentHops, header.timestamp)
            } finally { payload.fill(0) }
        }

        /** Validates an entire frame without allocating/copying its payload. Position must be zero. */
        fun inspectFrame(frame: ByteBuffer): FrameHeader {
            require(frame.position() == 0 && frame.limit() in
                (HEADER_SIZE + FIXED_BODY_SIZE + CRC_SIZE)..MAX_FRAME_SIZE) { "Invalid frame size/position" }
            val buffer = frame.duplicate().order(ByteOrder.BIG_ENDIAN)
            require(u8(buffer) == VERSION) { "Unsupported version" }
            val type = PacketType.fromWireCode(u8(buffer))
            val bodySize = buffer.short.toInt() and 0xffff
            require(frame.limit() == HEADER_SIZE + bodySize + CRC_SIZE) { "Frame length mismatch" }
            val bodyEnd = HEADER_SIZE + bodySize
            val expectedCrc = buffer.getInt(bodyEnd).toLong() and 0xffffffffL
            val actualCrc = checksum(frame, bodyEnd)
            require(expectedCrc == actualCrc) { "CRC32 mismatch" }
            buffer.limit(bodyEnd)
            val packetId = UUID(buffer.long, buffer.long)
            val clusterId = UUID(buffer.long, buffer.long)
            val hopLimit = u8(buffer)
            val currentHops = u8(buffer)
            val timestamp = buffer.long
            validateHops(hopLimit, currentHops)
            require(timestamp >= 0)
            val sender = readIdentity(buffer, nullable = false)!!
            val target = readIdentity(buffer, nullable = true)
            return FrameHeader(type, packetId, sender, target, clusterId, hopLimit, currentHops,
                timestamp, buffer.position(), buffer.remaining())
        }

        /** In-place v2 hop update. Caller must own the buffer and pass its inspected header. */
        fun advanceFrame(frame: ByteBuffer, header: FrameHeader) {
            require(header.hopLimit > 0) { "TTL exhausted" }
            frame.put(36, (header.hopLimit - 1).toByte())
            frame.put(37, (header.currentHops + 1).toByte())
            frame.duplicate().order(ByteOrder.BIG_ENDIAN)
                .putInt(frame.limit() - CRC_SIZE, checksum(frame, frame.limit() - CRC_SIZE).toInt())
        }

        private fun checksum(frame: ByteBuffer, end: Int): Long {
            val bytes = frame.duplicate()
            bytes.position(0)
            bytes.limit(end)
            return CRC32().apply { update(bytes) }.value
        }

        /** Standard InputStream adapter; only the caller-owned small scratch array touches heap. */
        fun readFrame(input: InputStream, frame: ByteBuffer, scratch: ByteArray): FrameHeader {
            require(frame.capacity() >= MAX_FRAME_SIZE && scratch.size >= HEADER_SIZE)
            frame.clear()
            try {
                val data = DataInputStream(input)
                data.readFully(scratch, 0, HEADER_SIZE)
                val bodySize = ((scratch[2].toInt() and 0xff) shl 8) or (scratch[3].toInt() and 0xff)
                require(bodySize >= FIXED_BODY_SIZE)
                frame.put(scratch, 0, HEADER_SIZE)
                scratch.fill(0)
                var remaining = bodySize + CRC_SIZE
                while (remaining > 0) {
                    val count = minOf(remaining, scratch.size)
                    data.readFully(scratch, 0, count)
                    frame.put(scratch, 0, count)
                    scratch.fill(0)
                    remaining -= count
                }
                frame.flip()
                return inspectFrame(frame)
            } finally { scratch.fill(0) }
        }

        /** Reads exactly one frame; readFully handles fragmented stream reads.
         * EOF/truncation throws IOException. Close the connection on invalid framing.
         * Caller supplies transport timeouts and must not share a reader concurrently.
         */
        fun readFrom(input: InputStream): MeshPacket {
            val data = DataInputStream(input)
            val header = ByteArray(HEADER_SIZE)
            data.readFully(header)
            val bodySize = ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
            require(bodySize >= FIXED_BODY_SIZE) { "Body too short" }
            val frame = ByteArray(HEADER_SIZE + bodySize + CRC_SIZE)
            header.copyInto(frame)
            data.readFully(frame, HEADER_SIZE, bodySize + CRC_SIZE)
            return fromByteArray(frame)
        }

        private fun u8(buffer: ByteBuffer): Int = buffer.get().toInt() and 0xff

        private fun readIdentity(buffer: ByteBuffer, nullable: Boolean): String? {
            require(buffer.hasRemaining()) { "Missing identity length" }
            val length = u8(buffer)
            if (length == 0 && nullable) return null
            require(length in 8..(MAX_NICKNAME_BYTES + 7) && length <= buffer.remaining()) {
                "Invalid identity length"
            }
            val bytes = ByteArray(length).also { buffer.get(it) }
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                throw IllegalArgumentException("Invalid UTF-8 identity", e)
            }
            require(isValidIdentity(text) || (nullable && text == BROADCAST)) { "Invalid identity" }
            return text
        }
    }

    /** Metadata only; never owns payload bytes. Offsets refer to a validated complete wire frame. */
    data class FrameHeader(
        val type: PacketType, val packetId: UUID, val senderId: String, val targetId: String?,
        val clusterId: UUID, val hopLimit: Int, val currentHops: Int, val timestamp: Long,
        val payloadOffset: Int, val payloadLength: Int
    )
}
