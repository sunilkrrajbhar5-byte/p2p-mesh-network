package com.example.mesh

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val MeshCyan = Color(0xFF00F2FE)
internal val MeshGreen = Color(0xFF10B981)
private val MeshBlue = Color(0xFF38BDF8)

@Composable
fun SrMeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = MeshCyan, onPrimary = Color(0xFF06222C), secondary = MeshGreen,
        background = Color(0xFF0B0F17), surface = Color(0xFF111C2C),
        surfaceVariant = Color(0xFF182638), onSurface = Color(0xFFE9F4FF),
        onSurfaceVariant = Color(0xFFABC1D5), outline = Color(0xFF36556A), error = Color(0xFFFFA0AB)),
        typography = Typography(
            headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 30.sp),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 25.sp)),
        shapes = Shapes(medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(26.dp)), content = content)
}

@Composable
internal fun MeshGlass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Surface(modifier.fillMaxWidth().shadow(12.dp, shape, ambientColor = MeshCyan.copy(alpha=.12f),
        spotColor = Color.Black).background(Brush.linearGradient(listOf(Color(0xED19283B), Color(0xE610192A))), shape),
        shape = shape, color = Color.Transparent,
        border = BorderStroke(1.dp, Brush.linearGradient(listOf(MeshCyan.copy(alpha=.36f),
            Color.White.copy(alpha=.06f), MeshBlue.copy(alpha=.18f))))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun MeshBrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.size(52.dp).semantics { contentDescription = "Connected mesh logo" }) {
            val c = center; val r = size.minDimension * .36f
            val nodes = listOf(Offset(c.x, c.y-r), Offset(c.x+r, c.y), Offset(c.x,c.y+r), Offset(c.x-r,c.y))
            nodes.indices.forEach { i -> drawLine(MeshBlue.copy(alpha=.55f), nodes[i], nodes[(i+1)%4], 2.dp.toPx()); drawLine(MeshCyan.copy(alpha=.35f), nodes[i], c, 1.dp.toPx()) }
            nodes.forEach { drawCircle(MeshCyan.copy(alpha=.12f), 7.dp.toPx(), it); drawCircle(MeshCyan, 3.dp.toPx(), it) }
            drawCircle(MeshBlue, 5.dp.toPx(), c)
        }
        Text("SR MESH", color = Color(0xFFE4FCFF), style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 2.sp,
            shadow = Shadow(MeshCyan.copy(alpha = .45f), Offset.Zero, 16f)))
    }
    Text("YOUR MESH PROFILE • बिना इंटरनेट की आज़ादी", color = MeshCyan, style = MaterialTheme.typography.labelLarge)
    Text("Connect and chat with people nearby, even without internet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun MeshIdentityCard(identity: String) {
    val context = LocalContext.current
    var qrOpen by remember(identity) { mutableStateOf(false) }
    fun share() { runCatching {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, identity)
        }, "Share mesh identity"))
    }.onFailure { Toast.makeText(context, "Sharing unavailable. Use Copy ID.", Toast.LENGTH_SHORT).show() } }
    MeshGlass {
        Text("Your Identity / आपकी पहचान", color = MaterialTheme.colorScheme.onSurfaceVariant)
        // FlowRow wraps the controls under long UTF-8 identities / large accessibility fonts.
        IdentityActions(identity, onCopy = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("SR Mesh identity", identity))
            Toast.makeText(context, "Identity copied", Toast.LENGTH_SHORT).show()
        }, onShare = { qrOpen = true })
        Text("Saved on this device • इस डिवाइस पर सुरक्षित", style = MaterialTheme.typography.bodySmall, color = MeshGreen)
    }
    if(qrOpen) {
        val bitmap by produceState<Bitmap?>(null, identity) {
            value = withContext(Dispatchers.Default) {
                val matrix = QRCodeWriter().encode(identity, BarcodeFormat.QR_CODE, 512, 512,
                    mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4))
                Bitmap.createBitmap(IntArray(512*512) { i -> if(matrix[i%512,i/512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE },512,512,Bitmap.Config.ARGB_8888)
            }
        }
        AlertDialog(onDismissRequest = {qrOpen=false}, title = {Text("Share Identity / पहचान साझा करें")},
            text = {Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                bitmap?.let { Image(it.asImageBitmap(), "Identity QR: $identity", Modifier.fillMaxWidth().aspectRatio(1f)) }
                SelectionContainer { Text(identity) }
                Text("Identity only • यह gateway link नहीं है।", style=MaterialTheme.typography.bodySmall)
            }}, confirmButton = { TextButton(onClick=::share) {Text("Share / साझा करें")} },
            dismissButton = {TextButton(onClick={qrOpen=false}) {Text("Close / बंद करें")}})
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdentityActions(identity: String, onCopy: () -> Unit, onShare: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            MeshMiniIcon("profile", MeshCyan, 44)
            SelectionContainer { Text(identity, style=MaterialTheme.typography.titleLarge, modifier=Modifier.padding(vertical=10.dp)) }
        }
        OutlinedButton(onClick=onCopy, shape=CircleShape, border=BorderStroke(1.dp, MeshCyan.copy(alpha=.45f)), contentPadding=PaddingValues(horizontal=14.dp)) { Text("Copy ID") }
        OutlinedButton(onClick=onShare, shape=CircleShape, border=BorderStroke(1.dp, MeshBlue.copy(alpha=.45f)), contentPadding=PaddingValues(horizontal=14.dp)) { Text("Share / QR") }
    }
}

@Composable
internal fun MeshRadarAnimation(active: Boolean, peers: List<HomePeerUi> = emptyList(), onPeer: (String) -> Unit = {}) {
    val phase = if(active) {
        val transition = rememberInfiniteTransition(label="Nearby radar")
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing=LinearEasing)), label="Radar sweep")
        value
    } else 0f
    val anglePaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(108, 167, 180); textAlign = android.graphics.Paint.Align.CENTER } }
    Canvas(Modifier.fillMaxWidth().height(238.dp).pointerInput(peers) {
        detectTapGestures { tap ->
            val center = Offset(size.width/2f,size.height/2f)
            val r = minOf(size.width,size.height)*.39f
            peers.take(12).forEachIndexed { i, peer ->
                val angle = (i*137.5-90)*Math.PI/180
                val point = center + Offset(cos(angle).toFloat(),sin(angle).toFloat()) * (r*(.35f+(i%3)*.22f))
                if((tap-point).getDistance() < 22.dp.toPx()) onPeer(peer.id)
            }
        }
    }.semantics {contentDescription=if(active) "Nearby peers; positions are illustrative, not measured directions" else "Radar paused"}) {
        val r = size.minDimension * .39f
        drawCircle(Brush.radialGradient(listOf(MeshCyan.copy(alpha=.12f), Color.Transparent), center, r), r)
        for (i in 1..6) drawCircle(MeshCyan.copy(alpha=if(i%2==0) .22f else .09f), r*i/6, style=Stroke(1.dp.toPx()))
        for (i in 0..7) {
            val a = i * Math.PI / 4
            drawLine(MeshBlue.copy(alpha=.10f), center, center+Offset(cos(a).toFloat()*r,sin(a).toFloat()*r))
        }
        if(active) {
            // Layered sectors create a fading sweep without shaders or bitmap allocations.
            for(i in 0..23) drawArc(MeshCyan.copy(alpha=.005f+i*.0012f), phase*360f-72+i*3, 3f, true,
                center-Offset(r,r), Size(r*2,r*2))
            val a=phase*2*Math.PI
            drawLine(MeshCyan.copy(alpha=.7f), center, center+Offset(cos(a).toFloat()*r,sin(a).toFloat()*r), 1.dp.toPx())
        }
        anglePaint.textSize = 9.sp.toPx()
        for (degree in 0 until 360 step 30) {
            val a = (degree - 90) * Math.PI / 180
            val point = center + Offset(cos(a).toFloat(), sin(a).toFloat()) * (r + 15.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText("$degree°", point.x, point.y + 3.dp.toPx(), anglePaint)
        }
        peers.take(12).forEachIndexed { i, peer ->
            val a = (i * 137.5 - 90) * Math.PI / 180
            val radius = r * (.35f + (i % 3) * .22f)
            val point = center + Offset(cos(a).toFloat(), sin(a).toFloat()) * radius
            val tint = if(peer.connected) MeshGreen else MeshCyan
            drawCircle(tint.copy(alpha=.12f), (6 + phase * 8).dp.toPx(), point)
            drawCircle(tint, 3.dp.toPx(), point)
        }
        drawCircle(MeshCyan.copy(alpha=.14f), 11.dp.toPx())
        drawCircle(MeshCyan, 4.dp.toPx())
    }
    if(peers.isNotEmpty()) Text("Tap a peer • Positions illustrative",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(peers.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        peers.take(12).forEach { peer -> AssistChip(onClick={onPeer(peer.id)}, label={Text(peer.name, fontSize=11.sp)}) }
    }
}

@Composable
internal fun MeshContinueButton(enabled: Boolean, onClick: () -> Unit, label: String = "Continue / आगे बढ़ें →") {
    val brush = Brush.horizontalGradient(listOf(Color(0xFF81F7FF), MeshCyan, Color(0xFF2589FF)))
    Button(onClick=onClick, enabled=enabled, modifier=Modifier.fillMaxWidth().heightIn(min=50.dp)
        .shadow(if(enabled) 12.dp else 0.dp, RoundedCornerShape(14.dp), spotColor=MeshCyan.copy(alpha=.3f)).clip(RoundedCornerShape(14.dp)).border(1.dp, Color.White.copy(alpha=.3f), RoundedCornerShape(14.dp)).background(if(enabled) brush else Brush.horizontalGradient(listOf(Color(0xFF263544),Color(0xFF263544)))),
        colors=ButtonDefaults.buttonColors(containerColor=Color.Transparent,disabledContainerColor=Color.Transparent,contentColor=Color(0xFF06222C)),
        contentPadding=PaddingValues(horizontal=14.dp,vertical=10.dp)) {Text(label, fontSize=15.sp, fontWeight=FontWeight.Bold, textAlign=TextAlign.Center)}
}

@Composable
internal fun MeshVersionTag() {
    val context=LocalContext.current
    val version=remember(context) {
        @Suppress("DEPRECATION")
        val info=context.packageManager.getPackageInfo(context.packageName,0)
        @Suppress("DEPRECATION")
        val code=if(Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong()
        "v${info.versionName} (Build $code) • Local P2P • BY SR SYSTEMS"
    }
    Text(version, Modifier.fillMaxWidth().padding(vertical=8.dp), color=MaterialTheme.colorScheme.onSurfaceVariant,
        style=MaterialTheme.typography.bodySmall,textAlign=TextAlign.Center)
}

/** Permissions reflect OS state; checked switches open settings to revoke, never fake a grant. */
@Composable
internal fun MeshPermissionMatrix(enabled: Boolean = true, onNetworkState: (Boolean) -> Unit = {}) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {revision++}
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer);onDispose {owner.lifecycle.removeObserver(observer)}
    }
    val network=remember {WifiDirectManager.requiredPermissions()}
    val states=remember(revision) {
        (network.toList()+listOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA,Manifest.permission.POST_NOTIFICATIONS))
            .associateWith {context.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED}
    }
    val networkGranted=network.all {states[it]==true}
    val notifications=remember(revision) {NotificationManagerCompat.from(context).areNotificationsEnabled()}
    LaunchedEffect(networkGranted) {onNetworkState(networkGranted)}
    fun settings() {runCatching {context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))}
        .onFailure {Toast.makeText(context,"Open app permissions in Android Settings",Toast.LENGTH_SHORT).show()} }
    val allowed = listOf(networkGranted, states[Manifest.permission.RECORD_AUDIO]==true, states[Manifest.permission.CAMERA]==true, notifications).count { it }
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(allowed) { expanded = allowed < 4 }
    MeshGlass {
        TextButton(onClick={expanded=!expanded}, modifier=Modifier.fillMaxWidth()) {
            MeshMiniIcon("settings", MeshGreen, 26)
            Text("System Permissions: $allowed/4 Allowed ${if(allowed==4) "✓" else "• अनुमति दें"}", Modifier.weight(1f), fontSize=13.sp)
            Text(if(expanded) "−" else "+")
        }
        AnimatedVisibility(expanded) { Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        PermissionSwitch("Nearby Devices / आस-पास के डिवाइस",networkGranted,enabled) {if(networkGranted)settings() else request.launch(network)}
        PermissionSwitch("Microphone / माइक्रोफ़ोन",states[Manifest.permission.RECORD_AUDIO]==true,enabled) {if(states[Manifest.permission.RECORD_AUDIO]==true)settings() else request.launch(arrayOf(Manifest.permission.RECORD_AUDIO))}
        PermissionSwitch("Camera / कैमरा",states[Manifest.permission.CAMERA]==true,enabled) {if(states[Manifest.permission.CAMERA]==true)settings() else request.launch(arrayOf(Manifest.permission.CAMERA))}
        PermissionSwitch("Notifications / सूचनाएँ",notifications,enabled) {
            if(Build.VERSION.SDK_INT>=33 && states[Manifest.permission.POST_NOTIFICATIONS]!=true) request.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) else settings()
        }
        TextButton(onClick=::settings,enabled=enabled) { MeshMiniIcon("settings", MeshCyan, 28); Spacer(Modifier.width(10.dp)); Text("Permission settings / सेटिंग्स") }
        } }
    }
}

@Composable
private fun PermissionSwitch(title: String, granted: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        val (icon, tint) = when {
            title.startsWith("Nearby") -> "radar" to MeshBlue
            title.startsWith("Microphone") -> "mic" to Color(0xFFC49AFF)
            title.startsWith("Camera") -> "camera" to Color(0xFFFFD36E)
            else -> "bell" to Color(0xFFFFB454)
        }
        MeshMiniIcon(icon, tint)
        Column(Modifier.weight(1f)) {
            Text(title,style=MaterialTheme.typography.bodyMedium)
            Text(if(granted) "✓ Granted / स्वीकृत" else "Not granted / अनुमति दें",color=if(granted) MeshGreen else MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall.copy(shadow=if(granted) Shadow(MeshGreen.copy(alpha=.35f), Offset.Zero, 8f) else null))
        }
        Switch(checked=granted,onCheckedChange={onToggle()},enabled=enabled,colors=SwitchDefaults.colors(checkedThumbColor=Color(0xFFD8FFFF),checkedTrackColor=Color(0xFF149D9D),checkedBorderColor=MeshCyan,uncheckedTrackColor=Color(0xFF1B293A)),modifier=Modifier.semantics {contentDescription=title})
    }
}

/** Shared paint-only decoration; radio and permission state remain owned by the screens. */
internal fun Modifier.meshBackdrop(): Modifier = background(Brush.verticalGradient(
    listOf(Color(0xFF0A0F1D), Color(0xFF0E1626))))

@Composable
internal fun MeshMiniIcon(kind: String, tint: Color, edge: Int = 36) {
    Canvas(Modifier.size(edge.dp).background(tint.copy(alpha=.10f), if(kind=="profile") CircleShape else RoundedCornerShape(11.dp))
        .border(1.dp,tint.copy(alpha=.22f),if(kind=="profile") CircleShape else RoundedCornerShape(11.dp)).padding(8.dp)) {
        val w=size.width; val h=size.height; val stroke=Stroke(1.6.dp.toPx())
        fun line(x:Float,y:Float,xx:Float,yy:Float) = drawLine(tint,Offset(w*x,h*y),Offset(w*xx,h*yy),1.6.dp.toPx(),StrokeCap.Round)
        when(kind) {
            "qr" -> {for(point in listOf(Offset(0f,0f),Offset(w*.6f,0f),Offset(0f,h*.6f))) drawRect(tint,point,Size(w*.36f,h*.36f),style=stroke);drawRect(tint,Offset(w*.6f,h*.6f),Size(w*.16f,h*.16f));drawRect(tint,Offset(w*.83f,h*.83f),Size(w*.16f,h*.16f))}
            "bolt" -> {val path=Path().apply {moveTo(w*.6f,0f);lineTo(w*.15f,h*.55f);lineTo(w*.48f,h*.55f);lineTo(w*.38f,h);lineTo(w*.87f,h*.4f);lineTo(w*.55f,h*.4f);close()};drawPath(path,tint,style=stroke)}
            "chat" -> {drawRoundRect(tint,Offset(w*.08f,h*.1f),Size(w*.84f,h*.65f),androidx.compose.ui.geometry.CornerRadius(w*.12f),style=stroke);line(.2f,.75f,.2f,.95f);line(.2f,.95f,.43f,.75f);line(.27f,.34f,.73f,.34f);line(.27f,.52f,.6f,.52f)}
            "broadcast" -> {val path=Path().apply {moveTo(w*.1f,h*.35f);lineTo(w*.75f,h*.1f);lineTo(w*.75f,h*.75f);lineTo(w*.1f,h*.55f);close()};drawPath(path,tint,style=stroke);line(.25f,.6f,.32f,.95f);line(.9f,.3f,1f,.2f);line(.9f,.6f,1f,.7f)}
            "profile" -> {drawCircle(tint,w*.19f,Offset(w*.5f,h*.28f),style=stroke); drawArc(tint,180f,180f,false,Offset(w*.12f,h*.56f),Size(w*.76f,h*.65f),style=stroke)}
            "mic" -> {drawRoundRect(tint,Offset(w*.34f,h*.06f),Size(w*.32f,h*.55f),androidx.compose.ui.geometry.CornerRadius(w*.16f),style=stroke);drawArc(tint,0f,180f,false,Offset(w*.16f,h*.28f),Size(w*.68f,h*.48f),style=stroke);line(.5f,.76f,.5f,.94f);line(.3f,.94f,.7f,.94f)}
            "camera" -> {drawRoundRect(tint,Offset(w*.06f,h*.23f),Size(w*.88f,h*.62f),androidx.compose.ui.geometry.CornerRadius(w*.1f),style=stroke);drawCircle(tint,w*.18f,center,style=stroke);line(.3f,.12f,.7f,.12f)}
            "bell" -> {drawArc(tint,180f,180f,false,Offset(w*.2f,h*.12f),Size(w*.6f,h*.6f),style=stroke);line(.2f,.42f,.13f,.78f);line(.8f,.42f,.87f,.78f);line(.13f,.78f,.87f,.78f);drawCircle(tint,w*.08f,Offset(w*.5f,h*.94f))}
            "pin" -> {drawCircle(tint,w*.29f,Offset(w*.5f,h*.33f),style=stroke);line(.25f,.5f,.5f,.94f);line(.75f,.5f,.5f,.94f);drawCircle(tint,w*.07f,Offset(w*.5f,h*.33f))}
            "wifi" -> {for(i in 1..3) drawArc(tint,220f,100f,false,Offset(w*(.5f-i*.17f),h*(.88f-i*.23f)),Size(w*i*.34f,h*i*.4f),style=stroke);drawCircle(tint,w*.07f,Offset(w*.5f,h*.9f))}
            "warning" -> {val path=Path().apply {moveTo(w*.5f,h*.05f);lineTo(w*.95f,h*.9f);lineTo(w*.05f,h*.9f);close()};drawPath(path,tint,style=stroke);line(.5f,.35f,.5f,.57f);drawCircle(tint,w*.04f,Offset(w*.5f,h*.74f))}
            "settings" -> {drawCircle(tint,w*.28f,style=stroke);drawCircle(tint,w*.09f,style=stroke);for(i in 0..7) {val a=i*Math.PI/4;drawLine(tint,center+Offset(cos(a).toFloat()*w*.3f,sin(a).toFloat()*h*.3f),center+Offset(cos(a).toFloat()*w*.44f,sin(a).toFloat()*h*.44f),2.dp.toPx())}}
            else -> {drawCircle(tint,w*.4f,style=stroke);drawCircle(tint,w*.2f,style=stroke);line(.5f,.5f,.82f,.18f)}
        }
    }
}

@Composable
internal fun MeshScanStatus(status: String) {
    val warning=status.contains("failed",ignoreCase=true) || status.contains("error",ignoreCase=true)
    val tint=if(warning) Color(0xFFFFC16E) else MeshCyan
    Row(Modifier.fillMaxWidth().background(tint.copy(alpha=.07f),RoundedCornerShape(12.dp)).padding(10.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        MeshMiniIcon(if(warning) "warning" else "radar",tint,30)
        Text(status,color=tint,style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f))
    }
}

@Composable
internal fun MeshEmptyNearby() {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text("No devices yet / अभी कोई डिवाइस नहीं मिला",style=MaterialTheme.typography.bodyMedium)
            Text("Wi-Fi और Location चालू रखें",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
        }
        MeshMiniIcon("wifi",MeshCyan,30)
        MeshMiniIcon("pin",Color(0xFFB79AFF),30)
    }
}
