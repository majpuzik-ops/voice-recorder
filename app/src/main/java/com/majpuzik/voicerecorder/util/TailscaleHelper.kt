package com.majpuzik.voicerecorder.util

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface

class TailscaleHelper(private val context: Context) {

    companion object {
        private const val TAG = "TailscaleHelper"
        private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
        private const val TAILSCALE_IP_PREFIX = "100."
    }

    /**
     * Check if Tailscale VPN is currently active
     */
    fun isTailscaleActive(): Boolean {
        return try {
            // Check for Tailscale IP address in network interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    val hostAddress = address.hostAddress ?: continue
                    // Tailscale IPs are in 100.x.x.x range
                    if (hostAddress.startsWith(TAILSCALE_IP_PREFIX)) {
                        Log.d(TAG, "Tailscale active: $hostAddress")
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Tailscale: ${e.message}")
            false
        }
    }

    /**
     * Check if Tailscale app is installed
     */
    fun isTailscaleInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(TAILSCALE_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Open Tailscale app to enable VPN
     */
    fun openTailscale() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening Tailscale: ${e.message}")
        }
    }

    /**
     * Get Tailscale IP address if connected
     */
    fun getTailscaleIP(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    val hostAddress = address.hostAddress ?: continue
                    if (hostAddress.startsWith(TAILSCALE_IP_PREFIX)) {
                        return hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if server is reachable via Tailscale
     */
    suspend fun isServerReachable(serverIP: String, timeout: Int = 3000): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val address = InetAddress.getByName(serverIP)
                address.isReachable(timeout)
            } catch (e: Exception) {
                Log.e(TAG, "Server not reachable: ${e.message}")
                false
            }
        }
    }

    /**
     * Register for VPN state changes
     */
    fun registerVpnCallback(onConnected: () -> Unit, onDisconnected: () -> Unit): Any? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (isTailscaleActive()) {
                        onConnected()
                    }
                }

                override fun onLost(network: Network) {
                    if (!isTailscaleActive()) {
                        onDisconnected()
                    }
                }
            }

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .build()

            try {
                connectivityManager.registerNetworkCallback(request, callback)
                return callback
            } catch (e: Exception) {
                Log.e(TAG, "Error registering VPN callback: ${e.message}")
            }
        }
        return null
    }

    fun unregisterVpnCallback(callback: Any?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && callback is ConnectivityManager.NetworkCallback) {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering callback: ${e.message}")
            }
        }
    }
}
