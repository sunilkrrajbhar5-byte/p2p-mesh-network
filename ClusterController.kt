// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Logical application membership. Android does not expose a portable radio client limit. */
class ClusterController(
    val localId: String,
    val clusterId: UUID,
    val ownerId: String
) {
    enum class Role(val code: Int) {
        OWNER(0), CLIENT(1), BRIDGE(2);
        companion object {
            fun fromCode(code: Int): Role = values().firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Invalid cluster role")
        }
    }
    data class Member(val nodeId: String, val sessionId: UUID, val role: Role)
    data class AdjacentLink(val clusterId: UUID, val ownerId: String, val sessionId: UUID)
    data class Snapshot(
        val localRole: Role,
        val clients: List<Member>,
        val ownerSession: UUID?,
        val adjacent: AdjacentLink?
    )

    val isOwner: Boolean get() = localId == ownerId
    private val lock = Any()
    private val clients = LinkedHashMap<String, Member>()
    private val bridgeCapableSessions = HashSet<UUID>()
    private var ownerSession: UUID? = null
    private var adjacent: AdjacentLink? = null
    private var localRole = if (isOwner) Role.OWNER else Role.CLIENT
    private val mutableState = MutableStateFlow(snapshot())
    val state: StateFlow<Snapshot> = mutableState.asStateFlow()

    init {
        require(MeshPacket.isValidIdentity(localId) && MeshPacket.isValidIdentity(ownerId))
    }

    /** Atomic admission, including duplicate protection. Fifth device = fourth client. */
    fun admitClient(nodeId: String, sessionId: UUID, bridgeCapable: Boolean = true): Member = synchronized(lock) {
        check(isOwner) { "Only the group owner admits local clients" }
        require(MeshPacket.isValidIdentity(nodeId) && nodeId != ownerId)
        check(nodeId !in clients) { "Duplicate identity/session" }
        check(clients.size < MAX_CLIENTS) { "Cluster full (owner + four clients)" }
        if (bridgeCapable) bridgeCapableSessions.add(sessionId)
        clients[nodeId] = Member(nodeId, sessionId, Role.CLIENT)
        if (clients.size == MAX_CLIENTS && clients.values.none { it.role == Role.BRIDGE }) {
            // Browsers are leaves. Prefer the fifth node, otherwise the latest capable native peer.
            clients.values.lastOrNull { it.sessionId in bridgeCapableSessions }?.let {
                clients[it.nodeId] = it.copy(role = Role.BRIDGE)
            }
        }
        publish()
        checkNotNull(clients[nodeId])
    }

    fun roleFor(sessionId: UUID): Role? = synchronized(lock) {
        clients.values.firstOrNull { it.sessionId == sessionId }?.role
    }

    fun attachOwner(nodeId: String, sessionId: UUID, assignedRole: Role) = synchronized(lock) {
        check(!isOwner && nodeId == ownerId && ownerSession == null)
        require(assignedRole != Role.OWNER)
        ownerSession = sessionId
        localRole = assignedRole
        publish()
    }

    fun updateRole(sessionId: UUID, assignedRole: Role) = synchronized(lock) {
        check(ownerSession == sessionId)
        require(assignedRole != Role.OWNER)
        localRole = assignedRole
        if (assignedRole != Role.BRIDGE) adjacent = null
        publish()
    }

    /** Called only after the second socket handshake succeeds while the home link is alive. */
    fun attachAdjacent(otherCluster: UUID, otherOwner: String, sessionId: UUID) = synchronized(lock) {
        check(localRole == Role.BRIDGE && ownerSession != null) { "No active bridge designation" }
        check(adjacent == null) { "Already linked to an adjacent cluster" }
        require(otherCluster != clusterId && otherOwner != localId && MeshPacket.isValidIdentity(otherOwner))
        adjacent = AdjacentLink(otherCluster, otherOwner, sessionId)
        publish()
    }

    /** Session token prevents an old disconnect from deleting a newer connection. */
    fun release(sessionId: UUID) = synchronized(lock) {
        bridgeCapableSessions.remove(sessionId)
        clients.entries.removeAll { it.value.sessionId == sessionId }
        if (ownerSession == sessionId) {
            ownerSession = null
            localRole = Role.CLIENT
            adjacent = null
        }
        if (adjacent?.sessionId == sessionId) adjacent = null
        publish()
    }

    private fun snapshot() = Snapshot(localRole, clients.values.toList(), ownerSession, adjacent)
    private fun publish() { mutableState.value = snapshot() }

    companion object {
        const val MAX_DEVICES = 5
        const val MAX_CLIENTS = MAX_DEVICES - 1
    }
}
