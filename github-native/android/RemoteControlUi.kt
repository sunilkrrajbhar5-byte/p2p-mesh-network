// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh.github

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private data class PendingOverride(val url: String?)

/** Seven taps opens a PIN challenge; editing also retains device-owner authentication on save. */
@Composable
fun RemoteVersionControl(config: DynamicConfigManager, updates: AppUpdateManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by config.state.collectAsStateWithLifecycle()
    var taps by remember { mutableIntStateOf(0) }
    var started by remember { mutableLongStateOf(0) }
    var showAdmin by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingOverride?>(null) }
    val authentication = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val change = pending; pending = null
        if (result.resultCode == Activity.RESULT_OK && change != null) scope.launch {
            runCatching { config.setLocalOverride(change.url) }
                .onSuccess { showAdmin = false }.onFailure { error = "Could not save that configuration JSON." }
        }
    }
    fun authenticate(change: PendingOverride) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val intent = keyguard.createConfirmDeviceCredentialIntent("Mesh administration", "Confirm the device credential to change local configuration.")
        if (!keyguard.isDeviceSecure || intent == null) { error = "Set a device screen lock before changing configuration."; return }
        pending = change
        authentication.launch(intent)
    }
    Text("Version ${updates.installedVersionName} (${updates.installedVersionCode})",
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable {
            val now = SystemClock.elapsedRealtime()
            if (now - started > 5_000) { taps = 0; started = now }
            taps++
            if (taps == 7) { taps = 0; showAdmin = false; showPin = true }
        }.padding(12.dp))
    TextButton(onClick = { scope.launch { config.refresh(); updates.showPrompt() } }) { Text("Check for updates") }
    if (showPin) AdminPinDialog(
        onDismiss = { showPin = false },
        onSubmit = { pin ->
            showPin = false
            if (DynamicConfigManager.verifyAdminPin(pin)) {
                url = snapshot.effective.toJson(); error = null; showAdmin = true
            } else {
                showAdmin = false
                Toast.makeText(context, "Access Denied", Toast.LENGTH_SHORT).show()
            }
        }
    )
    if (showAdmin) AlertDialog(onDismissRequest = { if (pending == null) showAdmin = false },
        title = { Text("Local GitHub configuration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Published: ${snapshot.published.webClientUrl}", style = MaterialTheme.typography.bodySmall)
                Text(if (snapshot.override == null) "Using published configuration" else "Local override is active")
                OutlinedTextField(url, { url = it }, label = { Text("Configuration JSON") }, modifier = Modifier.heightIn(max = 260.dp), maxLines = 12)
                Text("Local overrides affect this device only. URLs must stay within your GitHub repository and Pages site.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { authenticate(PendingOverride(null)) }, enabled = pending == null) { Text("Restore published configuration") }
            }
        }, confirmButton = {
            TextButton(onClick = {
                if (runCatching { RemoteConfig.parse(url.trim()) }.isSuccess) authenticate(PendingOverride(url.trim()))
                else error = "Enter valid configuration JSON with the six documented fields."
            }, enabled = pending == null) { Text("Authenticate and save") }
        }, dismissButton = { TextButton(onClick = { showAdmin = false }, enabled = pending == null) { Text("Close") } })
}

/** Render once at the Activity's composition root, above navigation. */
@Composable
fun RemoteUpdateDialog(manager: AppUpdateManager) {
    val offer by manager.offer.collectAsStateWithLifecycle()
    val status by manager.status.collectAsStateWithLifecycle()
    val network by manager.config.internet.network.collectAsStateWithLifecycle()
    val prompt by manager.prompt.collectAsStateWithLifecycle()
    var dismissed by remember(prompt) { mutableStateOf<String?>(null) }
    var installMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val item = offer ?: return
    val mandatory = item.forceUpdate && network != null
    if (!mandatory && dismissed == item.updateKey) return
    fun dismiss() { if (!mandatory) { manager.cancelDownload(); dismissed = item.updateKey } }
    AlertDialog(onDismissRequest = ::dismiss,
        title = { Text("Update ${item.latestVersionName}") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.releaseNotes.ifBlank { "A new version is available." })
                if (mandatory) Text("This version is required. Android will ask you to approve installation.")
                if (network == null) Text("Offline. Mesh features remain available; connect to Internet to download.")
                when (val progress = status) {
                    is AppUpdateManager.Status.Downloading -> {
                        if (progress.total != null && progress.total > 0) LinearProgressIndicator(
                            progress = { (progress.bytes.toDouble() / progress.total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Downloaded ${progress.bytes / 1024} KiB")
                    }
                    is AppUpdateManager.Status.Ready -> Text("APK downloaded and signature verified.")
                    is AppUpdateManager.Status.Failed -> Text(progress.message, color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
                installMessage?.let { Text(it) }
            }
        }, confirmButton = {
            if (status is AppUpdateManager.Status.Ready) TextButton(onClick = {
                scope.launch {
                    try {
                        val activity = findActivity(context) ?: error("Activity required")
                        installMessage = if (manager.launchInstaller(activity)) "Complete installation in Android's installer."
                        else "Allow installation from this app, return here, then tap Install again."
                    } catch (_: Exception) { installMessage = "Installer could not be opened, or the APK did not pass verification." }
                }
            }) { Text("Install") }
            else TextButton(onClick = { manager.download() }, enabled = network != null && status !is AppUpdateManager.Status.Downloading) {
                Text(if (status is AppUpdateManager.Status.Failed) "Retry download" else "Update Now")
            }
        }, dismissButton = { if (!mandatory) TextButton(onClick = ::dismiss) { Text("Later") } })
}

private fun findActivity(context: Context): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> findActivity(context.baseContext)
    else -> null
}

/** PIN is masked, excluded from saved-instance state, and the dialog blocks screenshots. */
@Composable
private fun AdminPinDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    fun dismiss() { pin = ""; onDismiss() }
    AlertDialog(
        onDismissRequest = ::dismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("Admin PIN Required") },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { value -> if (value.length <= 12 && value.all { it in '0'..'9' }) pin = value },
                label = { Text("Admin PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false)
            )
        },
        confirmButton = {
            TextButton(onClick = { val submitted = pin; pin = ""; onSubmit(submitted) }) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = ::dismiss) { Text("Cancel") } }
    )
}
