package com.example.mesh

import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Integration-test fixture only; not app source. Uses a test certificate trusted by the Python client. */
fun main(args: Array<String>) {
    val password = "test-only-password".toCharArray()
    val store = KeyStore.getInstance("PKCS12")
    File(args[0]).inputStream().use { store.load(it, password) }
    val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
    val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
    val group = UUID.fromString("11111111-2222-4333-8444-555555555555")
    val host = DesktopMeshHost("Host#123456", group, "127.0.0.1", File(args[1]), File("WebMeshClient.html").readText(),
        "https://127.0.0.1:18080", "test-only-join-secret-01234567890123456789", tls.serverSocketFactory, 18888, 18080)
    try { host.start(); println("READY"); System.out.flush(); readlnOrNull() }
    finally { host.close() }
}
