// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** PIN-free profile creation. Admission is handled by explicit peer consent. */
@Composable
fun ProfileOnboardingScreen(
    onContinue: suspend (identity: String) -> Unit,
    modifier: Modifier = Modifier,
    initialIdentity: String? = null,
    remoteControls: (@Composable () -> Unit)? = null
) {
    require(initialIdentity == null || MeshPacket.isValidIdentity(initialIdentity))
    val context = LocalContext.current
    val app = context.applicationContext
    val owner = LocalLifecycleOwner.current
    val generator = remember(app) { IdentityGenerator(app) }
    val scope = rememberCoroutineScope()
    var nickname by rememberSaveable { mutableStateOf("") }
    var identity by rememberSaveable(initialIdentity) { mutableStateOf(initialIdentity) }
    var busy by remember { mutableStateOf(initialIdentity == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(generator) {
        if (identity == null) {
            busy = true
            try { identity = withContext(Dispatchers.IO) { generator.getExistingIdentity() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Could not load your saved profile. Please try again." }
            finally { busy = false }
        } else busy = false
    }
    val networkPermissions = remember {
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRevision++
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val granted = remember(permissionRevision) {
        (networkPermissions.toList() + listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
            .associateWith { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
    val canNetwork = networkPermissions.all { granted[it] == true }
    val cleanName = nickname.trim()

    Column(modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
        .padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Your mesh profile", style = MaterialTheme.typography.headlineMedium)
        Text("Connect and chat with people nearby, even without internet.")
        remoteControls?.invoke()
        if (identity == null) {
            OutlinedTextField(nickname, { nickname = it }, label = { Text("Nickname") },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                isError = nickname.isNotEmpty() && !MeshPacket.isValidNickname(cleanName),
                supportingText = { Text("Up to 64 UTF-8 bytes. No # or control characters.") })
            Button(onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        identity = withContext(Dispatchers.IO) { generator.getOrCreateIdentity(cleanName) }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Could not save your profile. Please try again." }
                    finally { busy = false }
                }
            }, enabled = !busy && MeshPacket.isValidNickname(cleanName)) {
                Text(if (busy) "Saving…" else "Create profile")
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Your identity", style = MaterialTheme.typography.labelMedium)
                    Text(checkNotNull(identity), style = MaterialTheme.typography.titleLarge)
                    Text("Saved on this device", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        HorizontalDivider()
        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        PermissionRow(if (Build.VERSION.SDK_INT >= 33) "Nearby devices" else "Nearby discovery location",
            "Required to discover and connect", canNetwork, !busy) { launcher.launch(networkPermissions) }
        PermissionRow("Microphone", "Optional · hold-to-talk and video audio",
            granted[Manifest.permission.RECORD_AUDIO] == true, !busy) {
            launcher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
        PermissionRow("Camera", "Optional · video notes", granted[Manifest.permission.CAMERA] == true, !busy) {
            launcher.launch(arrayOf(Manifest.permission.CAMERA))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            PermissionRow("Notifications", "Optional · connection status",
                granted[Manifest.permission.POST_NOTIFICATIONS] == true, !busy) {
                launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        TextButton(onClick = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")))
            }.onFailure { error = "Open this app's permissions in Android Settings." }
        }, enabled = !busy) { Text("Open permission settings") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val id = identity ?: return@Button
            busy = true; error = null
            scope.launch {
                try { onContinue(id) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "Could not open nearby devices. Please try again." }
                finally { busy = false }
            }
        }, enabled = identity != null && canNetwork && !busy,
            modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Please wait…" else "Continue") }
    }
}

@Composable
private fun PermissionRow(title: String, detail: String, granted: Boolean, enabled: Boolean, request: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(detail, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = request, enabled = enabled && !granted) {
            Text(if (granted) "Granted" else "Allow")
        }
    }
}
