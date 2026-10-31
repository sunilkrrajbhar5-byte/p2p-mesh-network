// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import org.json.JSONObject
import java.net.URI

data class RemoteConfig(
    val serverBaseUrl: String,
    val latestVersionCode: Long,
    val latestVersionName: String,
    val apkDownloadUrl: String,
    val forceUpdate: Boolean,
    val releaseNotes: String,
    val telemetryEndpoint: String,
    val apkSha256: String? = null
) {
    init {
        requireHttps(serverBaseUrl, base = true)
        requireHttps(apkDownloadUrl)
        require(latestVersionCode in 1..Int.MAX_VALUE.toLong())
        require(latestVersionName.isNotBlank() && latestVersionName.length <= 64)
        require(releaseNotes.length <= 16_384)
        require(telemetryEndpoint.matches(Regex("/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")))
        require(apkSha256 == null || apkSha256.matches(Regex("[a-fA-F0-9]{64}")))
    }
    val installUrl: String get() = serverBaseUrl.trimEnd('/') + telemetryEndpoint + "/install"
    val updateKey: String get() = "$latestVersionCode|$apkDownloadUrl|$apkSha256"
    fun toJson(): String = JSONObject().put("serverBaseUrl", serverBaseUrl)
        .put("latestVersionCode", latestVersionCode).put("latestVersionName", latestVersionName)
        .put("apkDownloadUrl", apkDownloadUrl).put("forceUpdate", forceUpdate)
        .put("releaseNotes", releaseNotes).put("telemetryEndpoint", telemetryEndpoint)
        .apply { apkSha256?.let { put("apkSha256", it) } }.toString()

    companion object {
        fun parse(text: String): RemoteConfig {
            require(text.toByteArray(Charsets.UTF_8).size <= 65_536)
            val json = JSONObject(text)
            fun string(key: String): String = (json.get(key) as? String) ?: error("$key must be a string")
            val number = json.get("latestVersionCode") as? Number ?: error("Invalid version code")
            require(number.toDouble().isFinite() && number.toDouble() == number.toLong().toDouble())
            return RemoteConfig(string("serverBaseUrl").trimEnd('/'), number.toLong(), string("latestVersionName"),
                string("apkDownloadUrl"), json.get("forceUpdate") as? Boolean ?: error("Invalid forceUpdate"),
                string("releaseNotes"), string("telemetryEndpoint"),
                if (json.has("apkSha256") && !json.isNull("apkSha256")) string("apkSha256") else null)
        }
        fun requireHttps(value: String, base: Boolean = false): URI {
            require(value.length in 1..2048 && value.none { it.isISOControl() })
            val uri = URI(value)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
            require(uri.port == -1 || uri.port in 1..65535)
            require(uri.host.lowercase() !in setOf("localhost", "0.0.0.0", "127.0.0.1", "[::1]"))
            require(!uri.host.endsWith(".local", true))
            if (base) require(uri.rawQuery == null && uri.normalize().rawPath == uri.rawPath)
            return uri
        }
    }
}
