package com.example.mesh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Presentation-only snapshot. Unknown RSSI remains null rather than a fabricated reading. */
data class HomePeerUi(val id: String, val name: String, val rssiDbm: Int? = null, val connected: Boolean = false)
data class HomeUiState(
    val peersList: List<HomePeerUi> = emptyList(),
    val isScanning: Boolean = false,
    val myIdentity: String,
    val connectionStatus: String,
    val canConnect: Boolean = false,
    val engineReady: Boolean = false,
    val signalFidelity: String = "Not reported",
    val relayMode: String = "Direct peer",
)

private val HomeEmerald = Color(0xFF00FF87)
private val HomeAmber = Color(0xFFFFB300)

/** No engine, permissions, receivers, or sockets are created by this screen. */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onConnect: (String) -> Unit,
    onSearch: () -> Unit,
    onChat: () -> Unit,
    onFileDrop: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    settingsContent: @Composable () -> Unit = {},
    sessionContent: LazyListScope.() -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf("Radar") }
    var identityOpen by rememberSaveable { mutableStateOf(false) }
    var logsOpen by rememberSaveable { mutableStateOf(false) }
    fun notice(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    Scaffold(modifier = modifier.fillMaxSize().background(Brush.radialGradient(
        listOf(Color(0xFF060B14), Color(0xFF020408)), radius = 1500f)),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(Modifier.navigationBarsPadding().padding(horizontal=16.dp, vertical=10.dp),
                color=Color(0xF0121E30), shape=RoundedCornerShape(30.dp),
                border=BorderStroke(1.dp,MeshCyan.copy(alpha=.22f)), shadowElevation=16.dp) {
                Row(Modifier.fillMaxWidth().padding(6.dp)) {
                    listOf("Radar" to "खोज", "Chats" to "संदेश", "Relay" to "रिले नोड", "Settings" to "सेटिंग्स").forEach { (name, hindi) ->
                        TextButton(onClick={tab=name; if(name=="Chats") onChat(); scope.launch { listState.scrollToItem(0) }},
                            modifier=Modifier.weight(1f), contentPadding=PaddingValues(vertical=10.dp),
                            colors=ButtonDefaults.textButtonColors(contentColor=if(tab==name) MeshCyan else Color(0xFF9CAFC6))) {
                            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                Text(name,fontSize=11.sp,fontWeight=FontWeight.Bold)
                                Text(hindi,fontSize=10.sp,textAlign=TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }) { padding ->
        LazyColumn(state=listState, modifier=Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding=PaddingValues(18.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item("home-header") {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            MeshMiniIcon("radar",MeshCyan,38)
                            Text("SR MESH",fontSize=25.sp,fontWeight=FontWeight.ExtraBold,letterSpacing=2.sp,
                                color=Color(0xFFE1FCFF),style=MaterialTheme.typography.titleLarge.copy(shadow=Shadow(MeshCyan,blurRadius=12f)))
                        }
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            HomePulse(state.engineReady,HomeEmerald)
                            Text(if(state.engineReady) "Mesh Engine: Ready" else "Mesh Engine: Check status",fontSize=11.sp,
                                color=if(state.engineReady) HomeEmerald else HomeAmber)
                        }
                    }
                    IconButton(onClick={identityOpen=true},modifier=Modifier.semantics {contentDescription="Identity QR and share"}) { MeshMiniIcon("qr",MeshCyan,32) }
                    IconButton(onClick={logsOpen=true},modifier=Modifier.semantics {contentDescription="Live engine status"}) { MeshMiniIcon("bell",HomeAmber,32) }
                }
            }
            item("home-identity") {
                HomeGlass {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        MeshMiniIcon("profile",MeshCyan,52)
                        Column(Modifier.weight(1f)) {
                            Text("YOUR NODE / आपकी पहचान",color=MeshCyan,fontSize=10.sp,letterSpacing=1.sp)
                            Text(state.myIdentity,fontWeight=FontWeight.Bold,fontSize=20.sp)
                            Text("Local identity • On this device",color=Color(0xFF93A9C1),fontSize=11.sp)
                        }
                        TextButton(onClick={
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("SR Mesh identity",state.myIdentity))
                            notice("Identity copied")
                        }) { Text("Copy",fontSize=12.sp) }
                    }
                    HorizontalDivider(color=MeshCyan.copy(alpha=.12f))
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        HomeMetric("Nearby Peers",state.peersList.size.toString(),Modifier.weight(1f))
                        VerticalDivider(color=MeshCyan.copy(alpha=.15f))
                        HomeMetric("Signal Fidelity",state.signalFidelity,Modifier.weight(1f))
                        VerticalDivider(color=MeshCyan.copy(alpha=.15f))
                        HomeMetric("Relay Mode",state.relayMode,Modifier.weight(1f))
                    }
                }
            }
            item("home-tools") {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    HomeTool("Broadcast", "प्रसारण", "broadcast",HomeAmber,Modifier.weight(1f)) { notice("Broadcast is unavailable in this direct-peer session. Select a peer to chat.") }
                    HomeTool("Mesh Chat", "चैट", "chat",MeshCyan,Modifier.weight(1f)) { tab="Chats"; onChat() }
                    HomeTool("File Drop", "फ़ाइल शेयर", "bolt",HomeEmerald,Modifier.weight(1f),onFileDrop)
                }
            }
            item("home-status") { MeshScanStatus(state.connectionStatus) }
            if(tab=="Radar") {
                item("home-radar") {
                    HomeGlass {
                        Text("Discovered Devices / आस-पास के नोड्स",fontWeight=FontWeight.SemiBold)
                        MeshRadarAnimation(state.isScanning)
                        if(state.peersList.isEmpty()) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                HomePulse(state.isScanning,MeshCyan)
                                Text(if(state.isScanning) "Scanning local Wi-Fi direct airspace..." else "Discovery paused • Check connection status",fontSize=12.sp,color=Color(0xFFABC1D5))
                            }
                        }
                        FilledTonalButton(onClick=onSearch,enabled=state.canConnect) { Text("Search / खोजें") }
                    }
                }
                items(state.peersList,key={"home-peer:${it.id}"}) { peer ->
                    HomeGlass {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            MeshMiniIcon("wifi",if(peer.connected) HomeEmerald else MeshCyan)
                            Column(Modifier.weight(1f)) {
                                Text(peer.name,fontWeight=FontWeight.SemiBold)
                                Text(peer.rssiDbm?.let { "$it dBm" } ?: "Signal not reported",fontSize=11.sp,color=Color(0xFF93A9C1))
                            }
                            OutlinedButton(onClick={onConnect(peer.id); if(peer.connected) tab="Chats"},enabled=state.canConnect || peer.connected,
                                border=BorderStroke(1.dp,if(peer.connected) HomeEmerald else MeshCyan),shape=CircleShape) {
                                HomePulse(state.isScanning && state.canConnect,MeshCyan)
                                Text(if(peer.connected) "Chat" else "Connect",fontSize=12.sp)
                            }
                        }
                    }
                }
            }
            if(tab=="Relay") item("home-relay") { HomeGlass {
                Text("Relay / रिले नोड",color=HomeEmerald,fontWeight=FontWeight.Bold)
                Text("Mode: ${state.relayMode}")
                Text("This dashboard owns direct peer sessions. Background bridge control is not connected here.",color=Color(0xFFABC1D5))
            } }
            if(tab=="Settings") item("home-settings") { settingsContent() }
            sessionContent()
        }
    }
    if(identityOpen) AlertDialog(onDismissRequest={identityOpen=false},title={Text("Node identity / पहचान")},
        text={MeshIdentityCard(state.myIdentity)},confirmButton={TextButton(onClick={identityOpen=false}) { Text("Close") }})
    if(logsOpen) AlertDialog(onDismissRequest={logsOpen=false},title={Text("Live engine status")},
        text={Text(state.connectionStatus)},confirmButton={TextButton(onClick={logsOpen=false}) { Text("Close") }})
}

@Composable private fun HomeGlass(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(),color=Color(0x85182438),shape=RoundedCornerShape(24.dp),
        border=BorderStroke(1.dp,Color(0x50304B6E)),shadowElevation=8.dp) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
    }
}
@Composable private fun HomeMetric(title:String,value:String,modifier:Modifier) {
    Column(modifier.padding(horizontal=5.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Text(value,color=HomeEmerald,fontWeight=FontWeight.SemiBold,fontSize=12.sp,textAlign=TextAlign.Center)
        Text(title,color=Color(0xFF93A9C1),fontSize=10.sp,textAlign=TextAlign.Center)
    }
}
@Composable private fun HomeTool(title:String,hindi:String,icon:String,tint:Color,modifier:Modifier,onClick:()->Unit) {
    Surface(onClick=onClick,modifier=modifier,shape=RoundedCornerShape(20.dp),color=tint.copy(alpha=.07f),border=BorderStroke(1.dp,tint.copy(alpha=.25f))) {
        Column(Modifier.padding(horizontal=4.dp,vertical=16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
            MeshMiniIcon(icon,tint)
            Text(title,fontSize=12.sp,fontWeight=FontWeight.Bold,textAlign=TextAlign.Center)
            Text(hindi,color=tint,fontSize=11.sp,textAlign=TextAlign.Center)
        }
    }
}
@Composable private fun HomePulse(active:Boolean,tint:Color) {
    val alpha=if(active) {
        val transition=rememberInfiniteTransition(label="Engine pulse")
        val value by transition.animateFloat(.35f,1f,infiniteRepeatable(tween(1100),RepeatMode.Reverse),label="Pulse opacity")
        value
    } else .3f
    Canvas(Modifier.size(14.dp).alpha(alpha)) {drawCircle(tint.copy(alpha=.15f));drawCircle(tint,radius=size.minDimension*.22f)}
}

@Preview(name="Home – compact",widthDp=360,heightDp=800,showBackground=true)
@Preview(name="Home – large text",widthDp=412,heightDp=915,fontScale=1.5f,showBackground=true)
@Composable private fun HomeScreenPreview() {
    SrMeshTheme { HomeScreen(HomeUiState(myIdentity="Sunil#056535",connectionStatus="Visible to nearby devices",engineReady=true,
        peersList=listOf(HomePeerUi("preview-peer","Amit#094821",-62)),canConnect=true),{}, {}, {}, {}) }
}
@Preview(name="Home – empty",widthDp=360,heightDp=800)
@Composable private fun HomeEmptyPreview() {
    SrMeshTheme { HomeScreen(HomeUiState(myIdentity="Sunil#056535",connectionStatus="Starting nearby discovery…",isScanning=true),{}, {}, {}, {}) }
}
