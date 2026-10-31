package com.example.mesh
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
fun main() {
    for(url in listOf("https://192.168.43.1:8443/", "https://mesh.example:8443/", "https://[fd00::1]:8443/")) {
        val pixels = GatewayQr.pixels(url)
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(512, 512, pixels))))
        check(result.text == url)
    }
    for(url in listOf("http://host/", "https://user:pass@host/", "https://host/path", "https://host/#secret"))
        check(runCatching { GatewayQr.pixels(url) }.isFailure)
    println("Gateway QR round-trip and invalid URL checks passed")
}
