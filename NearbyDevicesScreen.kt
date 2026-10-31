package com.example.mesh

import android.widget.Toast
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.Color
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
    val request = pending.firstOrNull { it.kind != PeerConsentController.Kind.CONNECTION } ?: return
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
            dismissButton = { TextButton(onClick = { controller.decide(request.id, false) }) { Text("Reject / अस्वीकार करें") } },
            confirmButton = { Button(onClick = { controller.decide(request.id, true) }) { Text("Accept / स्वीकारें") } }
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
    var networkGranted by remember { mutableStateOf(WifiDirectManager.requiredPermissions().all { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) }
    val pending by engine.consent.pending.collectAsStateWithLifecycle()
    var showComposer by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(engine, owner) {
        fun update() {
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            engine.consent.setUiVisible(resumed)
            networkGranted = WifiDirectManager.requiredPermissions().all { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
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
    LaunchedEffect(pending.firstOrNull { it.kind == PeerConsentController.Kind.CONNECTION }?.id) {
        if (pending.any { it.kind == PeerConsentController.Kind.CONNECTION }) listState.animateScrollToItem(4)
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
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
        contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("brand") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { MeshBrandHeader() } }
        item("identity") { MeshIdentityCard(identity) }
        item("gateway") { ActiveGatewayCard(identity) }
        item("radar") {
            MeshGlass {
                Text("Nearby Devices / आस-पास के लोग", style = MaterialTheme.typography.titleLarge)
                MeshRadarAnimation(active = resumed && networkGranted && connections.isEmpty())
                Text("● Active: $identity (You)", color = MeshGreen)
                Text(status, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = engine::refresh, enabled = networkGranted) { Text("Search / खोजें") }
                    if (connections.isNotEmpty()) TextButton(onClick = engine::disconnect) { Text("Disconnect") }
                }
                if (nearby.isEmpty() && connections.isEmpty()) Text("No devices yet / अभी कोई डिवाइस नहीं मिला। Wi-Fi और Location चालू रखें।")
            }
        }
        items(pending.filter { it.kind == PeerConsentController.Kind.CONNECTION }, key = { "request:${it.id}" }) { request ->
            MeshGlass {
                Text("Join Request / जुड़ने का अनुरोध", color = MeshCyan, style = MaterialTheme.typography.labelLarge)
                Text(request.peerId, style = MaterialTheme.typography.titleLarge)
                Text("${request.peerId} wants to connect with you.")
                RequestActionButtons(onAccept = { engine.consent.decide(request.id, true) },
                    onReject = { engine.consent.decide(request.id, false) })
            }
        }
        items(connections, key = { "connected:${it.sessionId}" }) { connection ->
            Card(onClick = { selected = connection.peerId; showComposer = true }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) { Text(connection.peerId, style = MaterialTheme.typography.titleMedium); Text(if(selected == connection.peerId) "✓ Selected · Connected / जुड़े हैं" else "Connected / जुड़े हैं", color = MeshGreen) }
            }
        }
        items(nearby.filter { peer -> connections.none { it.peerId == peer.identity } }, key = { "nearby:${it.address}" }) { peer ->
            Card(onClick = { engine.connect(peer) }, enabled = networkGranted && connections.isEmpty(), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) { Text(peer.identity, style = MaterialTheme.typography.titleMedium); Text("Tap to connect / जुड़ने के लिए टैप करें", color = MeshCyan) }
            }
        }
        item("permissions") { MeshPermissionMatrix(onNetworkState = { networkGranted = it }) }
        item("continue") { MeshContinueButton(enabled = networkGranted, onClick = {
            if (selected != null) { showComposer = true; scope.launch { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 3).coerceAtLeast(0)) } }
            else { engine.refresh(); engine.notify("Select a nearby device / पास का डिवाइस चुनें") }
        }) }
        item("controls") { remoteControls() }
        items(chat) { Text(it) }
        item("composer") {
        if (showComposer) selected?.let { peer ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            }
        }
        item("transfer") { transfer?.let { value ->
            LinearProgressIndicator(progress = { value.fraction }, modifier = Modifier.fillMaxWidth())
            Text("${value.name}: ${displayBytes(value.sentBytes)} · ${value.phase}")
        }
        }
        item("version") { MeshVersionTag() }
    }
    IncomingConnectionDialog(engine.consent)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequestActionButtons(onAccept: () -> Unit, onReject: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onAccept, colors = ButtonDefaults.buttonColors(containerColor = MeshGreen, contentColor = Color(0xFF06261C))) { Text("Accept / स्वीकारें") }
        Button(onClick = onReject, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF512630), contentColor = Color(0xFFFFBAC5))) { Text("Reject / अस्वीकार करें") }
    }
}
