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

private const val SETTLE_MS = 1_500L

fun wifiKey(gateway: String?, dns: String) = "wifi:$gateway|$dns"

/** Ключ Wi-Fi прошлых версий (DNS вперемешку, с IPv6) → нынешний: только IPv4 DNS по порядку. */
fun normalizeKey(key: String): String {
    if (!key.startsWith("wifi:")) return key
    val gateway = key.removePrefix("wifi:").substringBefore('|')
    val dns = key.substringAfter('|', "").split(',').filter { it.isNotEmpty() && ':' !in it }.sorted().joinToString(",")
    return wifiKey(gateway, dns)
}

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
                override fun onAvailable(network: Network) = refreshSoon()
                override fun onLost(network: Network) = refreshSoon()
                override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = refresh()
                // Wi-Fi стал основной сетью (а мобильная осталась «на всякий случай») — видно только по возможностям.
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
            })
        }
    }

    fun refresh() {
        _current.value = identify(cm.activeNetwork)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Сейчас и ещё раз через [SETTLE_MS]: при подключении Wi-Fi `onAvailable` приходит раньше, чем он становится
     * основной сетью, — без повтора ключ оставался мобильным, пока работал Wi-Fi.
     */
    private fun refreshSoon() {
        refresh()
        handler.postDelayed({ refresh() }, SETTLE_MS)
    }

    private fun identify(network: Network?): NetworkId? {
        network ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        val lp = cm.getLinkProperties(network)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                // Две SIM-карты — оператор той, через которую идут данные.
                val tm = context.getSystemService(TelephonyManager::class.java).let { base ->
                    val sub = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
                    if (sub != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) base.createForSubscriptionId(sub) else base
                }
                val mccMnc = runCatching { tm.networkOperator }.getOrNull().orEmpty()
                val name = runCatching { tm.networkOperatorName }.getOrNull()?.takeIf { it.isNotBlank() }
                NetworkId("cell:$mccMnc", NetworkKind.Cellular, name)
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val gateway = lp?.routes?.mapNotNull { it.gateway }?.firstOrNull { it is Inet4Address && !it.isAnyLocalAddress }?.hostAddress
                // Только IPv4 и по порядку: адреса IPv6 (RDNSS) появляются позже и истекают — ключ «дрожал».
                val dns = lp?.dnsServers?.filterIsInstance<Inet4Address>()?.mapNotNull { it.hostAddress }?.sorted()?.joinToString(",").orEmpty()
                NetworkId(wifiKey(gateway, dns), NetworkKind.Wifi, null)
            }
            else -> NetworkId("net:${lp?.interfaceName}", NetworkKind.Other, null)
        }
    }
}
