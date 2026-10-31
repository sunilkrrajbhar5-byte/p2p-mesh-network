// SPDX-License-Identifier: GPL-2.0-or-later
@file:Suppress("DEPRECATION")
package com.example.mesh

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.android.apksig.ApkVerifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class AppUpdateManager private constructor(
    context: Context,
    val config: DynamicConfigManager,
    val installedVersionCode: Long,
    val installedVersionName: String
) : Closeable {
    sealed interface Status {
        data object Idle : Status
        data class Downloading(val bytes: Long, val total: Long?) : Status
        data class Ready(val file: File, val offer: RemoteConfig) : Status
        data class Failed(val message: String) : Status
    }
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var downloadJob: Job? = null
    private val attemptedForced = HashSet<String>()
    private val mutableStatus = MutableStateFlow<Status>(Status.Idle)
    val status = mutableStatus.asStateFlow()
    private val mutablePrompt = MutableStateFlow(0)
    val prompt = mutablePrompt.asStateFlow()
    fun showPrompt() { mutablePrompt.value++ }
    val offer: StateFlow<RemoteConfig?> = config.state.map { it.effective.takeIf { c -> c.latestVersionCode > installedVersionCode } }
        .stateIn(scope, SharingStarted.Eagerly, config.state.value.effective.takeIf { it.latestVersionCode > installedVersionCode })
    init {
        scope.launch {
            combine(offer, config.internet.network, status) { item, network, state -> Triple(item, network, state) }.collect { (item, network, state) ->
                if (state is Status.Ready && state.offer.updateKey != item?.updateKey) {
                    state.file.delete(); mutableStatus.value = Status.Idle
                }
                if (item != null && item.forceUpdate && network != null && state !is Status.Ready && state !is Status.Downloading && attemptedForced.add(item.updateKey)) download()
            }
        }
    }
    /** May be called from UI; forced offers invoke it automatically when validated Internet exists. */
    @Synchronized fun download() {
        if (downloadJob?.isActive == true) return
        val selected = offer.value ?: return
        downloadJob = scope.launch {
            mutex.withLock {
                var temporary: File? = null
                var connection: javax.net.ssl.HttpsURLConnection? = null
                try {
                    val network = config.internet.network.value ?: error("Internet is unavailable")
                    val directory = File(app.cacheDir, "updates").apply { check(isDirectory || mkdirs()) }
                    val file = File.createTempFile("download-", ".part", directory).also { temporary = it }
                    val request = InternetHttp.get(network, selected.apkDownloadUrl).also { connection = it }
                    check(request.responseCode == 200) { "Download HTTP ${request.responseCode}" }
                    val total = request.contentLengthLong.takeIf { it >= 0 }
                    require(total == null || total in 1..MAX_APK_BYTES)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var received = 0L
                    var lastPublished = 0L
                    mutableStatus.value = Status.Downloading(0, total)
                    FileOutputStream(file).use { output ->
                        request.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                require(received <= MAX_APK_BYTES)
                                output.write(buffer, 0, count); digest.update(buffer, 0, count)
                                if (received - lastPublished >= 256 * 1024) {
                                    mutableStatus.value = Status.Downloading(received, total); lastPublished = received
                                }
                            }
                        }
                        output.fd.sync()
                    }
                    require(received > 0 && (total == null || received == total)) { "Truncated APK" }
                    selected.apkSha256?.let { check(hex(digest.digest()).equals(it, true)) { "APK checksum mismatch" } }
                    verify(file, selected)
                    currentCoroutineContext().ensureActive()
                    check(config.state.value.effective.updateKey == selected.updateKey) { "Update offer changed during download" }
                    val ready = File(directory, "update-${selected.latestVersionCode}.apk")
                    check(!ready.exists() || ready.delete())
                    check(file.renameTo(ready))
                    temporary = null
                    mutableStatus.value = Status.Ready(ready, selected)
                } catch (e: CancellationException) { mutableStatus.value = Status.Idle; throw e }
                catch (_: Exception) { mutableStatus.value = Status.Failed("Update could not be downloaded or verified. Check Internet access and the published APK.") }
                finally { connection?.disconnect(); temporary?.delete() }
            }
        }
    }
    fun cancelDownload() { downloadJob?.cancel() }

    /** Must be invoked from a visible Activity/user action. Never claims installation succeeded. */
    suspend fun launchInstaller(activity: Activity): Boolean {
        val ready = status.value as? Status.Ready ?: return false
        if (!app.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")))
            return false // User returns and presses Install again after granting permission.
        }
        withContext(Dispatchers.IO) {
            check(ready.offer.updateKey == config.state.value.effective.updateKey)
            verify(ready.file, ready.offer) // Recheck the exact private-cache file before granting read access.
        }
        withContext(Dispatchers.Main.immediate) {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", ready.file)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri("Application update", uri) }
            activity.startActivity(intent)
        }
        return true
    }
    private fun verify(file: File, selected: RemoteConfig) {
        require(file.length() in 1..MAX_APK_BYTES)
        val result = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(Build.VERSION.SDK_INT)
            .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT).build().verify()
        check(result.isVerified) { "APK signature verification failed" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val local = app.packageManager.getPackageInfo(app.packageName, flags)
        val installedSigners = if (Build.VERSION.SDK_INT >= 28) checkNotNull(local.signingInfo).apkContentsSigners else local.signatures
        val expected = checkNotNull(installedSigners).map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
        val actual = result.signerCertificates.map { hex(MessageDigest.getInstance("SHA-256").digest(it.encoded)) }.toSet()
        check(actual.isNotEmpty() && actual == expected) { "Update uses a different signing certificate" }
        val candidate = checkNotNull(app.packageManager.getPackageArchiveInfo(file.absolutePath, 0))
        check(candidate.packageName == app.packageName && versionCode(candidate) == selected.latestVersionCode && versionCode(candidate) > installedVersionCode)
    }
    override fun close() { scope.cancel() }
    companion object {
        const val MAX_APK_BYTES = 256L * 1024 * 1024
        internal fun versionCode(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        /** PackageManager reports the installed BuildConfig.VERSION_CODE without a generated-class dependency. */
        suspend fun create(context: Context, config: DynamicConfigManager, installedCode: Long? = null): AppUpdateManager = withContext(Dispatchers.IO) {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            // A process killed during download can leave a partial file. Keep recent APKs for an open installer.
            File(context.cacheDir, "updates").listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".part") ||
                    System.currentTimeMillis() - file.lastModified() > 86_400_000)) file.delete()
            }
            AppUpdateManager(context, config, installedCode ?: versionCode(info), info.versionName ?: versionCode(info).toString())
        }
    }
}
