package com.example.mesh

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.CRC32

/** Standalone JVM checks: compile with MeshPacket.kt, then run this main. */
fun main() {
    val cluster = UUID.fromString("00000000-0000-0000-0000-000000000001")
    fun reject(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return }
        error("Expected rejection")
    }
    fun refreshCrc(bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }
        ByteBuffer.wrap(bytes).putInt(bytes.size - 4, crc.value.toInt())
    }
    for (type in PacketType.values()) {
        for (target in listOf(null, MeshPacket.BROADCAST, "Bob#000001")) {
            val input = byteArrayOf(0, 1, -1, 42)
            val original = MeshPacket(type, "Alice#123456", cluster, input, target)
            input[0] = 99
            check(original.payload[0] == 0.toByte())
            val frame = original.toByteArray()
            val decoded = MeshPacket.fromByteArray(frame)
            check(decoded.toByteArray().contentEquals(frame))
            check(decoded.packetId == original.packetId && decoded.clusterId == cluster)
            check(decoded.type == type && decoded.targetId == target)
            val fragmented = object : ByteArrayInputStream(frame + frame) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, minOf(len, 1))
            }
            repeat(2) { check(MeshPacket.readFrom(fragmented).toByteArray().contentEquals(frame)) }
            check(fragmented.read() == -1)
            reject { MeshPacket.fromByteArray(frame.copyOf(frame.size - 1)) }
            reject { MeshPacket.fromByteArray(frame + byteArrayOf(0)) }
            val corrupt = frame.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
            reject { MeshPacket.fromByteArray(corrupt) }
            val badType = frame.copyOf().also { it[1] = 100; refreshCrc(it) }
            reject { MeshPacket.fromByteArray(badType) }
            // First sender byte follows header, UUIDs, hops, timestamp and length.
            val badUtf8 = frame.copyOf().also { it[47] = 0xff.toByte(); refreshCrc(it) }
            reject { MeshPacket.fromByteArray(badUtf8) }
        }
    }
    val max = MeshPacket.maxPayloadSize("Alice#123456")
    val largest = MeshPacket(PacketType.MEDIA_CHUNK, "Alice#123456", cluster, ByteArray(max))
    check(largest.toByteArray().size == 65_543)
    check(MeshPacket.fromByteArray(largest.toByteArray()).payload.size == max)
    reject { MeshPacket(PacketType.TEXT, "Alice#123456", cluster, ByteArray(max + 1)) }
    var packet = MeshPacket(PacketType.HEARTBEAT, "Alice#123456", cluster)
    val id = packet.packetId
    repeat(25) { packet = packet.forNextHop() }
    check(packet.hopLimit == 0 && packet.currentHops == 25 && !packet.canForward && packet.packetId == id)
    check(runCatching { packet.forNextHop() }.exceptionOrNull() is IllegalStateException)
    reject { MeshPacket(PacketType.TEXT, "Alice#12345", cluster) }
    println("MeshPacket checks passed")
}
