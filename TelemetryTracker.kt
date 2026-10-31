// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Pseudonymous install observations. No peer IDs, messages, SSIDs, contacts or device identifiers. */
class TelemetryTracker private constructor(
    context: Context,
    private val config: DynamicConfigManager,
    val installId: String,
    private val appVersion: String,
    initialEnabled: Boolean
) : Closeable {
    private val app = context.applicationContext
    private val database = QueueDatabase(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableEnabled = MutableStateFlow(initialEnabled)
    val enabled = mutableEnabled.asStateFlow()
    init {
        scope.launch {
            combine(config.internet.network, config.state, enabled) { network, _, allowed -> network != null && allowed }
                .collect { canSend -> if (canSend) flushPending() }
        }
    }
    suspend fun setEnabled(value: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(DynamicConfigManager.preferences(app).edit().putBoolean("telemetryEnabled", value).commit())
            mutableEnabled.value = value
            if (!value) {
                database.writableDatabase.delete("pending", null, null)
                database.writableDatabase.delete("metadata", "name = ?", arrayOf("launch"))
                WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
            }
        }
        if (value) recordLaunch()
    }
    /** Once per UTC day/version on actual app launch; background workers do NOT fabricate activity. */
    suspend fun recordLaunch() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!enabled.value) return@withLock
            val db = database.writableDatabase
            val day = LocalDate.now(ZoneOffset.UTC).toString()
            val marker = "$day|$appVersion"
            db.beginTransaction()
            try {
                val previous = db.rawQuery("SELECT value FROM metadata WHERE name='launch'", null).use { if (it.moveToFirst()) it.getString(0) else null }
                if (previous != marker) {
                    val payload = JSONObject().put("installId", installId).put("os", "ANDROID")
                        .put("osVersion", Build.VERSION.RELEASE.take(64)).put("appVersion", appVersion.take(64))
                        .put("timestamp", Instant.now().toString()).toString()
                    db.insertOrThrow("pending", null, ContentValues().apply {
                        put("payload", payload); put("created", System.currentTimeMillis())
                    })
                    db.insertWithOnConflict("metadata", null, ContentValues().apply { put("name", "launch"); put("value", marker) }, SQLiteDatabase.CONFLICT_REPLACE)
                    db.execSQL("DELETE FROM pending WHERE id NOT IN (SELECT id FROM pending ORDER BY id DESC LIMIT 32)")
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            if (hasPending()) schedule()
        }
        flushPending()
    }
    private fun hasPending(): Boolean = database.readableDatabase.rawQuery("SELECT 1 FROM pending LIMIT 1", null).use { it.moveToFirst() }
    /** true means drained/disabled; false means retry when Internet/config/server becomes available. */
    internal suspend fun flushPending(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!enabled.value) return@withLock true
            val db = database.writableDatabase
            db.delete("pending", "created < ?", arrayOf((System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90)).toString()))
            while (enabled.value) {
                if (!DynamicConfigManager.preferences(app).getBoolean("telemetryEnabled", false)) return@withLock true
                val row = db.rawQuery("SELECT id,payload FROM pending ORDER BY id LIMIT 1", null).use {
                    if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null
                } ?: return@withLock true
                val network = config.internet.network.value ?: return@withLock false
                var connection: javax.net.ssl.HttpsURLConnection? = null
                try {
                    val request = InternetHttp.open(network, config.state.value.effective.installUrl, "POST").also { connection = it }
                    val bytes = row.second.toByteArray(Charsets.UTF_8)
                    request.doOutput = true; request.setFixedLengthStreamingMode(bytes.size)
                    request.setRequestProperty("Content-Type", "application/json")
                    request.outputStream.use { it.write(bytes) }
                    when (request.responseCode) {
                        in 200..299, 400, 413, 422 -> db.delete("pending", "id = ?", arrayOf(row.first.toString()))
                        else -> return@withLock false // Includes 429, 5xx, auth errors, and redirects.
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { return@withLock false }
                finally { connection?.disconnect() }
            }
            true
        }
    }
    private fun schedule() {
        val request = OneTimeWorkRequestBuilder<RemoteTelemetryWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
    override fun close() { scope.cancel(); /* SQLite is closed asynchronously after any active HTTP/DB operation. */
        CoroutineScope(Dispatchers.IO).launch { mutex.withLock { database.close() } }
    }
    private class QueueDatabase(context: Context) : SQLiteOpenHelper(context,
        File(context.noBackupFilesDir, "mesh-telemetry.db").absolutePath, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE pending(id INTEGER PRIMARY KEY AUTOINCREMENT,payload TEXT NOT NULL,created INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported telemetry schema migration")
    }
    companion object {
        private const val WORK_NAME = "mesh-install-telemetry"
        private val identityLock = Any()
        suspend fun create(context: Context, config: DynamicConfigManager, appVersion: String,
            enabledByDefault: Boolean = false, recordLaunch: Boolean = true): TelemetryTracker = withContext(Dispatchers.IO) {
            val prefs = DynamicConfigManager.preferences(context)
            if (!prefs.contains("telemetryEnabled")) check(prefs.edit().putBoolean("telemetryEnabled", enabledByDefault).commit())
            val identity = synchronized(identityLock) {
                prefs.getString("installId", null)?.also { UUID.fromString(it) } ?: UUID.randomUUID().toString().also {
                    check(prefs.edit().putString("installId", it).commit())
                }
            }
            TelemetryTracker(context, config, identity, appVersion, prefs.getBoolean("telemetryEnabled", enabledByDefault)).also {
                if (recordLaunch) it.recordLaunch()
            }
        }
    }
}

/** Survives process death. WorkManager schedules retries; this worker never touches mesh networking. */
class RemoteTelemetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        var config: DynamicConfigManager? = null
        var tracker: TelemetryTracker? = null
        return try {
            config = DynamicConfigManager.restore(applicationContext)
            if (config.internet.network.value == null) return Result.retry()
            config.refresh()
            val info = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
            tracker = TelemetryTracker.create(applicationContext, config, info.versionName ?: "unknown", recordLaunch = false)
            if (tracker.flushPending()) Result.success() else Result.retry()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
        finally { tracker?.close(); config?.close() }
    }
}
