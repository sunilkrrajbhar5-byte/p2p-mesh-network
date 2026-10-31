// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/** Route freshness uses an injected monotonic clock, never remote packet timestamps. */
class MeshRoutingTable(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Destination(val nodeId: String, val clusterId: UUID)
    data class Route(
        val destination: Destination,
        val nextHop: String,
        val sessionId: UUID,
        val hops: Int,
        val lastSeenMillis: Long,
        val direct: Boolean
    )
    private val lock = Any()
    private val entries = LinkedHashMap<Destination, Route>()
    private val mutableRoutes = MutableStateFlow<List<Route>>(emptyList())
    val routes: StateFlow<List<Route>> = mutableRoutes.asStateFlow()

    fun refreshDirect(nodeId: String, clusterId: UUID, sessionId: UUID) = synchronized(lock) {
        require(MeshPacket.isValidIdentity(nodeId))
        val destination = Destination(nodeId, clusterId)
        if (destination !in entries && entries.size >= MAX_ROUTES) {
            evictLocked()
            if (entries.size >= MAX_ROUTES) {
                val replaceable = entries.values.firstOrNull { !it.direct }?.destination
                check(replaceable != null) { "Direct route capacity exhausted" }
                entries.remove(replaceable)
            }
        }
        entries[destination] = Route(destination, nodeId, sessionId, 1, nowMillis(), true)
        publish()
    }

    /** For a later validated route-advertisement layer; arbitrary inbound data does not learn routes. */
    fun learn(destination: Destination, viaSession: UUID, hops: Int): Boolean = synchronized(lock) {
        require(MeshPacket.isValidIdentity(destination.nodeId) && hops in 2..MeshPacket.DEFAULT_HOP_LIMIT)
        evictLocked()
        val direct = entries.values.firstOrNull { it.direct && it.sessionId == viaSession }
            ?: return@synchronized false
        val old = entries[destination]
        if (old != null && (old.direct || (old.hops < hops && old.sessionId != viaSession)))
            return@synchronized false
        if (old == null && entries.size >= MAX_ROUTES) return@synchronized false
        entries[destination] = Route(destination, direct.nextHop, viaSession, hops, nowMillis(), false)
        publish()
        true
    }

    fun lookup(nodeId: String, clusterId: UUID): Route? = synchronized(lock) {
        evictLocked()
        entries[Destination(nodeId, clusterId)]
    }

    fun removeSession(sessionId: UUID) = synchronized(lock) {
        entries.entries.removeAll { it.value.sessionId == sessionId }
        publish()
    }

    /** Returns dead direct sessions so the transport can close them and release membership. */
    fun evictExpired(): Set<UUID> = synchronized(lock) { evictLocked() }

    /** Manager sends pings to live sessions, even when a route has just expired. */
    fun startMaintenance(
        scope: CoroutineScope,
        sendHeartbeats: suspend () -> Unit,
        onExpired: (Set<UUID>) -> Unit
    ): Job = scope.launch {
        while (isActive) {
            onExpired(evictExpired())
            sendHeartbeats()
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun evictLocked(): Set<UUID> {
        val now = nowMillis()
        val dead = entries.values.filter { it.direct && now - it.lastSeenMillis >= ROUTE_TIMEOUT_MS }
            .mapTo(mutableSetOf()) { it.sessionId }
        entries.entries.removeAll {
            now - it.value.lastSeenMillis >= ROUTE_TIMEOUT_MS || it.value.sessionId in dead
        }
        publish()
        return dead
    }
    private fun publish() { mutableRoutes.value = entries.values.toList() }

    companion object {
        const val HEARTBEAT_INTERVAL_MS = 3_000L
        const val ROUTE_TIMEOUT_MS = 30_000L
        const val MAX_ROUTES = 1_024
    }
}
