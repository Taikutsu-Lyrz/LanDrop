package dev.landrop.mobile

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class DiscoveredPeer(
    val name: String,
    val ip: String,
    val port: Int,
    val availability: String,
    val requiresPairCode: Boolean,
    val deviceKind: String,
    val deviceId: String = "",
    val adapterKind: String = "unknown",
    val lastSeenMs: Long = System.currentTimeMillis()
) {
    /** Human link label: VPN / Wi-Fi Direct / LAN, mirroring desktop adapter kinds. */
    val linkLabel: String
        get() = when {
            isTailscaleOrVpnIp(ip) || adapterKind.equals("vpn", ignoreCase = true) -> "VPN"
            ip.startsWith("192.168.49.") || ip.startsWith("192.168.137.") || adapterKind.equals("wifi_direct", ignoreCase = true) -> "Wi-Fi Direct"
            adapterKind.equals("wifi", ignoreCase = true) -> "Wi-Fi"
            adapterKind.equals("ethernet", ignoreCase = true) -> "Ethernet"
            adapterKind.equals("ethernet_direct", ignoreCase = true) -> "Direct"
            else -> "LAN"
        }
}

internal fun isTailscaleOrVpnIp(ip: String): Boolean {
    val trimmed = ip.trim().substringBefore(':').trim('[', ']').trim()
    if (trimmed.startsWith("100.")) {
        val second = trimmed.split('.').getOrNull(1)?.toIntOrNull()
        if (second != null && second in 64..127) return true
    }
    return false
}

class AndroidDiscovery(
    private val context: Context,
    private val deviceId: String,
    private val deviceName: String,
    private val receiveEnabledProvider: () -> Boolean,
    private val pairCodeRequiredProvider: () -> Boolean,
    private val onPeer: (DiscoveredPeer) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val instanceId = UUID.randomUUID().toString()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "LanDrop-Android-Discovery", isDaemon = true) {
            runCatching {
                acquireMulticastLock()
                DatagramSocket(null).use { udp ->
                    udp.reuseAddress = true
                    udp.bind(InetSocketAddress("0.0.0.0", 50500))
                    socket = udp
                    udp.broadcast = true
                    AppLog.add("UDP discovery listening on 0.0.0.0:50500; local IPs: ${NetworkDetails.localIpv4Summary()}")
                    thread(name = "LanDrop-Android-Discovery-Broadcast", isDaemon = true) {
                        while (running.get()) {
                            runCatching {
                                broadcast(udp, "hello")
                                broadcast(udp, "query")
                            }
                            Thread.sleep(5000)
                        }
                    }
                    broadcast(udp, "query")
                    val buffer = ByteArray(64 * 1024)
                    while (running.get()) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udp.receive(packet)
                        runCatching { handlePacket(udp, packet) }
                            .onFailure { AppLog.add("Discovery packet ignored: ${it.message}") }
                    }
                }
            }.onFailure {
                if (running.get()) {
                    AppLog.add("UDP discovery unavailable: ${it.message}")
                    onStatus("Discovery unavailable: ${it.message}")
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        releaseMulticastLock()
        socket = null
    }

    /**
     * Rebind the UDP discovery socket (e.g. after joining/leaving a
     * Windows direct network, where the process default network changed).
     * A socket created on the old network may keep sending via it.
     */
    fun restart() {
        thread(name = "LanDrop-Android-Discovery-Restart", isDaemon = true) {
            stop()
            Thread.sleep(300)
            start()
        }
    }

    fun probeNow() {
        val udp = socket ?: return
        thread(name = "LanDrop-Android-Discovery-Probe", isDaemon = true) {
            repeat(3) { index ->
                if (!running.get()) return@thread
                runCatching {
                    broadcast(udp, "query")
                    broadcast(udp, "hello")
                }.onFailure {
                    AppLog.add("Discovery probe failed: ${it.message}")
                }
                if (index < 2) {
                    Thread.sleep(450)
                }
            }
        }
    }

    private fun handlePacket(udp: DatagramSocket, packet: DatagramPacket) {
        val source = "${packet.address.hostAddress}:${packet.port}"
        AppLog.add("UDP packet from $source (${packet.length} bytes)")
        val json = JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
        if (json.optString("app") != "landrop" || json.optString("protocol") != "discovery") {
            AppLog.add("UDP packet ignored: not LanDrop discovery")
            return
        }
        if (json.optString("instanceId") == instanceId) {
            AppLog.add("UDP packet ignored: own instance")
            return
        }

        when (json.optString("type")) {
            "query" -> {
                AppLog.add("Discovery query from ${packet.address.hostAddress}:${packet.port}")
                sendTo(udp, packet.address, packet.port, "response")
            }
            "hello", "response" -> {
                val peer = jsonToPeer(json, packet.address.hostAddress ?: return)
                AppLog.add("Discovered ${peer.name} at ${peer.ip}:${peer.port}")
                onPeer(peer)
            }
            else -> AppLog.add("Discovery packet type ignored: ${json.optString("type")}")
        }
    }

    private fun jsonToPeer(json: JSONObject, fallbackIp: String): DiscoveredPeer {
        val addresses = json.optJSONArray("addresses") ?: JSONArray()
        var ip = fallbackIp
        var adapterKind = "unknown"
        var bestPriority = Int.MIN_VALUE
        for (index in 0 until addresses.length()) {
            val address = addresses.optJSONObject(index) ?: continue
            if (address.optString("ip") == fallbackIp) {
                ip = fallbackIp
                adapterKind = address.optString("adapterKind", adapterKind)
                bestPriority = Int.MAX_VALUE
                break
            }
            val priority = address.optInt("priority", 0)
            if (priority >= bestPriority) {
                bestPriority = priority
                ip = address.optString("ip", fallbackIp)
                adapterKind = address.optString("adapterKind", adapterKind)
            }
        }
        if (fallbackIp.contains('.')) {
            ip = fallbackIp
        }
        // Infer VPN link when the UDP source itself is a Tailscale address,
        // even if the sender did not tag its adapter kind.
        if (adapterKind.equals("unknown", ignoreCase = true) && isTailscaleOrVpnIp(fallbackIp)) {
            adapterKind = "vpn"
        }
        return DiscoveredPeer(
            name = json.optString("deviceName", "LanDrop PC"),
            ip = ip,
            port = json.optInt("tcpPort", 50501),
            availability = json.optString("availability", "available"),
            requiresPairCode = json.optBoolean("requiresPairCode"),
            deviceKind = json.optString("deviceKind", "unknown"),
            deviceId = json.optString("deviceId", ""),
            adapterKind = adapterKind,
            lastSeenMs = System.currentTimeMillis()
        )
    }

    private fun broadcast(udp: DatagramSocket, type: String) {
        val payload = discoveryPacket(type).toString().toByteArray(Charsets.UTF_8)
        val targets = broadcastTargets()
        targets.forEach { target ->
            // A missing route on one adapter must not suppress all other links.
            runCatching { udp.send(DatagramPacket(payload, payload.size, target, 50500)) }
                .onFailure { AppLog.add("Discovery send to ${target.hostAddress} failed: ${it.message}") }
        }
        AppLog.add("Discovery $type sent to ${targets.joinToString { it.hostAddress ?: it.toString() }}")
    }

    private fun sendTo(udp: DatagramSocket, address: InetAddress, port: Int, type: String) {
        val payload = discoveryPacket(type).toString().toByteArray(Charsets.UTF_8)
        udp.send(DatagramPacket(payload, payload.size, address, port))
    }

    private fun discoveryPacket(type: String): JSONObject {
        return JSONObject()
            .put("app", "landrop")
            .put("protocol", "discovery")
            .put("version", 1)
            .put("type", type)
            .put("deviceId", deviceId)
            .put("instanceId", instanceId)
            .put("deviceName", deviceName)
            .put("tcpPort", 50501)
            .put("availability", if (receiveEnabledProvider()) "available" else "receive_disabled")
            .put("requiresPairCode", pairCodeRequiredProvider())
            .put("deviceKind", "android")
            .put("addresses", JSONArray().apply {
                val fallbackKind = NetworkDetails.advertisedAdapterKind(context)
                NetworkDetails.localIpv4Addresses().forEach { ip ->
                    // Tag per IP so the direct link never shows as unknown
                    // even when the system default network is still home Wi-Fi.
                    val kind = if (NetworkDetails.isDirectLinkIp(ip)) "wifi_direct" else fallbackKind
                    put(
                        JSONObject()
                            .put("ip", ip)
                            .put("adapterKind", kind)
                            .put("priority", 20)
                    )
                }
            })
            .put("timestampMs", System.currentTimeMillis())
    }

    private fun broadcastTargets(): List<InetAddress> {
        val targets = mutableSetOf<InetAddress>()
        targets += InetAddress.getByName("255.255.255.255")
        targets += InetAddress.getByName("192.168.43.1")
        targets += InetAddress.getByName("192.168.43.255")
        targets += InetAddress.getByName("192.168.137.1")
        targets += InetAddress.getByName("192.168.137.255")
        targets += InetAddress.getByName("192.168.49.1")
        targets += InetAddress.getByName("192.168.49.255")
        targets += InetAddress.getByName("172.20.10.1")
        targets += InetAddress.getByName("172.20.10.15")
        wifiGatewayAddress()?.let { targets += it }
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .forEach { nic ->
                nic.interfaceAddresses.forEach { address ->
                    address.broadcast?.let { targets += it }
                    subnetBroadcast(address.address.hostAddress, address.networkPrefixLength)
                        ?.let { targets += it }
                    subnetProbeTargets(address.address.hostAddress, address.networkPrefixLength)
                        .forEach { targets += it }
                }
            }
        return targets.toList()
    }

    private fun subnetBroadcast(ip: String?, prefixLength: Short): InetAddress? {
        if (ip == null || !ip.contains('.') || prefixLength !in 1..30) return null
        val octets = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null
        val raw = octets.fold(0) { acc, octet -> (acc shl 8) or octet }
        val mask = (-1 shl (32 - prefixLength)).toInt()
        val broadcast = raw or mask.inv()
        val host = listOf(
            (broadcast ushr 24) and 0xff,
            (broadcast ushr 16) and 0xff,
            (broadcast ushr 8) and 0xff,
            broadcast and 0xff
        ).joinToString(".")
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }

    private fun wifiGatewayAddress(): InetAddress? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val gateway = wifiManager?.dhcpInfo?.gateway ?: return null
        if (gateway == 0) return null
        val host = listOf(
            gateway and 0xff,
            (gateway shr 8) and 0xff,
            (gateway shr 16) and 0xff,
            (gateway shr 24) and 0xff
        ).joinToString(".")
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }

    private fun acquireMulticastLock() {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("LanDropDiscovery")?.apply {
            setReferenceCounted(false)
            runCatching {
                acquire()
                AppLog.add("Wi-Fi multicast lock acquired")
            }.onFailure {
                AppLog.add("Could not acquire multicast lock: ${it.message}")
            }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            runCatching {
                if (lock.isHeld) lock.release()
            }
        }
        multicastLock = null
    }
}
