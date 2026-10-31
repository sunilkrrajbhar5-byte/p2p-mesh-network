// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PTT2 = complete, independently decodable MediaRecorder container; NOT raw AAC. */
object BrowserVoiceWire {
    const val MAGIC = 0x50545432
    const val HEADER = 33
    const val MAX_BYTES = 20 * 1024
    fun validate(bytes: ByteArray) {
        require(bytes.size in HEADER + 1..MAX_BYTES)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(b.int == MAGIC)
        b.position(20)
        require(b.int >= 0 && b.long in 0..86_400_000_000L)
        require(b.get().toInt() in 1..2) // 1 WebM/Opus; 2 MP4/AAC. Full container per packet.
    }
}
