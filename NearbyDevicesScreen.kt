package com.example.mesh

import android.widget.Toast
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import java.io.File

@Composable
fun IncomingConnectionDialog(controller: PeerConsentController) {
    val pending by controller.pending.collectAsStateWithLifecycle()
    val request = pending.firstOrNull() ?: return
    key(request.id) {
        AlertDialog(
            onDismissRequest = { controller.decide(request.id, false) },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text("Incoming Connection Request") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${request.peerId} wants to connect with you.")
                when (request.kind) {
                    PeerConsentController.Kind.FILE -> Text("File: ${request.fileName}\nSize: ${displayBytes(request.fileBytes ?: 0)}")
                    PeerConsentController.Kind.VOICE -> Text("Start a live voice session?")
                    PeerConsentController.Kind.CONNECTION -> Text("Allow a direct connection on this local network?")
                }
            } },
            dismissButton = { TextButton(onClick = { controller.decide(request.id, false) }) { Text("Reject") } },
            confirmButton = { Button(onClick = { controller.decide(request.id, true) }) { Text("Accept") } }
        )
    }
}

/** The visible screen owns radio/consent lifetime. Background requests fail closed. */
@Composable
fun NearbyDevicesScreen(identity: String, remoteControls: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val engine = remember(identity) { MeshEngine(context.applicationContext, identity) }
    val nearby by engine.nearby.collectAsStateWithLifecycle()
    val connections by engine.connections.collectAsStateWithLifecycle()
    val status by engine.status.collectAsStateWithLifecycle()
    val chat by engine.chat.collectAsStateWithLifecycle()
    val transfer by engine.transfer.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(engine, owner) {
        fun update() {
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            engine.consent.setUiVisible(resumed)
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        owner.lifecycle.addObserver(observer); update(); engine.start()
        onDispose { owner.lifecycle.removeObserver(observer); engine.shutdown() }
    }
    LaunchedEffect(engine) { engine.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
    LaunchedEffect(connections, resumed) {
        if (selected !in connections.map { it.peerId }) selected = connections.firstOrNull()?.peerId
        if (resumed && connections.isNotEmpty()) engine.createVoice(owner)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val peer = selected
        if (uri != null && peer != null) scope.launch {
            sending = true
            var temporary: File? = null
            var displayName = "Shared file"
            try {
                val file = withContext(Dispatchers.IO) {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) displayName = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: displayName
                    }
                    // Sender-side cache only. Remote bytes cannot create files before acceptance.
                    File.createTempFile("mesh-upload-", ".bin", context.cacheDir).also { target ->
                        temporary = target
                        context.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input)
                            target.outputStream().use { output ->
                                val buffer = ByteArray(512 * 1024)
                                var total = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer); if (n < 0) break
                                    total += n; require(total <= 8L * 1024 * 1024 * 1024) { "File exceeds 8 GiB" }
                                    output.write(buffer, 0, n)
                                }
                            }
                        }
                    }
                }
                engine.sendFile(file, peer, displayName)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(context, if (e.message == "Destination rejected transfer") "Request Declined" else e.message ?: "Transfer failed", Toast.LENGTH_SHORT).show() }
            finally { withContext(NonCancellable + Dispatchers.IO) { temporary?.delete() }; sending = false }
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Nearby Devices", style = MaterialTheme.typography.headlineMedium)
        Text(identity)
        remoteControls()
        ActiveGatewayCard(identity)
        Text(status, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = engine::refresh) { Text("Search nearby") }
            if (connections.isNotEmpty()) TextButton(onClick = engine::disconnect) { Text("Disconnect") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (nearby.isEmpty() && connections.isEmpty()) item { Text("No nearby devices yet. Keep Wi-Fi and Location enabled on both devices.") }
            items(connections, key = { it.sessionId }) { connection ->
                Card(onClick = { selected = connection.peerId }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) { Text(connection.peerId); Text(if (selected == connection.peerId) "Selected · Connected" else "Connected") }
                }
            }
            items(nearby.filter { peer -> connections.none { it.peerId == peer.identity } }, key = { it.address }) { peer ->
                Card(onClick = { engine.connect(peer) }, enabled = connections.isEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) { Text(peer.identity); Text("Tap to request connection") }
                }
            }
            items(chat) { Text(it) }
        }
        selected?.let { peer ->
            OutlinedTextField(text, { text = it.take(8000) }, label = { Text("Message to $peer") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { engine.sendText(peer, text); text = "" }, enabled = text.isNotBlank()) { Text("Send") }
                Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !sending) { Text(if (sending) "Sending…" else "Send file") }
            }
            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().pointerInput(engine, peer) {
                detectTapGestures(onPress = {
                    try { engine.voice?.startTalking(peer); tryAwaitRelease() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { engine.notify(e.message ?: "Voice unavailable") }
                    finally { engine.voice?.stopTalking() }
                })
            }) { Text("Hold to request voice / talk", Modifier.padding(20.dp)) }
        }
        transfer?.let { value ->
            LinearProgressIndicator(progress = { value.fraction }, modifier = Modifier.fillMaxWidth())
            Text("${value.name}: ${displayBytes(value.sentBytes)} · ${value.phase}")
        }
    }
    IncomingConnectionDialog(engine.consent)
}
