// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Supply cluster order/labels from your topology; labels must remain stable between refreshes. */
fun buildRadarClusters(
    clusterLabels: Map<UUID, String>,
    routes: List<MeshRoutingTable.Route>,
    connections: List<WifiDirectManager.Connection>,
    planningBands: Map<UUID, String> = emptyMap(),
    measuredRssiBySession: Map<UUID, Int> = emptyMap()
): List<RadarCluster> {
    val ids = (clusterLabels.keys + routes.map { it.destination.clusterId } + connections.map { it.clusterId }).distinct()
    val liveSessions = connections.map { it.sessionId }.toSet()
    return ids.map { id ->
        val nodes = linkedMapOf<String, RadarNode>()
        routes.filter { it.destination.clusterId == id }.forEach { route ->
            nodes[route.destination.nodeId] = RadarNode(route.destination.nodeId, id, route.hops,
                route.sessionId in liveSessions,
                if (route.direct) measuredRssiBySession[route.sessionId] else null)
        }
        connections.filter { it.clusterId == id }.forEach { connection ->
            nodes[connection.peerId] = RadarNode(connection.peerId, id, 1, true,
                measuredRssiBySession[connection.sessionId], connection.adjacent)
        }
        RadarCluster(id, clusterLabels[id] ?: "Cluster ${id.toString().take(8)}",
            nodes.values.sortedBy { it.id.lowercase() }, planningBands[id])
    }
}

/**
 * Call from a ViewModel/service-owned coroutine. Callback executes on IO: publish
 * immutable state to StateFlow, never manipulate Views. Retains Step 4 VERIFIED ACK semantics.
 * The source must be a stable endpoint file (a document-provider Uri is not a File).
 */
suspend fun sendChatFile(
    source: File,
    targetId: String,
    clusterId: UUID,
    chunks: ChunkManager,
    requestId: UUID = UUID.randomUUID(),
    onState: (FileTransferUi) -> Unit
): UUID = withContext(Dispatchers.IO) {
    var state = FileTransferUi(requestId, source.name, 0, null, FileTransferPhase.PREPARING)
    fun publish(phase: FileTransferPhase) { state = state.copy(phase = phase); onState(state) }
    try {
        onState(state)
        val transferId = chunks.sendFile(source, targetId, clusterId, onProgress = { sent, total ->
            state = state.copy(sentBytes = sent, totalBytes = total)
            publish(when {
                total == 0L || sent == total -> FileTransferPhase.VERIFYING
                sent == 0L -> FileTransferPhase.PREPARING
                else -> FileTransferPhase.SENDING
            })
        })
        publish(FileTransferPhase.DELIVERED) // sendFile returns only after verified reverse ACK.
        transferId
    } catch (e: CancellationException) {
        publish(FileTransferPhase.CANCELLED); throw e
    } catch (e: Exception) {
        publish(FileTransferPhase.FAILED); throw e
    }
}
