package com.example.mesh.github

import java.io.File

object GitHubConfigChecks {
    @JvmStatic fun main(args: Array<String>) {
        val value = RemoteConfig.parse(File("config.json").readText())
        check(value == RemoteConfig.DEFAULT)
        check(RemoteConfig.parse(value.toJson()) == value)
        fun rejects(action: () -> Unit) { check(runCatching(action).isFailure) }
        rejects { value.copy(apkDownloadUrl = "http://github.com/app.apk") }
        rejects { value.copy(apkDownloadUrl = "https://github.com/other/repository/releases/app.apk") }
        rejects { value.copy(apkDownloadUrl = "https://github.com/sunilkrrajbhar5-byte/p2p-mesh-network/releases/../other.apk") }
        rejects { value.copy(webClientUrl = "https://external.example/") }
        rejects { value.copy(latestVersionCode = 0) }
        rejects { RemoteConfig.parse(value.toJson().replace("\"forceUpdate\": false", "\"forceUpdate\": \"false\"")) }
        rejects { RemoteConfig.parse(value.toJson().replace("\"latestVersionCode\": 1", "\"latestVersionCode\": 1.5")) }
        rejects { RemoteConfig.parse(value.toJson().replace("\"releaseNotes\":", "\"unexpected\":")) }
        println("GitHub configuration checks passed")
    }
}
