// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import android.os.Debug
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.UUID

private data class DebugMemory(val javaUsed: Long, val javaLimit: Long, val nativeUsed: Long, val pss: Long?)
private data class SocketEvent(val id: Long, val time: Long, val message: String)

/** Place inside a Box using Modifier.align(Alignment.BottomEnd). No payload/PIN logging. */
@Composable
fun DebugConsoleOverlay(
    connections: StateFlow<List<WifiDirectManager.Connection>>,
    modifier: Modifier = Modifier
) {
    val sockets by connections.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val memory by produceState<DebugMemory?>(null, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                value = withContext(Dispatchers.IO) {
                    val runtime = Runtime.getRuntime()
                    val pss = runCatching {
                        Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong() * 1024
                    }.getOrNull()
                    DebugMemory(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(),
                        Debug.getNativeHeapAllocatedSize(), pss)
                }
                delay(1_000)
            }
        }
    }
    var previous by remember { mutableStateOf<Map<UUID, WifiDirectManager.Connection>>(emptyMap()) }
    var events by remember { mutableStateOf<List<SocketEvent>>(emptyList()) }
    var serial by remember { mutableLongStateOf(0) }
    val clock = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    LaunchedEffect(sockets) {
        val current = sockets.associateBy { it.sessionId }
        val changes = mutableListOf<SocketEvent>()
        (current.keys - previous.keys).forEach { id ->
            changes += SocketEvent(serial++, System.currentTimeMillis(), "Connected ${current.getValue(id).peerId} [${id.toString().take(8)}]")
        }
        (previous.keys - current.keys).forEach { id ->
            changes += SocketEvent(serial++, System.currentTimeMillis(), "Disconnected ${previous.getValue(id).peerId} [${id.toString().take(8)}]")
        }
        events = (events + changes).takeLast(100)
        previous = current
    }
    Surface(modifier.widthIn(max = 440.dp).fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        tonalElevation = 8.dp, shadowElevation = 4.dp) {
        Column(Modifier.padding(8.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text("${if (expanded) "Hide" else "Show"} debug · ${sockets.size} sockets · " +
                    (memory?.let { "${displayBytes(it.javaUsed)} heap" } ?: "RAM pending"))
            }
            if (expanded) {
                memory?.let {
                    Text("Java heap: ${displayBytes(it.javaUsed)} / ${displayBytes(it.javaLimit)} limit\n" +
                        "Native heap: ${displayBytes(it.nativeUsed)}\n" +
                        "Process PSS: ${it.pss?.let(::displayBytes) ?: "unavailable"}",
                        style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    item("sockets-title") { Text("Active connections", style = MaterialTheme.typography.titleSmall) }
                    if (sockets.isEmpty()) item("no-sockets") { Text("No TCP sessions") }
                    items(sockets, key = { "socket:${it.sessionId}" }) { socket ->
                        SelectionContainer {
                            Text("${socket.peerId}\nSession ${socket.sessionId.toString().take(8)} · " +
                                "cluster ${socket.clusterId.toString().take(8)}" + if (socket.adjacent) " · adjacent" else "",
                                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    item("events-title") { Text("Connection changes · latest first", style = MaterialTheme.typography.titleSmall) }
                    items(events.asReversed(), key = { "event:${it.id}" }) { event ->
                        Text("${clock.format(Date(event.time))} ${event.message}",
                            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
