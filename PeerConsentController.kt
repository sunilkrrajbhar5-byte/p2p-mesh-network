package com.example.mesh

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

class RequestDeclinedException : IOException("Request Declined")

/** In-memory, bounded, single-use consent. Never persists approvals across sessions. */
class PeerConsentController(private val timeoutMs: Long = 40_000) {
    enum class Kind { CONNECTION, FILE, VOICE }
    data class Request(val id: UUID, val peerId: String, val kind: Kind,
        val fileName: String? = null, val fileBytes: Long? = null)
    private val lock = Any()
    private val waiting = linkedMapOf<UUID, Pair<Request, CompletableDeferred<Boolean>>>()
    private val state = MutableStateFlow<List<Request>>(emptyList())
    val pending = state.asStateFlow()
    private var visible = false

    fun setUiVisible(value: Boolean) = synchronized(lock) {
        visible = value
        if (!value) waiting.values.forEach { it.second.complete(false) }
    }
    suspend fun ask(request: Request): Boolean {
        require(MeshPacket.isValidIdentity(request.peerId))
        val result = synchronized(lock) {
            if (!visible || waiting.size >= 4 || waiting.containsKey(request.id)) return false
            CompletableDeferred<Boolean>().also {
                waiting[request.id] = request to it
                state.value = waiting.values.map { entry -> entry.first }
            }
        }
        return try { withTimeoutOrNull(timeoutMs) { result.await() } == true }
        finally { synchronized(lock) { waiting.remove(request.id); state.value = waiting.values.map { it.first } } }
    }
    fun decide(id: UUID, accepted: Boolean) = synchronized(lock) {
        waiting[id]?.second?.complete(accepted && visible) ?: false
    }
    fun cancel(id: UUID) { decide(id, false) }
    fun cancelPeer(peerId: String) = synchronized(lock) {
        waiting.values.filter { it.first.peerId == peerId }.forEach { it.second.complete(false) }
    }
}
