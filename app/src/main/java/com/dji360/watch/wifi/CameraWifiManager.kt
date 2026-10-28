package com.dji360.watch.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import java.net.Inet4Address
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages Wi-Fi connection from the watch directly to the DJI camera's Access Point.
 * Once connected, binds the process network traffic ([ConnectivityManager.bindProcessToNetwork])
 * to ensure UDP Datalink / Live View traffic routes over the camera's local Wi-Fi interface (192.168.2.1)
 * without needing an active internet connection.
 */
class CameraWifiManager(
    private val context: Context,
    private val log: (String) -> Unit
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    var activeNetwork: Network? = null
        private set

    val isCameraWifiConnected: Boolean
        get() = findCameraWifiNetwork() != null

    val isConnected: Boolean
        get() = isCameraWifiConnected

    fun findCameraWifiNetwork(): Network? {
        val wifiNets = mutableListOf<Network>()
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                wifiNets.add(net)
            }
        }
        if (wifiNets.isEmpty()) {
            activeNetwork = null
            return null
        }

        // Prefer camera network (no internet validation or 192.168.2.x)
        val chosen = wifiNets.firstOrNull { net ->
            val lp = cm.getLinkProperties(net)
            val hasCameraSubnet = lp?.linkAddresses?.any { it.address.hostAddress?.startsWith("192.168.2.") == true } == true
            val hasCameraRoute = lp?.routes?.any { it.gateway?.hostAddress == "192.168.2.1" } == true
            val noInternet = cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == false
            hasCameraSubnet || hasCameraRoute || noInternet
        } ?: wifiNets.first()

        activeNetwork = chosen
        try {
            val bound = cm.bindProcessToNetwork(chosen)
            log("WiFi: Process bound to Wi-Fi network $chosen (result=$bound)")
        } catch (e: Exception) {
            log("WiFi: bindProcessToNetwork error: ${e.message}")
        }
        return chosen
    }

    fun findWifiNetwork(): Network? {
        return findCameraWifiNetwork()
    }

    fun logWifiSummary(): String {
        val net = findWifiNetwork() ?: return "No Wi-Fi Network"
        val lp = cm.getLinkProperties(net) ?: return "No LinkProperties"
        val iface = lp.interfaceName ?: "wlan"
        val addrs = lp.linkAddresses.map { it.address.hostAddress }.filter { it != null && it.contains(".") }
        val routes = lp.routes.map { "${it.destination} -> ${it.gateway?.hostAddress ?: "direct"}" }
        val summary = "WiFi[$iface]: IPs=$addrs Routes=$routes"
        log(summary)
        return summary
    }

    fun getGatewayIp(): String {
        val net = findWifiNetwork() ?: return "192.168.2.1"
        val lp = cm.getLinkProperties(net) ?: return "192.168.2.1"

        // 1. From routes
        for (route in lp.routes) {
            val gateway = route.gateway
            if (gateway is Inet4Address && !gateway.isAnyLocalAddress) {
                val ip = gateway.hostAddress
                if (!ip.isNullOrEmpty() && ip != "0.0.0.0") {
                    log("WiFi: Gateway from route: $ip")
                    return ip
                }
            }
        }

        // 2. From DHCP server
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dhcp = lp.dhcpServerAddress
            if (dhcp is Inet4Address && !dhcp.isAnyLocalAddress) {
                val ip = dhcp.hostAddress
                if (!ip.isNullOrEmpty() && ip != "0.0.0.0") {
                    log("WiFi: Gateway from DHCP: $ip")
                    return ip
                }
            }
        }

        // 3. From local IPv4 address (derive x.y.z.1)
        for (addr in lp.linkAddresses) {
            val inet = addr.address
            if (inet is Inet4Address && !inet.isLoopbackAddress && !inet.isLinkLocalAddress) {
                val host = inet.hostAddress ?: continue
                val parts = host.split(".")
                if (parts.size == 4) {
                    val derived = "${parts[0]}.${parts[1]}.${parts[2]}.1"
                    log("WiFi: Derived camera IP from local IP $host -> $derived")
                    return derived
                }
            }
        }

        log("WiFi: Gateway fallback to 192.168.2.1")
        return "192.168.2.1"
    }

    /**
     * Connect to the camera AP with given SSID and WPA passphrase.
     * Suspends until [ConnectivityManager.NetworkCallback.onAvailable] is called and process is bound,
     * or fails on timeout.
     */
    suspend fun connectToCameraAp(
        ssid: String,
        password: String,
        timeoutMs: Long = 25_000
    ): Boolean {
        // Check if actually connected to a valid Wi-Fi network right now
        val current = findCameraWifiNetwork()
        if (current != null) {
            log("WiFi: Already connected to camera network: $current")
            return true
        }
        activeNetwork = null

        // Try WPA2 first with full 20s timeout
        var ok = attemptConnect(ssid, password, isWpa3 = false, timeoutMs = 20_000)
        if (!ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && password.isNotEmpty()) {
            log("WiFi: WPA2 attempt failed/timed out, trying WPA3...")
            ok = attemptConnect(ssid, password, isWpa3 = true, timeoutMs = 15_000)
        }
        return ok
    }

    private suspend fun attemptConnect(
        ssid: String,
        password: String,
        isWpa3: Boolean,
        timeoutMs: Long
    ): Boolean {
        disconnect()
        val sec = if (isWpa3) "WPA3" else "WPA2"
        log("WiFi: Connecting to camera AP SSID='$ssid' ($sec, timeout ${timeoutMs / 1000}s)...")

        val deferred = CompletableDeferred<Boolean>()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log("WiFi: Camera network available: " + network)
                activeNetwork = network
                val bound = cm.bindProcessToNetwork(network)
                log("WiFi: Process bound to camera network: " + bound)
                deferred.complete(true)
            }

            override fun onUnavailable() {
                log("WiFi: Camera network unavailable / rejected")
                deferred.complete(false)
            }

            override fun onLost(network: Network) {
                log("WiFi: Camera network lost: " + network)
                if (activeNetwork == network) {
                    activeNetwork = null
                    runCatching { cm.bindProcessToNetwork(null) }
                }
            }
        }
        networkCallback = callback

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val specifierBuilder = WifiNetworkSpecifier.Builder()
            if (ssid.length >= 8 && (ssid.contains("-") || ssid.contains("_"))) {
                specifierBuilder.setSsid(ssid)
            } else {
                val cleanPrefix = ssid.replace(" ", "")
                specifierBuilder.setSsidPattern(android.os.PatternMatcher(cleanPrefix, android.os.PatternMatcher.PATTERN_PREFIX))
            }
            if (password.isNotEmpty()) {
                if (isWpa3 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    specifierBuilder.setWpa3Passphrase(password)
                } else {
                    specifierBuilder.setWpa2Passphrase(password)
                }
            }
            val specifier = specifierBuilder.build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            try {
                cm.requestNetwork(request, callback)
            } catch (e: Exception) {
                log("WiFi: requestNetwork failed: " + e.message)
                disconnect()
                return false
            }
        } else {
            log("WiFi: SDK < Q not supported for WifiNetworkSpecifier")
            return false
        }

        val success = runCatching { withTimeoutOrNull(timeoutMs) { deferred.await() } }.getOrNull() == true
        if (!success) {
            log("WiFi: " + sec + " connection timed out or failed")
            disconnect()
        }
        return success
    }

    /**
     * Unbinds process from camera network and releases the network request.
     */
    fun disconnect() {
        val cb = networkCallback
        networkCallback = null
        if (cb != null) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                log("WiFi: unregister callback exception: ${e.message}")
            }
        }
        activeNetwork = null
        try {
            cm.bindProcessToNetwork(null)
        } catch (e: Exception) {}
        log("WiFi: Disconnected and process network unbound")
    }
}
