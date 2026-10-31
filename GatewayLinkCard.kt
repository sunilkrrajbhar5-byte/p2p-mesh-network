package com.example.mesh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ActiveGatewayCard(identity: String) {
    val gateways by GatewayLinkState.active.collectAsStateWithLifecycle()
    gateways.filter { it.hostId == identity }.forEach { gateway ->
        key(gateway.url) { GatewayLinkCard(gateway.url) }
    }
}

@Composable
fun GatewayLinkCard(url: String) {
    val context = LocalContext.current
    var showQr by remember(url) { mutableStateOf(false) }
    fun copy() {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("SR Mesh gateway", url))
        Toast.makeText(context, "Gateway URL copied", Toast.LENGTH_SHORT).show()
    }
    fun share() {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
                putExtra(Intent.EXTRA_SUBJECT, "SR Mesh gateway")
            }
            context.startActivity(Intent.createChooser(intent, "Share gateway link"))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "No sharing app available. Use Copy Link.", Toast.LENGTH_LONG).show()
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Active HTTPS gateway", style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(url) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = ::copy, modifier = Modifier.weight(1f)) { Text("Copy Link") }
                Button(onClick = { showQr = true }, modifier = Modifier.weight(1f)) { Text("Share / QR") }
            }
        }
    }
    if (showQr) {
        val pixels by produceState<IntArray?>(null, url) {
            value = withContext(Dispatchers.Default) { runCatching { GatewayQr.pixels(url) }.getOrNull() }
        }
        val bitmap = remember(pixels) { pixels?.let { Bitmap.createBitmap(it, 512, 512, Bitmap.Config.ARGB_8888).asImageBitmap() } }
        AlertDialog(onDismissRequest = { showQr = false }, title = { Text("Share gateway / Scan QR") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (bitmap != null) Image(bitmap, "QR code containing $url", Modifier.fillMaxWidth().aspectRatio(1f))
                else Text("Preparing QR. You can also share the link below.")
                SelectionContainer { Text(url) }
                Text("Join the host Wi-Fi first. The gateway HTTPS certificate must be trusted by the receiving device.")
            } },
            confirmButton = { Button(onClick = ::share) { Text("Share Link") } },
            dismissButton = { TextButton(onClick = { showQr = false }) { Text("Close") } })
    }
}
