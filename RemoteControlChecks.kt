package com.example.mesh

import java.io.File

object RemoteControlChecks {
    @JvmStatic fun main(args: Array<String>) {
        val text = File("distribution/config.json").readText()
        val config = RemoteConfig.parse(text)
        check(RemoteConfig.parse(config.toJson()) == config)
        check(config.installUrl == "https://distribution.example.com/api/v1/telemetry/install")
        fun rejected(block: () -> Unit) { check(runCatching(block).isFailure) }
        rejected { config.copy(serverBaseUrl = "http://example.com") }
        rejected { config.copy(serverBaseUrl = "https://localhost") }
        rejected { config.copy(serverBaseUrl = "https://example.com/path/../admin") }
        rejected { config.copy(apkDownloadUrl = "https://user:password@example.com/a.apk") }
        rejected { config.copy(telemetryEndpoint = "/../admin") }
        rejected { config.copy(apkSha256 = "bad") }
        rejected { RemoteConfig.parse(text.replace("\"forceUpdate\": false", "\"forceUpdate\": \"false\"")) }
        rejected { RemoteConfig.parse(text.replace("\"latestVersionCode\": 2", "\"latestVersionCode\": 2.5")) }
        println("Remote configuration checks passed")
    }
}
