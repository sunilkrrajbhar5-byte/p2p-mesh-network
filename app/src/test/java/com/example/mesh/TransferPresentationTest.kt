package com.example.mesh

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class TransferPresentationTest {
    @Test fun filenamesCannotEscapeDownloadDirectory() {
        assertEquals("payload.zip", safeDownloadName("../../payload.zip"))
        assertEquals("payload.zip", safeDownloadName("C:\\private\\payload.zip"))
        assertEquals("mesh-file.bin", safeDownloadName(".."))
        assertFalse(safeDownloadName("bad\u0000name?.zip").contains('\u0000'))
        assertTrue(safeDownloadName("a".repeat(300)).length <= 120)
    }
    @Test fun publicCopyMustMatchSizeAndDigest() {
        val data = ByteArray(1024 * 1024 + 19) { (it % 251).toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        verifyStoredFile(data.inputStream(), data.size.toLong(), digest)
        assertThrows(IllegalStateException::class.java) { verifyStoredFile(data.copyOf(data.size-1).inputStream(), data.size.toLong(), digest) }
        data[9] = (data[9]+1).toByte()
        assertThrows(IllegalStateException::class.java) { verifyStoredFile(data.inputStream(), data.size.toLong(), digest) }
    }
    @Test fun estimatedTimeUsesActualRateAndUnknownRateHasNoCountdown() {
        val transfer = TransferHudState("test.zip", "Peer#123456", 250, 1000, "Receiving", 250.0)
        assertEquals(3L, transfer.secondsRemaining)
        assertEquals(.25f, transfer.fraction)
        assertNull(transfer.copy(bytesPerSecond=0.0).secondsRemaining)
        assertEquals(1f, transfer.copy(bytes=2000).fraction)
        assertFalse(transfer.copy(bytes=1000).saved != null)
    }
}
