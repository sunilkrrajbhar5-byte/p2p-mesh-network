import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val published = JsonSlurper().parse(rootProject.file("config.json")) as Map<*, *>
val generatedSources = layout.buildDirectory.dir("generated/meshSources")
val copyMeshSources by tasks.registering(Sync::class) {
    from(rootProject.projectDir) {
        include("*.kt")
        exclude("*Checks.kt", "GatewayTestHost.kt", "DesktopMeshHost.kt")
        // The GitHub-only app deliberately excludes the older external-server implementation.
        exclude("AppUpdateManager.kt", "DynamicConfigManager.kt", "RemoteConfig.kt", "RemoteInternet.kt", "RemoteControlUi.kt", "TelemetryTracker.kt")
    }
    from(rootProject.file("github-native/android")) { include("*.kt") }
    from(rootProject.file("vendor")) { include("NanoWSD.java") }
    into(generatedSources)
}
val copyMeshAssets by tasks.registering(Sync::class) {
    from(rootProject.projectDir) { include("config.json", "WebMeshClient.html") }
    into(layout.buildDirectory.dir("generated/meshAssets"))
}
android {
    namespace = "com.example.mesh"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sunilkrrajbhar5.p2pmesh"
        minSdk = 26
        targetSdk = 35
        versionCode = (published["latestVersionCode"] as Number).toInt()
        versionName = published["latestVersionName"] as String
    }
    signingConfigs {
        getByName("debug") {
            System.getenv("MESH_KEYSTORE_PATH")?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("MESH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MESH_KEY_ALIAS")
                keyPassword = System.getenv("MESH_KEY_PASSWORD")
            }
        }
    }
    buildTypes.getByName("release") {
        // Reuse the stable installed-app signer for upgrade compatibility.
        signingConfig = signingConfigs.getByName("debug")
        isDebuggable = false
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
    sourceSets.getByName("main") {
        java.srcDir(generatedSources)
        assets.srcDir(layout.buildDirectory.dir("generated/meshAssets"))
        res.srcDir(rootProject.file("github-native/res"))
    }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST") }
}
tasks.named("preBuild") { dependsOn(copyMeshSources, copyMeshAssets) }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.android.tools.build:apksig:8.7.3")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-video:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3") // Offline QR generation; no scanner service/CDN.
}
