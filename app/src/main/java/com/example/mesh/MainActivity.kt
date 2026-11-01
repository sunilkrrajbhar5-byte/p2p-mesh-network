package com.example.mesh

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mesh.github.AppUpdateManager
import com.example.mesh.github.DynamicConfigManager
import com.example.mesh.github.RemoteConfig
import com.example.mesh.github.RemoteVersionControl
import com.example.mesh.github.RemoteUpdateDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

data class GitHubControls(val config: DynamicConfigManager, val updates: AppUpdateManager)
class MeshApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val controls = MutableStateFlow<GitHubControls?>(null)
    val error = MutableStateFlow<String?>(null)
    private var initialization: Job? = null
    override fun onCreate() { super.onCreate(); initialize() }
    @Synchronized fun initialize() {
        if (initialization?.isActive == true || controls.value != null) return
        initialization = scope.launch {
            var manager: DynamicConfigManager? = null
            try {
                error.value = null
                val fallback = assets.open("config.json").bufferedReader().use { RemoteConfig.parse(it.readText()) }
                manager = DynamicConfigManager.create(this@MeshApplication, fallback)
                val updates = AppUpdateManager.create(this@MeshApplication, manager, BuildConfig.VERSION_CODE.toLong())
                controls.value = GitHubControls(manager, updates)
            } catch (e: CancellationException) { manager?.close(); throw e }
            catch (_: Exception) { manager?.close(); error.value = "Remote settings could not be opened. Check device storage and retry." }
        }
    }
}

/** PIN-free onboarding, nearby discovery and per-peer consent. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MeshApplication
        setContent {
            val controls by app.controls.collectAsStateWithLifecycle()
            val error by app.error.collectAsStateWithLifecycle()
            var savedProfile by remember { mutableStateOf<String?>(null) }
            SrMeshTheme {
                Surface(Modifier.fillMaxSize()) {
                    val remote = controls
                    if (remote == null) Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                        Text(error ?: "Opening local configuration…")
                        if (error != null) Button(onClick = app::initialize) { Text("Retry") }
                    } else {
                        val config by remote.config.state.collectAsStateWithLifecycle()
                        val controlsUi: @Composable () -> Unit = {
                            RemoteVersionControl(remote.config, remote.updates)
                            OutlinedButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(config.effective.webClientUrl))) }, modifier=Modifier.fillMaxWidth(), shape=androidx.compose.foundation.shape.RoundedCornerShape(50)) {
                                Text("Web Gateway / Downloads • वेब गेटवे")
                            }
                        }
                        val profile = savedProfile
                        if (profile == null) ProfileOnboardingScreen(onContinue = { savedProfile = it }, remoteControls = controlsUi)
                        else NearbyDevicesScreen(profile, controlsUi)
                        RemoteUpdateDialog(remote.updates)
                    }
                }
            }
        }
    }
}
