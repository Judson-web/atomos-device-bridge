package dev.atomos.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.ConnectivityManager.NetworkCallback
import android.net.TrafficStats
import kotlinx.coroutines.delay

data class NetworkSnapshot(val connected: Boolean, val transport: String, val validated: Boolean, val downloadBps: Long, val uploadBps: Long, val ipAddress: String)

class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    fun snapshot(previousRx: Long, previousTx: Long, elapsedMs: Long): NetworkSnapshot {
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile data"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
            else -> if (connected) "Connected" else "Offline"
        }
        val props: LinkProperties? = network?.let { cm.getLinkProperties(it) }
        val ip = props?.linkAddresses?.firstOrNull { !it.address.isLoopbackAddress }?.address?.hostAddress.orEmpty()
        val rx = TrafficStats.getTotalRxBytes().coerceAtLeast(0)
        val tx = TrafficStats.getTotalTxBytes().coerceAtLeast(0)
        val scale = 1000.0 / elapsedMs.coerceAtLeast(1)
        return NetworkSnapshot(connected, transport, caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            if (previousRx < 0) 0 else ((rx - previousRx).coerceAtLeast(0) * scale).toLong(),
            if (previousTx < 0) 0 else ((tx - previousTx).coerceAtLeast(0) * scale).toLong(), ip)
    }
    fun counters() = Pair(TrafficStats.getTotalRxBytes().coerceAtLeast(0), TrafficStats.getTotalTxBytes().coerceAtLeast(0))
}

fun formatRate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_000_000 -> String.format("%.1f MB/s", bytesPerSecond / 1_000_000.0)
    bytesPerSecond >= 1_000 -> String.format("%.0f KB/s", bytesPerSecond / 1_000.0)
    else -> "$bytesPerSecond B/s"
}
