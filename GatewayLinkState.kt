package com.example.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Publishes only a successfully started HTTPS gateway, never a guessed Wi-Fi IP. */
object GatewayLinkState {
    data class Active(val hostId: String, val url: String)
    private val gateways = linkedSetOf<UniversalMeshGateway>()
    private val state = MutableStateFlow<List<Active>>(emptyList())
    val active = state.asStateFlow()
    @Synchronized internal fun started(gateway: UniversalMeshGateway) {
        if (!gateway.origin.startsWith("https://")) return
        gateways.add(gateway); publish()
    }
    @Synchronized internal fun stopped(gateway: UniversalMeshGateway) { gateways.remove(gateway); publish() }
    private fun publish() {
        state.value = gateways.map { Active(it.hostId, it.origin.trimEnd('/') + "/") }.distinct()
    }
}
