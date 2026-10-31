// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun MeshRadarScreen(
    clusters: List<RadarCluster>,
    scanning: Boolean,
    onScan: () -> Unit,
    onNodeSelected: (RadarNode) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(clusters, query) {
        clusters.mapNotNull { cluster ->
            val nodes = if (cluster.label.contains(query.trim(), ignoreCase = true)) cluster.nodes
            else cluster.nodes.filter { it.id.contains(query.trim(), ignoreCase = true) }
            if (nodes.isEmpty() && query.isNotBlank()) null else cluster.copy(nodes = nodes)
        }
    }
    Column(modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Text("Mesh radar", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
        OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search people or clusters") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${clusters.sumOf { it.nodes.size }} known nodes")
            Button(onClick = onScan, enabled = !scanning) { Text(if (scanning) "Searching…" else "Search nearby") }
        }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (clusters.any { it.planningBand != null }) {
            Text("Distance bands are deployment estimates, not measured range.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filtered.isEmpty()) item("empty") {
                Text(if (query.isBlank()) "No nodes yet. Search nearby to discover your mesh." else "No matching nodes.")
            }
            filtered.forEach { cluster ->
                item("cluster:${cluster.id}") {
                    Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                        Text(cluster.label, style = MaterialTheme.typography.titleMedium)
                        cluster.planningBand?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (cluster.nodes.isEmpty()) item("empty:${cluster.id}") { Text("No nodes in this cluster") }
                items(cluster.nodes, key = { "${cluster.id}:${it.id}" }) { node ->
                    Card(onClick = { onNodeSelected(node) }, enabled = node.connected,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(node.id, style = MaterialTheme.typography.titleMedium)
                            Text("${node.hops} ${if (node.hops == 1) "hop" else "hops"} · " +
                                if (node.connected) "Reachable" else "Route unavailable")
                            if (node.bridge) Text("Adjacent-cluster link", style = MaterialTheme.typography.labelMedium)
                            SignalStrength(node.rssiDbm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalStrength(rssi: Int?) {
    val bars = signalBars(rssi)
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outlineVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(30.dp, 22.dp).semantics {
            contentDescription = if (bars == null) "Signal unknown" else "Signal $bars of 4"
        }) {
            val width = size.width / 6f
            repeat(4) { index ->
                val height = size.height * (index + 1) / 4f
                drawRect(if (bars != null && index < bars) active else inactive,
                    Offset(index * width * 1.5f, size.height - height), Size(width, height))
            }
        }
        Text(if (bars == null) "Signal unavailable" else "Link RSSI $rssi dBm",
            style = MaterialTheme.typography.bodySmall)
    }
}
