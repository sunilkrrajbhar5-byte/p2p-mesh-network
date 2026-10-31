// SPDX-License-Identifier: GPL-2.0-or-later
@file:Suppress("DEPRECATION")
package com.example.mesh.github

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
    internal val preferences: SharedPreferences,
    initial: Snapshot
) : Closeable {
    data class Snapshot(val published: RemoteConfig, val override: RemoteConfig?, val fetchedAt: Long) {
        val effective get() = override ?: published
    }
    val internet = ValidatedInternetMonitor(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(initial)
    val state = mutableState.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    init {
        scope.launch { internet.network.collectLatest { network ->
            if (network != null) while (isActive) { refresh(); delay(6 * 60 * 60 * 1000L) }
        } }
    }
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        val network = internet.network.value ?: return@withLock false
        var connection: javax.net.ssl.HttpsURLConnection? = null
        try {
            val request = InternetHttp.open(network, RemoteConfig.SOURCE).also { connection = it }
            check(request.responseCode == 200)
            val fetched = RemoteConfig.parse(InternetHttp.readLimited(request, 65_536).toString(Charsets.UTF_8))
            val now = System.currentTimeMillis()
            check(preferences.edit().putString("published", fetched.toJson()).putLong("fetchedAt", now).commit())
            mutableState.value = Snapshot(fetched, state.value.override, now)
            mutableError.value = null
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableError.value = "GitHub unavailable; using cached configuration."; false }
        finally { connection?.disconnect() }
    } }
    /** Only call after local device-owner authentication. Null removes the local override. */
    suspend fun setLocalOverride(json: String?) = withContext(Dispatchers.IO) { mutex.withLock {
        val replacement = json?.let(RemoteConfig::parse)
        check(preferences.edit().apply {
            if (replacement == null) remove("override") else putString("override", replacement.toJson())
        }.commit())
        mutableState.value = state.value.copy(override = replacement)
    } }
    override fun close() { scope.cancel(); internet.close() }
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
        const val PREFERENCES = "mesh_github_config"
        suspend fun create(context: Context, fallback: RemoteConfig = RemoteConfig.DEFAULT): DynamicConfigManager = withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(app, PREFERENCES, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            DynamicConfigManager(app, prefs, Snapshot(
                prefs.getString("published", null)?.let(RemoteConfig::parse) ?: fallback,
                prefs.getString("override", null)?.let(RemoteConfig::parse), prefs.getLong("fetchedAt", 0)))
        }
    }
}
