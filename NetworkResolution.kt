package com.example.mesh

import java.io.IOException

/** Actionable radio prerequisites, separate from socket/protocol failures. */
enum class NetworkResolution(val title: String, val description: String, val status: String) {
    HOTSPOT(
        "फ़ोन का हॉटस्पॉट बंद करें (Turn off Hotspot)",
        "मेश नेटवर्क शुरू करने के लिए आपके फ़ोन का पर्सनल हॉटस्पॉट बंद होना ज़रूरी है। कृपया इसे बंद करें।",
        "Turn off personal hotspot / फ़ोन का हॉटस्पॉट बंद करें"
    ),
    WIFI(
        "Wi-Fi चालू करें (Turn on Wi-Fi)",
        "आस-पास के डिवाइस खोजने के लिए Wi-Fi चालू करें। इंटरनेट की ज़रूरत नहीं है।",
        "Turn on Wi-Fi / Wi-Fi चालू करें"
    ),
    LOCATION(
        "लोकेशन चालू करें (Turn on Location)",
        "Wi-Fi discovery के लिए फ़ोन की Location setting चालू करें, फिर ऐप में वापस आएँ।",
        "Turn on Location / लोकेशन चालू करें"
    )
}

class NetworkResolutionException(val resolution: NetworkResolution) : IOException(resolution.status)

/** Arm only after opening Settings; ignore initial/resumed recompositions. */
class NetworkSettingsRetry {
    private var awaitingReturn = false
    private var leftScreen = false
    fun opened() { awaitingReturn = true; leftScreen = false }
    fun paused() { if (awaitingReturn) leftScreen = true }
    fun resumed(): Boolean {
        if (!awaitingReturn || !leftScreen) return false
        awaitingReturn = false; leftScreen = false
        return true
    }
}
