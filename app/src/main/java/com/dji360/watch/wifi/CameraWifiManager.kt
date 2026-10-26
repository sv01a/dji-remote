package com.dji360.watch.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
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

    val isConnected: Boolean get() = activeNetwork != null

    /**
     * Connect to the camera AP with given SSID and WPA passphrase.
     * Suspends until [ConnectivityManager.NetworkCallback.onAvailable] is called and process is bound,
     * or fails on timeout.
     */
    suspend fun connectToCameraAp(
        ssid: String,
        password: String,
        timeoutMs: Long = 20_000
    ): Boolean {
        disconnect()
        log("WiFi: Connecting to camera AP SSID='$ssid' (timeout ${timeoutMs / 1000}s)...")

        val deferred = CompletableDeferred<Boolean>()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log("WiFi: Camera network available: $network")
                activeNetwork = network
                val bound = cm.bindProcessToNetwork(network)
                log("WiFi: Process bound to camera network: $bound")
                deferred.complete(true)
            }

            override fun onUnavailable() {
                log("WiFi: Camera network unavailable / rejected by user")
                deferred.complete(false)
            }

            override fun onLost(network: Network) {
                log("WiFi: Camera network lost: $network")
                if (activeNetwork == network) {
                    activeNetwork = null
                    runCatching { cm.bindProcessToNetwork(null) }
                }
            }
        }
        networkCallback = callback

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (password.isNotEmpty()) {
                specifierBuilder.setWpa2Passphrase(password)
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
                log("WiFi: requestNetwork failed: ${e.message}")
                disconnect()
                return false
            }
        } else {
            // For older Android / compat layers
            log("WiFi: SDK < Q not supported for WifiNetworkSpecifier")
            return false
        }

        val success = runCatching { withTimeoutOrNull(timeoutMs) { deferred.await() } }.getOrNull() == true
        if (!success) {
            log("WiFi: Connection timed out or failed")
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
        } catch (_: Exception) {}
        log("WiFi: Disconnected and process network unbound")
    }
}
