package com.example.mesh

import android.widget.Toast
import android.content.Intent
import android.content.ActivityNotFoundException
import android.provider.Settings
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
            dismissButton = { TextButton(onClick = { controller.decide(request.id, false) }) { Text("Reject / अस्वीकार करें") } },
            confirmButton = { Button(onClick = { controller.decide(request.id, true) }) { Text("Accept / स्वीकारें") } }
        )
    }
}

/** The visible screen owns radio/consent lifetime. Background requests fail closed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyDevicesScreen(identity: String, remoteControls: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val engine = remember(identity) { MeshEngine(context.applicationContext, identity) }
    val nearby by engine.nearby.collectAsStateWithLifecycle()
    val connections by engine.connections.collectAsStateWithLifecycle()
    val status by engine.status.collectAsStateWithLifecycle()
    val resolution by engine.networkResolution.collectAsStateWithLifecycle()
    val settingsRetry = remember(engine) { NetworkSettingsRetry() }
    val chat by engine.chat.collectAsStateWithLifecycle()
    val hud by engine.transferHud.collectAsStateWithLifecycle()
    val savedFiles by engine.savedFiles.collectAsStateWithLifecycle()
    var folderOpen by remember { mutableStateOf(false) }
    var wifiEnabled by remember { mutableStateOf(false) }
    var locationEnabled by remember { mutableStateOf(false) }
    val legacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if(granted) engine.retrySaving() }
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
            engine.setForeground(resumed)
            wifiEnabled = (context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager).isWifiEnabled
            val location = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            locationEnabled = if(android.os.Build.VERSION.SDK_INT >= 28) location.isLocationEnabled else location.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) || location.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            networkGranted = WifiDirectManager.requiredPermissions().all { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        }
        val observer = LifecycleEventObserver { _, event ->
            update()
            if (event == Lifecycle.Event.ON_PAUSE) settingsRetry.paused()
            if (event == Lifecycle.Event.ON_RESUME && settingsRetry.resumed()) engine.refresh()
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: Intent?) { update() }
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, android.content.IntentFilter().apply {
            addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(android.location.LocationManager.MODE_CHANGED_ACTION)
        }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        owner.lifecycle.addObserver(observer); update(); engine.start()
        onDispose { context.unregisterReceiver(receiver); owner.lifecycle.removeObserver(observer); engine.shutdown() }
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
    val homeState = HomeUiState(
        wifiEnabled = wifiEnabled, locationEnabled = locationEnabled,
        myIdentity = identity,
        peersList = connections.map { HomePeerUi(it.peerId, it.peerId, connected = true) } +
            nearby.filter { peer -> connections.none { it.peerId == peer.identity } }
                .map { HomePeerUi(it.address, it.identity) },
        isScanning = resumed && networkGranted && connections.isEmpty() &&
            (status == "Visible to nearby devices" || status == "Searching nearby devices"),
        connectionStatus = status,
        canConnect = networkGranted && connections.isEmpty(),
        engineReady = resumed && networkGranted && (connections.isNotEmpty() || status == "Visible to nearby devices" || status == "Searching nearby devices"),
    )
    fun openChat() {
        if (selected != null) showComposer = true
        else engine.notify("Select a nearby device / पास का डिवाइस चुनें")
    }
    HomeScreen(state = homeState, listState = listState,
        onSearch = engine::refresh,
        onConnect = { id ->
            val connection = connections.firstOrNull { it.peerId == id }
            if (connection != null) { selected = connection.peerId; showComposer = true }
            else nearby.firstOrNull { it.address == id }?.let(engine::connect)
        },
        onChat = ::openChat,
        onFileDrop = {
            if (selected != null && !sending) picker.launch(arrayOf("*/*"))
            else engine.notify(if(sending) "File transfer is already running" else "Select a connected peer first")
        },
        transferContent = { hud?.let { value ->
            TransferHud(value, engine.downloads::openFile, engine.downloads::openFolder) {
                if(android.os.Build.VERSION.SDK_INT <= 28 && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    legacyStorage.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                else engine.retrySaving()
            }
        } },
        settingsContent = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                MeshPermissionMatrix(onNetworkState = { networkGranted = it })
                remoteControls()
            }
        }) {
        item("downloads-folder") {
            OutlinedButton(onClick={folderOpen=true},modifier=Modifier.fillMaxWidth()) { Text("SR-Mesh Downloads / प्राप्त फ़ाइलें (${savedFiles.size})") }
        }
        item("gateway") { ActiveGatewayCard(identity) }
        if (connections.isNotEmpty()) item("disconnect") {
            TextButton(onClick = engine::disconnect) { Text("Disconnect / कनेक्शन बंद करें") }
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
        items(chat) { message -> Surface(shape=androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color=Color(0x85182438), border=androidx.compose.foundation.BorderStroke(1.dp,MeshCyan.copy(alpha=.18f))) {
            Text(message,Modifier.fillMaxWidth().padding(14.dp),style=MaterialTheme.typography.bodyMedium)
        } }
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
        item("version") { MeshVersionTag() }
    }
    if(folderOpen) ModalBottomSheet(onDismissRequest={folderOpen=false},containerColor=Color(0xFF101C2A)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.8f),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text("SR-Mesh Folder / डाउनलोड",style=MaterialTheme.typography.titleLarge,color=MeshCyan) }
            item { Text("Internal Storage > Downloads > SR-Mesh",style=MaterialTheme.typography.bodySmall) }
            item { OutlinedButton(onClick=engine.downloads::openFolder) { Text("Open system file manager / फ़ाइल मैनेजर") } }
            if(savedFiles.isEmpty()) item { Text("No received files yet / अभी कोई फ़ाइल नहीं") }
            items(savedFiles,key={it.id}) { file -> MeshGlass {
                Text(file.name)
                Text(displayBytes(file.size),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={engine.downloads.openFile(file)}) { Text("OPEN / खोलें") }
            } }
        }
    }
    if (resolution == null) IncomingConnectionDialog(engine.consent)
    if (resumed) resolution?.let { issue ->
        ModalBottomSheet(onDismissRequest=engine::dismissNetworkResolution, containerColor=Color(0xFF101C2A)) {
            Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(issue.title,style=MaterialTheme.typography.titleLarge,color=MeshCyan)
            Text(issue.description)
            MeshContinueButton(enabled=true, label="सेटिंग्स खोलें (Open Settings)", onClick = {
                val action = when (issue) {
                    NetworkResolution.HOTSPOT -> "android.settings.TETHER_SETTINGS"
                    NetworkResolution.WIFI -> Settings.ACTION_WIFI_SETTINGS
                    NetworkResolution.LOCATION -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                }
                val opened = listOf(action, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)
                    .distinct().any { candidate ->
                        try {
                            context.startActivity(Intent(candidate))
                            true
                        } catch (_: ActivityNotFoundException) { false }
                        catch (_: SecurityException) { false }
                    }
                if (opened) {
                    settingsRetry.opened()
                    engine.dismissNetworkResolution()
                } else Toast.makeText(context, "Settings नहीं खुलीं। फ़ोन की Settings खोलें।", Toast.LENGTH_LONG).show()
            })
            TextButton(onClick=engine::dismissNetworkResolution,modifier=Modifier.fillMaxWidth()) { Text("रद्द करें (Cancel)") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequestActionButtons(onAccept: () -> Unit, onReject: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onAccept, colors = ButtonDefaults.buttonColors(containerColor = MeshGreen, contentColor = Color(0xFF06261C))) { Text("Accept / स्वीकारें") }
        Button(onClick = onReject, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF512630), contentColor = Color(0xFFFFBAC5))) { Text("Reject / अस्वीकार करें") }
    }
}
