package com.project.lol.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NetworkMonitor {

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var started = false

    fun isOnlineNow(context: Context): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) {
        true
    }

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        _online.value = isOnlineNow(app)
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _online.value = true
            }

            override fun onLost(network: Network) {
                _online.value = isOnlineNow(app)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                _online.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { Logger.d("net", "network callback not registered: $it") }
    }
}
