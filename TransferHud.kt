package com.example.mesh

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransferHud(state: TransferHudState, onOpenFile: (SavedMeshFile) -> Unit, onOpenFolder: () -> Unit, onRetry: () -> Unit) {
    val fraction by animateFloatAsState(state.fraction, label="File progress")
    MeshGlass {
        Text("P2P TRANSFER / फ़ाइल ट्रांसफ़र",color=MeshCyan,fontSize=11.sp)
        Text(state.name,fontWeight=FontWeight.Bold)
        Text("${displayBytes(state.bytes)} / ${displayBytes(state.total)} • ${state.peer}",fontSize=12.sp)
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF233245))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f,1f)).fillMaxHeight().background(Brush.horizontalGradient(listOf(MeshGreen,MeshCyan,Color(0xFF4FACFE)))))
        }
        val done = state.saved != null || state.phase == "Delivered • SHA-256 verified"
        Text(if(done) "✓ 100% Completed" else "${(state.fraction * 100).toInt()}% • ${state.phase}",color=if(state.failed) MaterialTheme.colorScheme.error else MeshGreen)
        if(state.bytesPerSecond > 0 && !done && !state.failed) Text(
            "${String.format(Locale.ROOT,"%.1f",state.bytesPerSecond/1048576)} MB/s" +
                (state.secondsRemaining?.let { " • ${it/60}:${(it%60).toString().padStart(2,'0')} remaining" } ?: " • Final verification"), fontSize=12.sp)
        if(state.saved != null) {
            Text("Saved in Internal Storage > Downloads > SR-Mesh",fontSize=12.sp)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={onOpenFile(state.saved)}) { Text("OPEN FILE / फ़ाइल खोलें",fontSize=12.sp) }
                OutlinedButton(onClick=onOpenFolder) { Text("OPEN FOLDER / फ़ोल्डर खोलें",fontSize=12.sp) }
            }
        }
        if(state.retrySaveAvailable) TextButton(onClick=onRetry) { Text("Retry save / दोबारा सेव करें") }
    }
}
