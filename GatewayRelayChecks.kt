package com.example.mesh

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.UUID
import fi.iki.elonen.NanoWSD

fun main() = runBlocking {
    // Only a header is supplied: the parser must reject BEFORE attempting payload allocation/read.
    val oversizedHeader = byteArrayOf(0x82.toByte(), 0xff.toByte(), 0, 0, 0, 0, 0, 1, 0, 8, 1, 2, 3, 4)
    val oversized = runCatching { NanoWSD.WebSocketFrame.read(ByteArrayInputStream(oversizedHeader)) }.exceptionOrNull()
    check(oversized is NanoWSD.WebSocketException && oversized.code == NanoWSD.WebSocketFrame.CloseCode.MessageTooBig)
    val unmasked = runCatching { NanoWSD.WebSocketFrame.read(ByteArrayInputStream(byteArrayOf(0x82.toByte(), 0))) }.exceptionOrNull()
    check(unmasked is NanoWSD.WebSocketException && unmasked.code == NanoWSD.WebSocketFrame.CloseCode.ProtocolError)
    val browser = UUID.randomUUID()
    val native = UUID.randomUUID()
    val cluster = UUID.randomUUID()
    val output = mutableListOf<Pair<UUID, MeshPacket>>()
    fun capture(id: UUID, frame: ByteBuffer): Boolean {
        val bytes = ByteArray(frame.remaining()); frame.get(bytes)
        output += id to MeshPacket.fromByteArray(bytes)
        return true
    }
    val filter = RelayPacketFilter("Host#123456", RelayPacketFilter.LocalConsumer { _, _ -> },
        { _, _ -> listOf(native) }, RelayPacketFilter.FrameWriter { id, frame, _ -> capture(id, frame) })
    val bridge = object : RelayPacketFilter.TransportBridge {
        override fun owns(sessionId: UUID) = sessionId == browser
        override fun routes(header: MeshPacket.FrameHeader) =
            if (header.targetId == "Browser#111111" || header.targetId == MeshPacket.BROADCAST) listOf(browser) else emptyList()
        override suspend fun write(sessionId: UUID, frame: ByteBuffer) = capture(sessionId, frame)
    }
    filter.attachBridge(bridge)
    try {
        val first = MeshPacket(PacketType.TEXT, "Native#222222", cluster, byteArrayOf(1), "Browser#111111").forNextHop()
        val result = filter.receive(native, ByteArrayInputStream(first.toByteArray())) { error("Unexpected heartbeat") }
        check(result.forwardedTo == 1 && output.single().first == browser)
        check(output.single().second.currentHops == 2 && output.single().second.hopLimit == 23)
        check(filter.receive(native, ByteArrayInputStream(first.toByteArray())) {}.reason == RelayPacketFilter.Reason.DUPLICATE)
        output.clear()
        val broadcast = MeshPacket(PacketType.TEXT, "Browser#111111", cluster, byteArrayOf(2), MeshPacket.BROADCAST).forNextHop()
        filter.receive(browser, ByteArrayInputStream(broadcast.toByteArray())) {}
        check(output.single().first == native) // No echo back to browser ingress.
        val voice = ByteBuffer.allocate(34).putInt(BrowserVoiceWire.MAGIC).putLong(1).putLong(2).putInt(0).putLong(0).put(2).put(1).array()
        BrowserVoiceWire.validate(voice)
        val decoded = VoiceFrame.decode(ByteBuffer.wrap(voice))
        check(decoded.containerKind == 2 && decoded.audio.contentEquals(byteArrayOf(1)))
        check(runCatching { BrowserVoiceWire.validate(voice.copyOf().also { it[32] = 9 }) }.isFailure)
        check(runCatching { BrowserVoiceWire.validate(ByteArray(20*1024+1)) }.isFailure)
        val controller = ClusterController("Host#123456", cluster, "Host#123456")
        val capable = UUID.randomUUID()
        controller.admitClient("Native#222222", capable)
        repeat(3) { controller.admitClient("Web${it}#111111", UUID.randomUUID(), bridgeCapable = false) }
        check(controller.roleFor(capable) == ClusterController.Role.BRIDGE)
        check(controller.state.value.clients.filter { it.nodeId.startsWith("Web") }.all { it.role == ClusterController.Role.CLIENT })
        println("Gateway relay checks passed: hybrid routing, TTL/CRC, deduplication, no echo, PTT2 bounds and native-only bridge election")
    } finally { filter.detachBridge(bridge); filter.close(); filter.awaitWiped() }
}
