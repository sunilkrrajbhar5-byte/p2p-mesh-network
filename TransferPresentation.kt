package com.example.mesh

import java.io.InputStream
import java.security.MessageDigest

data class TransferHudState(
    val name: String, val peer: String, val bytes: Long, val total: Long,
    val phase: String, val bytesPerSecond: Double = 0.0,
    val saved: SavedMeshFile? = null, val failed: Boolean = false,
    val retrySaveAvailable: Boolean = false
) {
    val fraction: Float get() = if(saved != null || phase == "Delivered • SHA-256 verified") 1f else
        if (total > 0) (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    val secondsRemaining: Long? get() = if (bytesPerSecond > 0 && bytes < total)
        ((total - bytes) / bytesPerSecond).toLong() else null
}
data class SavedMeshFile(val id: String, val name: String, val uri: String, val mime: String, val size: Long)

internal fun safeDownloadName(name: String): String {
    val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
        .map { if (it.isISOControl() || it in ":*?\"<>|") '_' else it }.joinToString("").trim().trim('.')
    val result = StringBuilder()
    var bytes = 0
    for (point in cleaned.codePoints().toArray()) {
        val text = String(Character.toChars(point))
        val count = text.toByteArray(Charsets.UTF_8).size
        if (bytes + count > 180 || result.length + text.length > 120) break
        result.append(text); bytes += count
    }
    return result.toString().ifBlank { "mesh-file.bin" }
}

internal fun verifyStoredFile(input: InputStream, expectedSize: Long, expectedHash: ByteArray) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(512 * 1024)
    var count = 0L
    input.use { while (true) { val n = it.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); count += n } }
    buffer.fill(0)
    check(count == expectedSize && MessageDigest.isEqual(digest.digest(), expectedHash)) { "Saved file integrity check failed" }
}
