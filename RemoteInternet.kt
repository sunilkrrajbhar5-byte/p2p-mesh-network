// SPDX-License-Identifier: GPL-2.0-or-later
package com.example.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.Inet6Address
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

/** Never changes process routing or touches the local mesh's sockets or callbacks. */
class ValidatedInternetMonitor(context: Context) : Closeable {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val candidates = ConcurrentHashMap<Network, NetworkCapabilities>()
    private val mutableNetwork = MutableStateFlow<Network?>(null)
    val network = mutableNetwork.asStateFlow()
    @Volatile private var closed = false
    private fun publish() {
        if (closed) return
        val valid = candidates.filterValues { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
        mutableNetwork.value = manager.activeNetwork?.takeIf { it in valid } ?: valid.keys.firstOrNull()
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { synchronized(candidates) { candidates[network] = capabilities; publish() } }
        override fun onLost(network: Network) { synchronized(candidates) { candidates.remove(network); publish() } }
    }
    init {
        synchronized(candidates) {
            manager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
            manager.allNetworks.forEach { network -> manager.getNetworkCapabilities(network)?.let { candidates[network] = it } }
            publish()
        }
    }
    override fun close() { synchronized(candidates) {
        if (!closed) { closed = true; manager.unregisterNetworkCallback(callback); candidates.clear(); mutableNetwork.value = null }
    } }
}

internal object InternetHttp {
    /** HTTPS only; redirects bounded, GET-only. DNS and HTTP are scoped to the validated network. */
    fun open(network: Network, address: String, method: String = "GET"): HttpsURLConnection {
        val uri = RemoteConfig.requireHttps(address)
        val addresses = network.getAllByName(uri.host)
        require(addresses.isNotEmpty() && addresses.all { ip ->
            !ip.isLoopbackAddress && !ip.isAnyLocalAddress && !ip.isLinkLocalAddress && !ip.isSiteLocalAddress &&
                !ip.isMulticastAddress && !(ip is Inet6Address && (ip.address[0].toInt() and 0xfe) == 0xfc)
        }) { "Remote control cannot address a local/mesh host" }
        return (network.openConnection(URL(address)) as HttpsURLConnection).apply {
            requestMethod = method; instanceFollowRedirects = false; useCaches = false
            connectTimeout = 15_000; readTimeout = 15_000
            setRequestProperty("Accept-Encoding", "identity")
        }
    }
    fun get(network: Network, address: String): HttpsURLConnection {
        var current = address
        repeat(6) { index ->
            val connection = open(network, current)
            try {
                if (connection.responseCode in setOf(301, 302, 303, 307, 308)) {
                    require(index < 5) { "Redirect limit" }
                    current = URL(URL(current), checkNotNull(connection.getHeaderField("Location"))).toString()
                    connection.disconnect()
                } else return connection
            } catch (e: Exception) { connection.disconnect(); throw e }
        }
        error("Redirect limit")
    }
    suspend fun readLimited(connection: HttpsURLConnection, maximum: Int): ByteArray {
        require(connection.contentLengthLong <= maximum)
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(bytes)
                if (count < 0) break
                if (output.size() + count > maximum) throw IOException("Response too large")
                output.write(bytes, 0, count)
            }
        }
        return output.toByteArray()
    }
}
