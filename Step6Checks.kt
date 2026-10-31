package com.example.mesh

import java.util.UUID

fun main() {
    val size = 5L * 1024 * 1024 * 1024
    check(transferFraction(size / 2, size) == 0.5f) // Beyond signed 32-bit counts.
    check(transferFraction(Long.MAX_VALUE / 2, Long.MAX_VALUE) in 0.499f..0.501f)
    check(transferFraction(10, null) == 0f)
    check(transferFraction(10, 0) == 0f)
    check(transferFraction(20, 10) == 1f)
    check(transferFraction(0, 0, delivered = true) == 1f)
    check(displayBytes(size).contains("GiB"))
    check(signalBars(null) == null && signalBars(0) == null && signalBars(-127) == null)
    check(signalBars(-55) == 4 && signalBars(-90) == 0)
    val group = UUID.randomUUID()
    val session = UUID.randomUUID()
    val other = UUID.randomUUID()
    val connection = WifiDirectManager.Connection(session, "Alice#123456", group, false)
    val routes = listOf(
        MeshRoutingTable.Route(MeshRoutingTable.Destination("Alice#123456", group), "Alice#123456", session, 1, 0, true),
        MeshRoutingTable.Route(MeshRoutingTable.Destination("Bob#654321", other), "Alice#123456", session, 3, 0, false)
    )
    val clusters = buildRadarClusters(linkedMapOf(group to "Cluster 1", other to "Cluster 2"),
        routes, listOf(connection), mapOf(group to "0–400 m", other to "400–800 m"), mapOf(session to -62))
    check(clusters[0].nodes.size == 1) // Direct connection and route are deduplicated.
    check(clusters[0].nodes.single().rssiDbm == -62)
    check(clusters[1].nodes.single().hops == 3)
    check(clusters[1].nodes.single().rssiDbm == null) // No fabricated remote signal strength.
    check(clusters[1].nodes.single().connected)
    val lost = buildRadarClusters(emptyMap(), routes, emptyList())
    check(lost.flatMap { it.nodes }.none { it.connected })
    println("Step 6 checks passed: large-file progress, unknown RSSI, route reachability and cluster grouping")
}
