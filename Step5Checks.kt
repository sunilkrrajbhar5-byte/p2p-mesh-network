package com.example.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

fun main() {
    val stream = UUID.randomUUID()
    val frame = VoiceFrame(stream, 17, 1_088_000, ByteArray(256) { it.toByte() })
    val encoded = frame.encode()
    // A borrowed relay slice must remain unchanged, including its position/limit.
    val direct = ByteBuffer.allocateDirect(encoded.size + 12).order(ByteOrder.LITTLE_ENDIAN)
    direct.position(7); direct.put(encoded); direct.limit(7 + encoded.size); direct.position(7)
    val decoded = VoiceFrame.decode(direct.asReadOnlyBuffer())
    check(direct.position() == 7 && direct.limit() == 7 + encoded.size)
    check(decoded.streamId == stream && decoded.sequence == 17 && decoded.ptsUs == 1_088_000L)
    check(decoded.audio.contentEquals(frame.audio))
    for (i in 7 until direct.limit()) direct.put(i, 0)
    check(decoded.audio.contentEquals(frame.audio)) // No retained relay-buffer view.
    val huge = VoiceFrame(stream, 0, 0, ByteArray(VoiceFrame.MAX_AUDIO)).encode()
    check(huge.size == VoiceFrame.MAX_PAYLOAD)
    fun reject(bytes: ByteArray) = check(runCatching { VoiceFrame.decode(ByteBuffer.wrap(bytes)) }.isFailure)
    reject(ByteArray(32)); reject(ByteArray(VoiceFrame.MAX_PAYLOAD + 1))
    reject(encoded.copyOf().also { it[0] = 0 })
    reject(encoded.copyOf().also { ByteBuffer.wrap(it).putInt(20, -1) })
    reject(encoded.copyOf().also { ByteBuffer.wrap(it).putLong(24, -1) })
    reject(encoded.copyOf().also { ByteBuffer.wrap(it).putLong(24, Long.MAX_VALUE) })
    val packet = MeshPacket(PacketType.PTT_VOICE, "Alice#123456", UUID.randomUUID(), encoded, "Bob#654321")
    val restored = MeshPacket.fromByteArray(packet.toByteArray())
    check(restored.type == PacketType.PTT_VOICE)
    check(VoiceFrame.decode(ByteBuffer.wrap(restored.payload)).audio.contentEquals(frame.audio))
    println("Step 5 checks passed: bounds, malformed payloads, borrowed-buffer ownership, mesh round-trip")
}
