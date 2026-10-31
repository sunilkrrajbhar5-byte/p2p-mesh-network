// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/** Messages are chronological; host owns message/transfer state and network jobs. */
@Composable
fun MainChatScreen(
    conversationId: String,
    title: String,
    messages: List<ChatMessageUi>,
    transfers: List<FileTransferUi>,
    connected: Boolean,
    pttEnabled: Boolean,
    onSendText: suspend (String) -> Unit,
    onFileSelected: (Uri) -> Unit,
    onPttStart: () -> Unit,
    onPttStop: () -> Unit,
    modifier: Modifier = Modifier,
    onCancelTransfer: ((UUID) -> Unit)? = null,
    onVideoNote: (() -> Unit)? = null
) {
    var draft by rememberSaveable(conversationId) { mutableStateOf("") }
    var sending by remember(conversationId) { mutableStateOf(false) }
    var error by remember(conversationId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onFileSelected)
    }
    LaunchedEffect(conversationId) { list.scrollToItem(0) }
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty() && list.firstVisibleItemIndex <= 1) list.animateScrollToItem(0)
    }
    Column(modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
        Text(if (connected) "Connected to mesh" else "Waiting for a route",
            style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, reverseLayout = true,
            contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (messages.isEmpty()) item("empty") { Text("Start a conversation with your mesh.") }
            items(messages.asReversed(), key = { it.id }) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement =
                    if (message.outgoing) Arrangement.End else Arrangement.Start) {
                    Surface(shape = MaterialTheme.shapes.medium,
                        color = if (message.outgoing) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.widthIn(max = 340.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!message.outgoing) Text(message.sender, style = MaterialTheme.typography.labelMedium)
                            SelectionContainer { Text(message.text) }
                            val status = when (message.delivery) {
                                ChatDelivery.PENDING -> "Sending"
                                ChatDelivery.SENT -> "✓ Sent"
                                ChatDelivery.DELIVERED -> "✓✓ Delivered"
                                ChatDelivery.FAILED -> "Failed"
                            }
                            Text(timeFormat.format(Date(message.timestampMillis)) +
                                if (message.outgoing) " · $status" else "",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        // Bound the composer so transfers/large fonts cannot push PTT below the IME.
        Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            if (transfers.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(transfers, key = { it.id.toString() }) { transfer ->
                        FileTransferCard(transfer, onCancelTransfer)
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("Message") }, maxLines = 4, enabled = !sending)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    runCatching { picker.launch(arrayOf("*/*")) }
                        .onFailure { error = "No document picker is available." }
                }, enabled = connected) { Text("Attach file") }
                if (onVideoNote != null) TextButton(onClick = onVideoNote, enabled = connected) { Text("Video note") }
                Button(onClick = {
                    val text = draft.trim()
                    sending = true; error = null
                    scope.launch {
                        try { onSendText(text); draft = "" }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "Message could not be sent. Your draft is retained." }
                        finally { sending = false }
                    }
                }, enabled = connected && draft.isNotBlank() && !sending) { Text(if (sending) "Sending…" else "Send") }
            }
            HoldToTalkButton(conversationId, connected && pttEnabled, onPttStart, onPttStop,
                Modifier.fillMaxWidth().padding(bottom = 12.dp))
        }
    }
}

@Composable
private fun FileTransferCard(transfer: FileTransferUi, cancel: ((UUID) -> Unit)?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(transfer.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            val status = when (transfer.phase) {
                FileTransferPhase.PREPARING -> "Preparing file"
                FileTransferPhase.SENDING -> "Sending"
                FileTransferPhase.VERIFYING -> "Waiting for verification"
                FileTransferPhase.DELIVERED -> "✓✓ Delivered and verified"
                FileTransferPhase.FAILED -> "Transfer failed"
                FileTransferPhase.CANCELLED -> "Transfer cancelled"
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (transfer.phase == FileTransferPhase.PREPARING || transfer.phase == FileTransferPhase.VERIFYING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { transfer.fraction }, modifier = Modifier.fillMaxWidth())
            }
            Text("${displayBytes(transfer.sentBytes)} / ${transfer.totalBytes?.let(::displayBytes) ?: "size pending"}",
                style = MaterialTheme.typography.labelSmall)
            if (cancel != null && transfer.active) TextButton(onClick = { cancel(transfer.id) }) { Text("Cancel") }
        }
    }
}

/** Gesture cancellation, navigation, focus loss and lifecycle pause all release PTT. */
@Composable
private fun HoldToTalkButton(
    conversationId: String,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val owner = LocalLifecycleOwner.current
    val latestStart by rememberUpdatedState(onStart)
    val latestStop by rememberUpdatedState(onStop)
    var held by remember { mutableStateOf(false) }
    var stopThisPress by remember { mutableStateOf<(() -> Unit)?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val stop = remember {
        {
            val release = stopThisPress
            stopThisPress = null; held = false
            if (release != null) runCatching { release() }.onFailure { error = "Could not release voice session." }
            Unit
        }
    }
    val start = {
        if (enabled && !held && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            error = null; held = true; stopThisPress = latestStop
            try { latestStart() }
            catch (_: Exception) { stop(); error = "Voice unavailable. Check microphone permission and connection." }
        }
    }
    LaunchedEffect(enabled) { if (!enabled) stop() }
    DisposableEffect(owner, conversationId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { stop(); owner.lifecycle.removeObserver(observer) }
    }
    Column(modifier) {
        Surface(color = if (held) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .semantics {
                    role = Role.Button
                    stateDescription = if (held) "Transmitting" else "Not transmitting"
                    if (!enabled) disabled()
                    else onClick(label = if (held) "Stop talking" else "Start talking; activate again to stop") {
                        if (held) stop() else start()
                        true
                    }
                }
                .onFocusChanged { if (!it.isFocused) stop() }
                .onKeyEvent { event ->
                    if (enabled && event.key in listOf(Key.Spacebar, Key.Enter, Key.DirectionCenter)) {
                        if (event.type == KeyEventType.KeyDown) start() else if (event.type == KeyEventType.KeyUp) stop()
                        true
                    } else false
                }.focusable(enabled)
                .pointerInput(enabled, conversationId) {
                    if (enabled) detectTapGestures(onPress = {
                        start()
                        try { tryAwaitRelease() } finally { stop() }
                    })
                }) {
            Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                Text(if (!enabled) "Voice unavailable" else if (held) "Talking… release to stop" else "Hold to talk",
                    style = MaterialTheme.typography.titleMedium)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
