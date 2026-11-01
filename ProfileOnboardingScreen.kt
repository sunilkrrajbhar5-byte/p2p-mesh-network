// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    val generator = remember(app) { IdentityGenerator(app) }
    val scope = rememberCoroutineScope()
    var nickname by rememberSaveable { mutableStateOf("") }
    var identity by rememberSaveable(initialIdentity) { mutableStateOf(initialIdentity) }
    var busy by remember { mutableStateOf(initialIdentity == null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(generator) {
        if (identity == null) {
            busy = true
            try { identity = withContext(Dispatchers.IO) { generator.getExistingIdentity() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Could not load your saved profile. Please try again." }
            finally { busy = false }
        } else busy = false
    }
    var canNetwork by remember { mutableStateOf(false) }
    val cleanName = nickname.trim()

    Scaffold(modifier=modifier.fillMaxSize().meshBackdrop(),containerColor=androidx.compose.ui.graphics.Color.Transparent,
        bottomBar={ Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal=18.dp,vertical=8.dp)) {
        MeshContinueButton(onClick = {
            val id = identity ?: return@MeshContinueButton
            busy = true; error = null
            scope.launch {
                try { onContinue(id) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "Could not open nearby devices. Please try again." }
                finally { busy = false }
            }
        }, enabled = identity != null && canNetwork && !busy,
            label = if (busy) "Please wait / कृपया प्रतीक्षा करें…" else "Continue / आगे बढ़ें →")
        } }) { insets ->
    Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding().verticalScroll(rememberScrollState())
        .padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MeshBrandHeader()
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
            MeshIdentityCard(checkNotNull(identity))
        }
        MeshPermissionMatrix(enabled = !busy, onNetworkState = { canNetwork = it })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        remoteControls?.invoke()
        MeshVersionTag()
    }
    }
}
