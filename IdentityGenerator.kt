@file:Suppress("DEPRECATION")

package com.example.mesh

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.SecureRandom

/**
 * Android API 23+. Call on a worker thread: Keystore access and commit perform I/O.
 * One application process owns this store; SharedPreferences is not multiprocess-safe.
 * Exclude mesh_identity.xml from cloud backup AND device-to-device transfer.
 * A nickname plus six random digits is a display identifier, not a cryptographic
 * identity or a guarantee of network-wide uniqueness.
 */
class IdentityGenerator(context: Context) {
    private val appContext = context.applicationContext

    /** Load without creating a display identity. Call on a worker thread. */
    fun getExistingIdentity(): String? = synchronized(LOCK) {
        preferences().getString(IDENTITY_KEY, null)?.also {
            check(MeshPacket.isValidIdentity(it)) { "Stored identity is invalid" }
        }
    }

    private fun preferences(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(appContext, PREFERENCES_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    /**
     * Returns the persisted identity, regardless of a later nickname argument.
     * nickname is only used on first creation. Storage/key errors propagate; they
     * must never cause silent identity rotation or a plaintext storage fallback.
     */
    fun getOrCreateIdentity(nickname: String): String = synchronized(LOCK) {
        val preferences = preferences()
        val existing = preferences.getString(IDENTITY_KEY, null)
        if (existing != null) {
            check(MeshPacket.isValidIdentity(existing)) { "Stored identity is invalid" }
            return@synchronized existing
        }
        require(MeshPacket.isValidNickname(nickname)) {
            "Nickname must be nonblank, trimmed, at most 64 UTF-8 bytes, without # or control characters"
        }
        val suffix = RANDOM.nextInt(1_000_000).toString().padStart(6, '0')
        val identity = "$nickname#$suffix"
        if (!preferences.edit().putString(IDENTITY_KEY, identity).commit()) {
            // commit() can update memory even when disk persistence fails. Remove
            // that tentative value so another call cannot return it as persisted.
            preferences.edit().remove(IDENTITY_KEY).commit()
            throw IOException("Could not persist mesh identity")
        }
        identity
    }

    companion object {
        const val PREFERENCES_NAME = "mesh_identity"
        private const val IDENTITY_KEY = "identity_v1"
        private val LOCK = Any()
        private val RANDOM = SecureRandom()
    }
}
