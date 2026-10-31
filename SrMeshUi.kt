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
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
internal val MeshGreen = Color(0xFF42E6A4)
private val MeshBlue = Color(0xFF38BDF8)

@Composable
fun SrMeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = MeshCyan, onPrimary = Color(0xFF06222C), secondary = MeshGreen,
        background = Color(0xFF0B0F19), surface = Color(0xFF111C2C),
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
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        color = Color(0xD9111C2C), border = BorderStroke(1.dp, MeshCyan.copy(alpha = .22f))) {
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
        Text("SR MESH", style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 2.sp,
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
        SelectionContainer { Text(identity, style=MaterialTheme.typography.titleLarge, modifier=Modifier.padding(vertical=10.dp)) }
        OutlinedButton(onClick=onCopy, contentPadding=PaddingValues(horizontal=12.dp)) { Text("Copy ID") }
        OutlinedButton(onClick=onShare, contentPadding=PaddingValues(horizontal=12.dp)) { Text("Share / QR") }
    }
}

@Composable
internal fun MeshRadarAnimation(active: Boolean) {
    val phase = if(active) {
        val transition = rememberInfiniteTransition(label="Nearby radar")
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing=LinearEasing)), label="Radar sweep")
        value
    } else 0f
    Canvas(Modifier.fillMaxWidth().height(148.dp).semantics {contentDescription=if(active) "Nearby discovery radar" else "Radar paused"}) {
        val r=size.height*.43f
        for(fraction in listOf(.35f,.65f,1f)) drawCircle(MeshCyan.copy(alpha=.16f),r*fraction,center,style=Stroke(1.dp.toPx()))
        drawLine(MeshBlue.copy(alpha=.1f),Offset(center.x-r,center.y),Offset(center.x+r,center.y))
        drawLine(MeshBlue.copy(alpha=.1f),Offset(center.x,center.y-r),Offset(center.x,center.y+r))
        drawArc(MeshCyan.copy(alpha=.1f), phase*360f, 48f, true, Offset(center.x-r,center.y-r), Size(r*2,r*2))
        drawCircle(MeshCyan.copy(alpha=(1-phase)*.3f),r*phase,center,style=Stroke(2.dp.toPx()))
        drawCircle(MeshCyan,4.dp.toPx(),center)
    }
}

@Composable
internal fun MeshContinueButton(enabled: Boolean, onClick: () -> Unit, label: String = "Continue / आगे बढ़ें ➔") {
    val brush = Brush.horizontalGradient(listOf(MeshCyan, MeshBlue))
    Button(onClick=onClick, enabled=enabled, modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)
        .clip(RoundedCornerShape(16.dp)).background(if(enabled) brush else Brush.horizontalGradient(listOf(Color(0xFF263544),Color(0xFF263544)))),
        colors=ButtonDefaults.buttonColors(containerColor=Color.Transparent,disabledContainerColor=Color.Transparent,contentColor=Color(0xFF06222C)),
        contentPadding=PaddingValues(16.dp)) {Text(label, fontWeight=FontWeight.Bold, textAlign=TextAlign.Center)}
}

@Composable
internal fun MeshVersionTag() {
    val context=LocalContext.current
    val version=remember(context) {
        @Suppress("DEPRECATION")
        val info=context.packageManager.getPackageInfo(context.packageName,0)
        @Suppress("DEPRECATION")
        val code=if(Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong()
        "v${info.versionName} ($code) • SR Systems"
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
    MeshGlass {
        Text("Permissions / अनुमतियाँ", style=MaterialTheme.typography.titleMedium)
        PermissionSwitch("Nearby Devices / आस-पास के डिवाइस",networkGranted,enabled) {if(networkGranted)settings() else request.launch(network)}
        PermissionSwitch("Microphone / माइक्रोफ़ोन",states[Manifest.permission.RECORD_AUDIO]==true,enabled) {if(states[Manifest.permission.RECORD_AUDIO]==true)settings() else request.launch(arrayOf(Manifest.permission.RECORD_AUDIO))}
        PermissionSwitch("Camera / कैमरा",states[Manifest.permission.CAMERA]==true,enabled) {if(states[Manifest.permission.CAMERA]==true)settings() else request.launch(arrayOf(Manifest.permission.CAMERA))}
        PermissionSwitch("Notifications / सूचनाएँ",notifications,enabled) {
            if(Build.VERSION.SDK_INT>=33 && states[Manifest.permission.POST_NOTIFICATIONS]!=true) request.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) else settings()
        }
        TextButton(onClick=::settings,enabled=enabled) {Text("Permission settings / सेटिंग्स")}
    }
}

@Composable
private fun PermissionSwitch(title: String, granted: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title,style=MaterialTheme.typography.bodyMedium)
            Text(if(granted) "✓ Granted / स्वीकृत" else "Not granted / अनुमति दें",color=if(granted) MeshGreen else MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
        }
        Switch(checked=granted,onCheckedChange={onToggle()},enabled=enabled,modifier=Modifier.semantics {contentDescription=title})
    }
}
