package dev.landrop.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

data class ActiveNetworkDetails(
    val transport: String,
    val interfaceName: String,
    val addresses: String
)

object NetworkDetails {
    fun localIpv4Addresses(): List<String> {
        return NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic -> nic.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    }

    fun localIpv4Summary(): String {
        return localIpv4Addresses().joinToString().ifBlank { "No IPv4 address found" }
    }

    fun activeNetworkDetails(context: Context): ActiveNetworkDetails {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = connectivity?.activeNetwork
        val capabilities = activeNetwork?.let(connectivity::getNetworkCapabilities)
        val linkProperties = activeNetwork?.let(connectivity::getLinkProperties)

        val transport = when {
            capabilities == null -> "No active network"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE) -> "Wi-Fi Direct"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> {
                // Wi-Fi Direct p2p interfaces and Tailscale (tun0) often surface
                // without a flagged transport; infer from interface names/IPs.
                val iface = linkProperties?.interfaceName.orEmpty().lowercase()
                val ips = localIpv4Addresses()
                when {
                    iface.contains("p2p") || ips.any { it.startsWith("192.168.49.") } -> "Wi-Fi Direct"
                    iface.startsWith("tun") || iface.startsWith("tailscale") ||
                        ips.any { isTailscaleIp(it) } -> "VPN"
                    else -> "Other network"
                }
            }
        }
        val addresses = linkProperties?.linkAddresses
            ?.mapNotNull { it.address.hostAddress }
            ?.filter { it.contains('.') }
            ?.joinToString()
            ?.ifBlank { null }
            ?: localIpv4Summary()
        return ActiveNetworkDetails(
            transport = transport,
            interfaceName = linkProperties?.interfaceName ?: "Unknown interface",
            addresses = addresses
        )
    }

    fun advertisedAdapterKind(context: Context): String {
        val transport = activeNetworkDetails(context).transport
        return when (transport) {
            "Ethernet" -> "ethernet"
            "Wi-Fi" -> "wifi"
            "VPN" -> "vpn"
            "Wi-Fi Direct" -> "wifi_direct"
            else -> "unknown"
        }
    }

    fun isTailscaleIp(ip: String): Boolean {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return false
        if (parts[0] != "100") return false
        val second = parts[1].toIntOrNull() ?: return false
        return second in 64..127
    }

    /** Direct-link subnets (phone-to-phone p2p / Windows legacy AP). */
    fun isDirectLinkIp(ip: String): Boolean {
        val trimmed = ip.trim()
        return trimmed.startsWith("192.168.49.") || trimmed.startsWith("192.168.137.")
    }

    fun isVpnInterface(interfaceName: String): Boolean {
        val lower = interfaceName.lowercase()
        return lower.startsWith("tun") || lower.startsWith("tailscale") ||
            lower.contains("vpn") || lower.contains("wireguard") || lower.contains("zerotier")
    }
}
