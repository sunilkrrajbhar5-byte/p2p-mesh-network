// SPDX-License-Identifier: GPL-2.0-or-later
@file:Suppress("DEPRECATION")
package com.example.mesh.github

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.android.apksig.ApkVerifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class AppUpdateManager private constructor(
    context: Context, val config: DynamicConfigManager,
    val installedVersionCode: Long, val installedVersionName: String
) : Closeable {
    sealed interface Status {
        data object Idle : Status
        data class Downloading(val bytes: Long, val total: Long?) : Status
        data class Ready(val file: File, val offer: RemoteConfig) : Status
        data class Failed(val message: String) : Status
    }
    private val app = context.applicationContext
    private val downloads = app.getSystemService(DownloadManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = config.preferences
    private var job: Job? = null
    private val mutableStatus = MutableStateFlow<Status>(Status.Idle)
    val status = mutableStatus.asStateFlow()
    private val mutablePrompt = MutableStateFlow(0)
    val prompt = mutablePrompt.asStateFlow()
    fun showPrompt() { mutablePrompt.value++ }
    val offer = config.state.map { it.effective.takeIf { c -> c.latestVersionCode > installedVersionCode } }
        .stateIn(scope, SharingStarted.Eagerly, config.state.value.effective.takeIf { it.latestVersionCode > installedVersionCode })
    private val attempted = HashSet<String>()
    init {
        val saved = prefs.getString("downloadOffer", null)
        val id = prefs.getLong("downloadId", -1)
        if (saved != null && id >= 0) job = scope.launch {
            try { monitor(id, RemoteConfig.parse(saved)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failure(id) }
        }
        scope.launch {
            combine(offer, config.internet.network, status) { item, network, state -> Triple(item, network, state) }
                .collect { (item, network, state) ->
                    if (state is Status.Ready && state.offer.updateKey != item?.updateKey) mutableStatus.value = Status.Idle
                    if (item != null && item.forceUpdate && network != null && state !is Status.Ready &&
                        state !is Status.Downloading && attempted.add(item.updateKey)) download()
                }
        }
    }
    @Synchronized fun download() {
        if (job?.isActive == true) return
        val item = offer.value ?: return
        if (config.internet.network.value == null) return
        job = scope.launch {
            var id = -1L
            try {
                val old = prefs.getLong("downloadId", -1)
                if (old >= 0) downloads.remove(old)
                val external = checkNotNull(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))
                File(external, "updates").apply { check(isDirectory || mkdirs()) }
                val request = DownloadManager.Request(Uri.parse(item.apkDownloadUrl))
                    .setTitle("Mesh ${item.latestVersionName}")
                    .setDescription("Application update — Android approval is required to install")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true).setAllowedOverRoaming(false)
                    .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "updates/${UUID.randomUUID()}.apk")
                id = downloads.enqueue(request)
                check(prefs.edit().putLong("downloadId", id).putString("downloadOffer", item.toJson()).commit())
                monitor(id, item)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failure(id) }
        }
    }
    private fun failure(id: Long) {
        if (id >= 0) runCatching { downloads.remove(id) }
        prefs.edit().remove("downloadId").remove("downloadOffer").commit()
        mutableStatus.value = Status.Failed("Download or APK verification failed. Check Internet access and the release version/signing key.")
    }
    private suspend fun monitor(id: Long, selected: RemoteConfig) {
        check(selected.latestVersionCode > installedVersionCode)
        while (currentCoroutineContext().isActive) {
            check(config.state.value.effective.updateKey == selected.updateKey) { "Offer changed" }
            var complete = false
            downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                check(cursor != null && cursor.moveToFirst()) { "Download disappeared" }
                fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                val received = number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val total = number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES).takeIf { it > 0 }
                check(received <= MAX_APK_BYTES && (total == null || total <= MAX_APK_BYTES))
                when (number(DownloadManager.COLUMN_STATUS).toInt()) {
                    DownloadManager.STATUS_FAILED -> error("DownloadManager failure")
                    DownloadManager.STATUS_SUCCESSFUL -> complete = true
                    else -> mutableStatus.value = Status.Downloading(received.coerceAtLeast(0), total)
                }
            }
            if (complete) {
                val directory = File(app.cacheDir, "updates").apply { check(isDirectory || mkdirs()) }
                val file = File.createTempFile("verified-", ".apk", directory)
                try {
                    val uri = checkNotNull(downloads.getUriForDownloadedFile(id))
                    checkNotNull(app.contentResolver.openInputStream(uri)).use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024); var count = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val size = input.read(buffer); if (size < 0) break
                                count += size; check(count <= MAX_APK_BYTES)
                                output.write(buffer, 0, size)
                            }
                            output.fd.sync()
                        }
                    }
                    verify(file, selected)
                    check(config.state.value.effective.updateKey == selected.updateKey)
                    mutableStatus.value = Status.Ready(file, selected)
                } catch (e: Exception) { file.delete(); throw e }
                return // Keep the completed DownloadManager ID so process restart can recover it.
            }
            delay(2_000) // Only while an update is pending, never on mesh socket threads.
        }
    }
    @Synchronized fun cancelDownload() {
        val previous = job
        previous?.cancel()
        job = scope.launch {
            previous?.join()
            val id = prefs.getLong("downloadId", -1)
            if (id >= 0) downloads.remove(id)
            prefs.edit().remove("downloadId").remove("downloadOffer").commit()
            mutableStatus.value = Status.Idle
        }
    }
    suspend fun launchInstaller(activity: Activity): Boolean {
        val ready = status.value as? Status.Ready ?: return false
        if (!app.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")))
            return false
        }
        withContext(Dispatchers.IO) {
            check(ready.offer.updateKey == config.state.value.effective.updateKey)
            verify(ready.file, ready.offer)
        }
        withContext(Dispatchers.Main.immediate) {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", ready.file)
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("APK update", uri) })
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
    override fun close() { scope.cancel() } // DownloadManager persists; creation resumes monitoring.
    companion object {
        const val MAX_APK_BYTES = 256L * 1024 * 1024
        private fun versionCode(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        suspend fun create(context: Context, config: DynamicConfigManager, installedCode: Long? = null): AppUpdateManager = withContext(Dispatchers.IO) {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            File(context.cacheDir, "updates").listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
            AppUpdateManager(context.applicationContext, config, installedCode ?: versionCode(info), info.versionName ?: "unknown")
        }
    }
}
