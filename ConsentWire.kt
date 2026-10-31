package com.example.mesh

import java.nio.ByteBuffer
import java.util.UUID

/** Control payload inside the existing CRC-checked MeshPacket, never a second packet engine. */
object ConsentWire {
    const val BYTES = 17
    fun encode(id: UUID, accepted: Boolean = false): ByteArray = ByteBuffer.allocate(BYTES)
        .putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
        .put(if (accepted) 1.toByte() else 0.toByte()).array()
    fun decode(bytes: ByteArray): Pair<UUID, Boolean> {
        require(bytes.size == BYTES)
        val b = ByteBuffer.wrap(bytes)
        val id = UUID(b.long, b.long)
        val code = b.get().toInt()
        require(code == 0 || code == 1)
        return id to (code == 1)
    }
}
