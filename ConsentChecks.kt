package com.example.mesh

import java.util.UUID
import kotlinx.coroutines.*

fun main() = runBlocking {
    val peer = "Sunil#056535"
    fun request() = PeerConsentController.Request(UUID.randomUUID(), peer, PeerConsentController.Kind.CONNECTION)
    val controller = PeerConsentController(timeoutMs = 100)
    check(!controller.ask(request())) // No UI means no implicit consent.
    controller.setUiVisible(true)
    val first = request()
    val accepted = async(start = CoroutineStart.UNDISPATCHED) { controller.ask(first) }
    check(controller.pending.value.single() == first)
    check(!controller.decide(UUID.randomUUID(), true))
    check(controller.decide(first.id, true))
    check(!controller.decide(first.id, false)) // A decision cannot be changed or replayed.
    check(accepted.await())
    check(controller.pending.value.isEmpty())
    val second = request()
    val denied = async(start = CoroutineStart.UNDISPATCHED) { controller.ask(second) }
    controller.decide(second.id, false)
    check(!denied.await())
    val background = async(start = CoroutineStart.UNDISPATCHED) { controller.ask(request()) }
    controller.setUiVisible(false)
    check(!background.await())
    controller.setUiVisible(true)
    check(!controller.ask(request())) // Deadline must decline and remove UI state.
    check(controller.pending.value.isEmpty())
    val pending = (1..4).map { async(start = CoroutineStart.UNDISPATCHED) { controller.ask(request()) } }
    check(!controller.ask(request())) // Bounded resource use.
    controller.cancelPeer(peer)
    check(pending.all { !it.await() })
    val cancelled = async(start = CoroutineStart.UNDISPATCHED) { controller.ask(request()) }
    cancelled.cancelAndJoin()
    check(controller.pending.value.isEmpty())
    val id = UUID.randomUUID()
    for (value in listOf(true, false)) {
        val payload = ConsentWire.encode(id, value)
        val frame = MeshPacket(PacketType.CONSENT_RESPONSE, peer, UUID.randomUUID(), payload, "Receiver#123456").forNextHop()
        val decoded = MeshPacket.fromByteArray(frame.toByteArray())
        check(ConsentWire.decode(decoded.payload) == id to value)
    }
    check(runCatching { ConsentWire.decode(ByteArray(18)) }.isFailure)
    check(runCatching { ConsentWire.decode(ByteArray(17).also { it[16] = 2 }) }.isFailure)
    println("Consent accept/reject, correlation, replay, background, timeout, capacity, cancellation and wire checks passed")
}
