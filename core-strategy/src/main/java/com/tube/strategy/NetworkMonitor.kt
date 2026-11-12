package com.tube.strategy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

enum class NetworkKind { Wifi, Cellular, Other }

/**
 * Сеть, для которой запоминается лучшая стратегия. Имя Wi-Fi без разрешения на геолокацию недоступно,
 * поэтому домашний и рабочий Wi-Fi различаются по шлюзу и DNS роутера, а мобильная сеть — по оператору.
 */
data class NetworkId(val key: String, val kind: NetworkKind, val operator: String?)

/**
 * Текущая сеть (основная сеть приложения; Tube не входит в собственный VPN, поэтому это сеть провайдера).
 */
class NetworkMonitor(private val context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _current = MutableStateFlow(identify(cm.activeNetwork))
    val current: StateFlow<NetworkId?> = _current.asStateFlow()

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                override fun onLost(network: Network) = refresh()
                override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = refresh()
            })
        }
    }

    fun refresh() {
        _current.value = identify(cm.activeNetwork)
    }

    private fun identify(network: Network?): NetworkId? {
        network ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        val lp = cm.getLinkProperties(network)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val tm = context.getSystemService(TelephonyManager::class.java)
                val mccMnc = runCatching { tm.networkOperator }.getOrNull().orEmpty()
                val name = runCatching { tm.networkOperatorName }.getOrNull()?.takeIf { it.isNotBlank() }
                NetworkId("cell:$mccMnc", NetworkKind.Cellular, name)
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val gateway = lp?.routes?.mapNotNull { it.gateway }?.firstOrNull { it is Inet4Address && !it.isAnyLocalAddress }?.hostAddress
                val dns = lp?.dnsServers?.joinToString(",") { it.hostAddress.orEmpty() }.orEmpty()
                NetworkId("wifi:$gateway|$dns", NetworkKind.Wifi, null)
            }
            else -> NetworkId("net:${lp?.interfaceName}", NetworkKind.Other, null)
        }
    }
}
