// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import java.util.Locale
import java.util.UUID

data class RadarNode(
    val id: String,
    val clusterId: UUID,
    val hops: Int,
    val connected: Boolean,
    val rssiDbm: Int? = null,
    val bridge: Boolean = false
) {
    init { require(hops >= 0) }
}

data class RadarCluster(
    val id: UUID,
    val label: String,
    val nodes: List<RadarNode>,
    /** Optional deployment/planning band, NEVER inferred from hop counts. */
    val planningBand: String? = null
)

enum class ChatDelivery { PENDING, SENT, DELIVERED, FAILED }
data class ChatMessageUi(
    val id: String,
    val sender: String,
    val text: String,
    val timestampMillis: Long,
    val outgoing: Boolean,
    val delivery: ChatDelivery = ChatDelivery.SENT
)

enum class FileTransferPhase { PREPARING, SENDING, VERIFYING, DELIVERED, FAILED, CANCELLED }
data class FileTransferUi(
    val id: UUID,
    val name: String,
    val sentBytes: Long,
    val totalBytes: Long?,
    val phase: FileTransferPhase
) {
    init { require(sentBytes >= 0 && (totalBytes == null || totalBytes >= 0)) }
    val fraction: Float get() = transferFraction(sentBytes, totalBytes, phase == FileTransferPhase.DELIVERED)
    val active: Boolean get() = phase in setOf(FileTransferPhase.PREPARING, FileTransferPhase.SENDING, FileTransferPhase.VERIFYING)
}

internal fun transferFraction(sent: Long, total: Long?, delivered: Boolean = false): Float = when {
    delivered -> 1f
    total == null || total <= 0 -> 0f
    else -> (sent.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

internal fun displayBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0)
    return when {
        value >= (1L shl 30) -> String.format(Locale.getDefault(), "%.2f GiB", value / (1L shl 30).toDouble())
        value >= (1L shl 20) -> String.format(Locale.getDefault(), "%.1f MiB", value / (1L shl 20).toDouble())
        value >= (1L shl 10) -> String.format(Locale.getDefault(), "%.1f KiB", value / 1024.0)
        else -> "$value B"
    }
}

/** Null represents unknown; RSSI is never synthesized from route/hop information. */
internal fun signalBars(rssiDbm: Int?): Int? = when {
    rssiDbm == null || rssiDbm !in -126..-1 -> null
    rssiDbm >= -55 -> 4
    rssiDbm >= -65 -> 3
    rssiDbm >= -75 -> 2
    rssiDbm >= -85 -> 1
    else -> 0
}
