// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh.github

import org.json.JSONObject
import java.net.URI

data class RemoteConfig(
    val latestVersionCode: Long,
    val latestVersionName: String,
    val apkDownloadUrl: String,
    val webClientUrl: String,
    val forceUpdate: Boolean,
    val releaseNotes: String
) {
    init {
        require(latestVersionCode in 1..Int.MAX_VALUE.toLong())
        require(latestVersionName.isNotBlank() && latestVersionName.length <= 64)
        require(releaseNotes.length <= 16_384)
        val apk = requireHttps(apkDownloadUrl)
        require(apk.host == "github.com" && apk.rawPath.startsWith("/sunilkrrajbhar5-byte/p2p-mesh-network/releases/") && apk.query == null)
        val web = requireHttps(webClientUrl)
        require(web.host == "sunilkrrajbhar5-byte.github.io" && web.rawPath.startsWith("/p2p-mesh-network/") && web.query == null)
    }
    val updateKey get() = "$latestVersionCode|$apkDownloadUrl"
    fun toJson(): String = JSONObject().put("latestVersionCode", latestVersionCode)
        .put("latestVersionName", latestVersionName).put("apkDownloadUrl", apkDownloadUrl)
        .put("webClientUrl", webClientUrl).put("forceUpdate", forceUpdate).put("releaseNotes", releaseNotes).toString(2)
    companion object {
        const val SOURCE = "https://raw.githubusercontent.com/sunilkrrajbhar5-byte/p2p-mesh-network/main/config.json"
        val DEFAULT = RemoteConfig(2, "1.0.1",
            "https://github.com/sunilkrrajbhar5-byte/p2p-mesh-network/releases/latest/download/SR-Mesh.apk",
            "https://sunilkrrajbhar5-byte.github.io/p2p-mesh-network/", false, "Initial universal P2P mesh release")
        fun parse(text: String): RemoteConfig {
            require(text.toByteArray().size <= 65_536)
            val json = JSONObject(text)
            val keys = setOf("latestVersionCode", "latestVersionName", "apkDownloadUrl", "webClientUrl", "forceUpdate", "releaseNotes")
            require(json.keys().asSequence().toSet() == keys)
            fun string(key: String) = json.get(key) as? String ?: error("$key must be a string")
            val code = json.get("latestVersionCode") as? Number ?: error("Invalid version")
            require(code.toDouble().isFinite() && code.toDouble() == code.toLong().toDouble())
            return RemoteConfig(code.toLong(), string("latestVersionName"), string("apkDownloadUrl"), string("webClientUrl"),
                json.get("forceUpdate") as? Boolean ?: error("forceUpdate must be boolean"), string("releaseNotes"))
        }
        fun requireHttps(value: String, base: Boolean = false): URI {
            require(value.length in 1..2048 && value.none { it.isISOControl() })
            return URI(value).also {
                require(it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null)
                require(it.port == -1 || it.port == 443)
                require(it.rawPath == it.normalize().rawPath && !it.rawPath.contains('%'))
                if (base) require(it.query == null)
            }
        }
    }
}
