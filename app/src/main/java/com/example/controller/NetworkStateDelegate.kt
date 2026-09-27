package com.example.controller

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

/**
 * Delegate responsible for tracking device network changes,
 * connection drops and notifying on reconnection to auto-refresh active views silently.
 */
class NetworkStateDelegate(
    private val application: Application,
    private val onNetworkReconnected: () -> Unit,
    private val onNotify: (String) -> Unit
) {
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wasOffline = false

    fun start() {
        try {
            val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                connectivityManager = cm

                val activeNetwork = cm.activeNetwork
                val capabilities = cm.getNetworkCapabilities(activeNetwork)
                val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                wasOffline = !hasInternet

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        Handler(Looper.getMainLooper()).post {
                            if (wasOffline) {
                                wasOffline = false
                                onNotify("Ligação restabelecida. A recarregar página...")
                                onNetworkReconnected()
                            }
                        }
                    }

                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        super.onCapabilitiesChanged(network, capabilities)
                        val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        if (isValidated && wasOffline) {
                            Handler(Looper.getMainLooper()).post {
                                wasOffline = false
                                onNotify("Ligação restabelecida. A recarregar página...")
                                onNetworkReconnected()
                            }
                        }
                    }

                    override fun onLost(network: Network) {
                        super.onLost(network)
                        Handler(Looper.getMainLooper()).post {
                            val activeNet = cm.activeNetwork
                            val caps = cm.getNetworkCapabilities(activeNet)
                            val hasInternetNow = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                            if (!hasInternetNow) {
                                wasOffline = true
                                onNotify("Sem ligação à internet. A aguardar conexão para recarregar...")
                            }
                        }
                    }
                }

                networkCallback = callback

                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, callback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        try {
            networkCallback?.let { callback ->
                connectivityManager?.unregisterNetworkCallback(callback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

