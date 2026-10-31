// SPDX-License-Identifier: GPL-2.0-or-later
@file:Suppress("DEPRECATION")
package com.example.mesh

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

class DynamicConfigManager private constructor(
    context: Context,
    val sourceUrl: String,
    private val preferences: SharedPreferences,
    initial: RemoteConfig,
    autoRefresh: Boolean
) : Closeable {
    data class Snapshot(val published: RemoteConfig, val overrideUrl: String?, val fetchedAt: Long) {
        val effective: RemoteConfig get() = overrideUrl?.let { published.copy(serverBaseUrl = it) } ?: published
    }
    val internet = ValidatedInternetMonitor(context)
    private val root = SupervisorJob()
    private val scope = CoroutineScope(root + Dispatchers.IO)
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(Snapshot(initial, preferences.getString("baseOverride", null), preferences.getLong("fetchedAt", 0)))
    val state = mutableState.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    init {
        mutableState.value.overrideUrl?.let { RemoteConfig.requireHttps(it, base = true) }
        if (autoRefresh) scope.launch {
            internet.network.collectLatest { network ->
                if (network != null) while (isActive) { refresh(); delay(6 * 60 * 60 * 1000L) }
            }
        }
    }
    /** Failure preserves last known-good configuration and any local override. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val network = internet.network.value ?: return@withLock false
            var connection: javax.net.ssl.HttpsURLConnection? = null
            try {
                val request = InternetHttp.open(network, sourceUrl).also { connection = it }
                check(request.responseCode == 200) { "Configuration HTTP ${request.responseCode}" }
                val config = RemoteConfig.parse(InternetHttp.readLimited(request, 65_536).toString(Charsets.UTF_8))
                val now = System.currentTimeMillis()
                check(preferences.edit().putString("config", config.toJson()).putLong("fetchedAt", now).commit())
                mutableState.value = Snapshot(config, mutableState.value.overrideUrl, now)
                mutableError.value = null
                true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableError.value = "Remote configuration unavailable; using the saved configuration."; false }
            finally { connection?.disconnect() }
        }
    }
    /** Host must authenticate its device owner before calling this local administrative action. */
    suspend fun setServerOverride(value: String?) = withContext(Dispatchers.IO) {
        lock.withLock {
            val normalized = value?.trim()?.trimEnd('/')
            normalized?.let { RemoteConfig.requireHttps(it, base = true) }
            check(preferences.edit().apply {
                if (normalized == null) remove("baseOverride") else putString("baseOverride", normalized)
            }.commit())
            mutableState.value = mutableState.value.copy(overrideUrl = normalized)
        }
    }
    override fun close() { root.cancel(); internet.close() }

    companion object {
        // Default PIN: 1990. Replace this SHA-256 hex value when configuring a different PIN.
        // This is a local UI gate, not a secret that can be protected from APK inspection.
        private const val ADMIN_PIN_SHA256 = "a7be8e1fe282a37cd666e0632b17d933fa13f21addf4798fc0455bc166e2488c"
        fun verifyAdminPin(pin: String): Boolean {
            if (pin.length !in 4..12 || pin.any { it !in '0'..'9' }) return false
            val expected = ADMIN_PIN_SHA256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val actual = java.security.MessageDigest.getInstance("SHA-256").digest(pin.toByteArray(Charsets.UTF_8))
            return java.security.MessageDigest.isEqual(expected, actual)
        }
        const val PREFERENCES = "mesh_remote_control"
        internal fun preferences(context: Context): SharedPreferences {
            val key = MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(context.applicationContext, PREFERENCES, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }
        suspend fun create(context: Context, githubRawUrl: String, fallback: RemoteConfig, autoRefresh: Boolean = true): DynamicConfigManager = withContext(Dispatchers.IO) {
            val uri = RemoteConfig.requireHttps(githubRawUrl)
            require(uri.host == "raw.githubusercontent.com" && uri.query == null)
            val prefs = preferences(context)
            val cached = prefs.getString("config", null)?.let(RemoteConfig::parse) ?: fallback
            check(prefs.edit().putString("sourceUrl", githubRawUrl).putString("config", cached.toJson()).commit())
            DynamicConfigManager(context.applicationContext, githubRawUrl, prefs, cached, autoRefresh)
        }
        internal suspend fun restore(context: Context): DynamicConfigManager = withContext(Dispatchers.IO) {
            val prefs = preferences(context)
            create(context, checkNotNull(prefs.getString("sourceUrl", null)),
                RemoteConfig.parse(checkNotNull(prefs.getString("config", null))), autoRefresh = false)
        }
    }
}
