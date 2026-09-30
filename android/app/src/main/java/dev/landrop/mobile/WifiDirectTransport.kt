package dev.landrop.mobile

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.File

/** Best-effort client IPs for the p2p group owner, read from the ARP table. */
internal fun p2pClientIpsFromArp(): List<String> {
    return runCatching {
        val arp = File("/proc/net/arp")
        if (!arp.canRead()) return emptyList()
        arp.readLines()
            .drop(1)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 6) {
                    val ip = parts[0].trim()
                    val device = parts[5].trim()
                    // p2p interfaces are typically p2p0 / p2p-wlan0-0.
                    if (ip.startsWith("192.168.49.") && device.contains("p2p")) ip else null
                } else null
            }
            .filter { it != "192.168.49.1" }
            .distinct()
    }.getOrDefault(emptyList())
}

/** Windows hosts a legacy AP (DIRECT-LanDrop-...), not a P2P group. */
internal fun isWindowsHostSsid(ssid: String): Boolean =
    ssid.startsWith("DIRECT-LanDrop-", ignoreCase = true)

/** Default gateway Windows assigns its hosted direct network (ICS). */
internal const val WINDOWS_DIRECT_GATEWAY = "192.168.137.1"

data class WifiDirectLink(
    val groupOwnerAddress: String,
    val isGroupOwner: Boolean,
    val groupFormed: Boolean = true,
    /** MAC addresses of connected peers (from WifiP2pGroup), if known. */
    val clientDevices: List<String> = emptyList(),
    /** Local p2p interface IPv4 (e.g. 192.168.49.1 or 192.168.49.x), if known. */
    val localAddress: String? = null
) {
    /**
     * Candidate LanDrop TCP endpoints to probe, ordered by likelihood.
     * The GO is almost always 192.168.49.1. When we are GO, the peer's DHCP
     * address is unknown, so we include ARP-derived addresses plus the
     * conventional client range head (probing is cheap and parallel).
     */
    fun candidateIps(): List<String> {
        val ordered = LinkedHashSet<String>()
        if (!isGroupOwner) {
            if (groupOwnerAddress.isNotBlank()) ordered += groupOwnerAddress.trim()
            ordered += "192.168.49.1"
            // Windows hosts use Internet Connection Sharing (usually .137.1).
            ordered += WINDOWS_DIRECT_GATEWAY
        } else {
            // We are GO: probe clients learned from ARP plus the usual pool.
            ordered += p2pClientIpsFromArp()
            for (octet in 2..20) ordered += "192.168.49.$octet"
            ordered += "192.168.49.254"
            // Also include GO itself in case the other side hosts LanDrop there
            // (harmless; probe will just hit ourselves).
            if (groupOwnerAddress.isNotBlank()) ordered += groupOwnerAddress.trim()
        }
        localAddress?.let { ordered -= it.trim() }
        return ordered.filter { it.isNotBlank() }.distinct()
    }
}

class WifiDirectTransport(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onConnected: (WifiDirectLink) -> Unit,
    private val onHostCredentials: (WifiDirectCredentials?) -> Unit = {},
    private val onDisconnected: () -> Unit = {}
) {
    private val manager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel =
        manager?.initialize(context, Looper.getMainLooper(), null)
    private var registered = false
    private var legacyNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val legacyJoinHandler = android.os.Handler(Looper.getMainLooper())
    private var legacyJoinTimeout: Runnable? = null
    // True while joined to a Windows-hosted legacy AP. P2P connection info
    // is meaningless there: skip P2P refreshes and "not formed" retries so
    // they cannot clobber the live legacy link status.
    private var legacyActive = false
    // A group owner can be fully created while no client is connected yet.
    // Keep that state separate from NetworkInfo.isConnected so transient
    // disconnect broadcasts do not tear down the QR host UI.
    private var hostingGroup = false

    private var session = 0
    private var stopped = true
    private var resetting = false
    private val connectionListener: WifiP2pManager.ConnectionInfoListener
        get() {
            val expectedSession = session
            return WifiP2pManager.ConnectionInfoListener { info: WifiP2pInfo ->
                if (stopped || resetting || legacyNetworkCallback != null || session != expectedSession) return@ConnectionInfoListener
                val owner = info.groupOwnerAddress?.hostAddress
                if (info.groupFormed && !owner.isNullOrBlank()) {
                    hostingGroup = info.isGroupOwner
                    // Resolve group details (client list for GO case) before emitting.
                    // requestGroupInfo is best-effort; always emit a link even if it fails.
                    val manager = manager
                    val channel = channel
                    if (manager != null && channel != null && hasWifiDirectPermission()) {
                        runCatching {
                            manager.requestGroupInfo(channel) { group ->
                                if (stopped || session != expectedSession) return@requestGroupInfo
                                val clients = group?.clientList
                                    ?.mapNotNull { it.deviceAddress?.trim() }
                                    ?.filter { it.isNotBlank() }
                                    ?: emptyList()
                                val link = WifiDirectLink(
                                    groupOwnerAddress = owner,
                                    isGroupOwner = info.isGroupOwner,
                                    groupFormed = true,
                                    clientDevices = clients,
                                    localAddress = localP2pAddress()
                                )
                                if (info.isGroupOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    val networkName = group?.networkName?.trim().orEmpty()
                                    val passphrase = group?.passphrase?.trim().orEmpty()
                                    if (networkName.isNotBlank() && passphrase.length in 8..63) {
                                        onHostCredentials(WifiDirectCredentials(networkName, passphrase))
                                    }
                                }
                                onConnected(link)
                                onStatus(
                                    if (info.isGroupOwner) {
                                        "Wi-Fi Direct group hosted at $owner. Probing for LanDrop phones."
                                    } else {
                                        "Wi-Fi Direct connected: $owner. Probing for LanDrop."
                                    }
                                )
                            }
                        }.onFailure {
                            val link = WifiDirectLink(
                                groupOwnerAddress = owner,
                                isGroupOwner = info.isGroupOwner,
                                localAddress = localP2pAddress()
                            )
                            onConnected(link)
                            onStatus("Wi-Fi Direct connected: $owner")
                        }
                    } else {
                        val link = WifiDirectLink(
                            groupOwnerAddress = owner,
                            isGroupOwner = info.isGroupOwner,
                            localAddress = localP2pAddress()
                        )
                        onConnected(link)
                        onStatus("Wi-Fi Direct connected: $owner")
                    }
                } else if (!info.groupFormed && !legacyActive) {
                    onStatus("Wi-Fi Direct group was not formed yet. Retrying link info.")
                }
            }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (stopped || resetting || legacyNetworkCallback != null) return
            val manager = manager ?: return
            val channel = channel ?: return
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    onStatus(
                        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            "Wi-Fi Direct is available."
                        } else {
                            "Wi-Fi Direct is off on this phone."
                        }
                    )
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val networkInfo =
                        intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        if (hasWifiDirectPermission()) {
                            // Small delay: DHCP on the p2p interface needs a moment
                            // before the GO address / client IPs are usable.
                            legacyJoinHandler.postDelayed({
                                runCatching {
                                    manager.requestConnectionInfo(channel, connectionListener)
                                }
                            }, 1200)
                        } else {
                            onStatus("Wi-Fi Direct needs nearby Wi-Fi permission.")
                        }
                    } else if (hostingGroup) {
                        // A group owner has no connected client yet (or a client
                        // just left). The group and its QR credentials are still
                        // valid, so do not clear the host UI.
                        onStatus("Wi-Fi Direct host is ready. Waiting for a phone to join.")
                        legacyJoinHandler.postDelayed({
                            refreshLinkInfo()
                        }, 500)
                    } else if (!legacyActive) {
                        // P2P disconnect broadcasts also fire while on a
                        // Windows legacy link; those must not tear it down.
                        onStatus("Wi-Fi Direct disconnected.")
                        onDisconnected()
                    }
                }

            }
        }
    }

    fun start() {
        if (registered || manager == null || channel == null) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        registered = true
        refreshLinkInfo()
    }

    fun stop() {
        disconnect()
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    fun stopDiscovery() {
        val manager = manager
        val channel = channel
        if (manager == null || channel == null || !hasWifiDirectPermission()) return
        runCatching {
            manager.stopPeerDiscovery(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {}
                    override fun onFailure(reason: Int) {}
                }
            )
        }
    }

    /**
     * Get the radio ready for a Wi-Fi Direct group: drop any LanDrop-held
     * legacy/specifier link and ask the framework to leave the current
     * infrastructure Wi-Fi. Without this, joining from a QR scan while on
     * home Wi-Fi ends at the system "leave this network" prompt — or a
     * stale specifier request holds the radio and the P2P connect fails.
     */
    fun prepareForDirectJoin() {
        leaveLegacyNetwork()
        val wifi = wifiManager()
        if (wifi != null) {
            @Suppress("DEPRECATION")
            val connected = runCatching {
                val info = wifi.connectionInfo
                info != null && info.networkId != -1
            }.getOrNull() == true
            if (connected) {
                onStatus("Leaving current Wi-Fi for the direct link...")
                // Reliable on Android 9 and below; best-effort on 10+ where
                // the framework owns the connection and may still confirm
                // with the user — harmless either way.
                runCatching {
                    @Suppress("DEPRECATION")
                    wifi.disconnect()
                }
            }
        }
    }

    private fun afterGroupReleased(action: () -> Unit) {
        val manager = manager ?: return
        val channel = channel ?: return
        val expectedSession = session
        resetting = true
        prepareForDirectJoin()
        hostingGroup = false
        onHostCredentials(null)
        onDisconnected()
        stopDiscovery()
        runCatching { manager.cancelConnect(channel, null) }
        var finished = false
        val timeout = Runnable {
            if (!finished && session == expectedSession) {
                finished = true
                stopped = true
                onStatus("Could not reset the previous Wi-Fi Direct group. Tap Stop Wi-Fi Direct, or turn Wi-Fi off and on, then retry.")
            }
        }
        legacyJoinHandler.postDelayed(timeout, 8000)
        fun ready() {
            if (finished || stopped || session != expectedSession) return
            finished = true
            legacyJoinHandler.removeCallbacks(timeout)
            resetting = false
            action()
        }
        runCatching {
            manager.requestGroupInfo(channel) { group ->
                if (finished || stopped || session != expectedSession) return@requestGroupInfo
                if (group == null) {
                    ready()
                } else {
                    manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = ready()
                        override fun onFailure(reason: Int) {
                            if (finished || session != expectedSession) return
                            finished = true
                            legacyJoinHandler.removeCallbacks(timeout)
                            onStatus("Could not release the previous Wi-Fi Direct group: ${connectFailure(reason)}")
                        }
                    })
                }
            }
        }.onFailure {
            finished = true
            legacyJoinHandler.removeCallbacks(timeout)
            onStatus("Could not reset Wi-Fi Direct. Tap Stop Wi-Fi Direct and retry.")
        }
    }

    /** Host a non-persistent group with QR-shareable credentials on Android 10+. */
    fun createGroup() {
        val manager = manager
        val channel = channel
        if (manager == null || channel == null) {
            onStatus("Wi-Fi Direct is not supported on this device.")
            return
        }
        if (!hasWifiDirectPermission()) {
            onStatus("Wi-Fi Direct needs nearby Wi-Fi permission.")
            return
        }

        if (!isLocationReady()) return
        stopped = false
        session++
        afterGroupReleased {
            hostingGroup = true
            // Leave home Wi-Fi and any Windows legacy link before hosting.
            onStatus("Preparing Wi-Fi Direct host...")

            val expectedSession = session
            val listener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (stopped || session != expectedSession) return
                    onStatus(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            "Wi-Fi Direct QR host ready. Scan it from the joining phone."
                        } else {
                            "Hosting a Wi-Fi Direct group. Ask the other phone to join."
                        }
                    )
                    refreshLinkInfo()
                }

                override fun onFailure(reason: Int) {
                    if (stopped || session != expectedSession) return
                    hostingGroup = false
                    onHostCredentials(null)
                    onStatus("Could not host a Wi-Fi Direct group: ${connectFailure(reason)}")
                }
            }

            runCatching {
                // Android's API 29+ predetermined-credentials flow supports creating
                // the group directly from the config. Do not wait on
                // stopPeerDiscovery(): some vendor Wi-Fi Direct stacks fail to
                // return that callback when discovery was never active.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val credentials = WifiDirectCredentials.generate()
                    val config = WifiP2pConfig.Builder()
                        .setNetworkName(credentials.networkName)
                        .setPassphrase(credentials.passphrase)
                        .enablePersistentMode(false)
                        .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                        .build()

                    // Show the QR immediately so the UI always responds to the tap.
                    // It is removed again if Android rejects the group request.
                    onHostCredentials(credentials)
                    manager.createGroup(channel, config, listener)
                } else {
                    onHostCredentials(null)
                    @Suppress("DEPRECATION")
                    manager.createGroup(channel, listener)
                }
            }.onFailure { error ->
                hostingGroup = false
                onHostCredentials(null)
                val detail = error.message?.takeIf { it.isNotBlank() }
                    ?: error.javaClass.simpleName
                onStatus("Could not start Wi-Fi Direct host: $detail")
            }
        }
    }

    /** Join a QR-created Wi-Fi Direct group without peer discovery (Android 10+). */
    fun connect(credentials: WifiDirectCredentials) {
        val manager = manager
        val channel = channel
        if (manager == null || channel == null) {
            onStatus("Wi-Fi Direct is not supported on this device.")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onStatus("Wi-Fi Direct QR pairing requires Android 10 or newer.")
            return
        }
        if (!hasWifiDirectPermission()) {
            onStatus("Wi-Fi Direct needs nearby Wi-Fi permission.")
            return
        }

        // Drop any stale legacy request and the current infrastructure
        // Wi-Fi so the group can form without the system prompt.
        // (The Windows branch below re-arms the legacy flag once its link
        // is actually up.)
        if (!isLocationReady()) return
        stopped = false
        session++
        afterGroupReleased {

            // The Windows app hosts a legacy AP, not a P2P group: join it through
            // a WifiNetworkSpecifier so the user never leaves LanDrop for the
            // system Wi-Fi settings.
            if (isWindowsHostSsid(credentials.networkName)) {
                joinWindowsAp(credentials)
                return@afterGroupReleased
            }

            hostingGroup = false
            onStatus("Joining Wi-Fi Direct group from QR...")
            val expectedSession = session

            runCatching {
                val config = WifiP2pConfig.Builder()
                    .setNetworkName(credentials.networkName)
                    .setPassphrase(credentials.passphrase)
                    .enablePersistentMode(false)
                    .build()

                // Predetermined-credentials connections can be initiated directly.
                manager.connect(
                    channel,
                    config,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            if (stopped || session != expectedSession) return
                            onStatus("Wi-Fi Direct QR accepted. Waiting for the link.")
                        }

                        override fun onFailure(reason: Int) {
                            if (stopped || session != expectedSession) return
                            onStatus("Wi-Fi Direct QR connect failed: ${connectFailure(reason)}")
                        }
                    }
                )
            }.onFailure { error ->
                val detail = error.message?.takeIf { it.isNotBlank() }
                    ?: error.javaClass.simpleName
                onStatus("Could not join Wi-Fi Direct from QR: $detail")
            }
        }
    }

    /**
     * Join a Windows-hosted direct network (legacy AP) without leaving the
     * app. Android 10+ allows this via a WifiNetworkSpecifier; the DHCP
     * gateway is the Windows PC, so the normal probe flow finds LanDrop and
     * the send page labels it Wi-Fi Direct.
     */
    fun joinWindowsAp(credentials: WifiDirectCredentials) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onStatus("Joining a Windows direct network in-app requires Android 10 or newer.")
            return
        }
        if (!hasWifiDirectPermission()) {
            onStatus("Wi-Fi Direct needs nearby Wi-Fi permission.")
            return
        }
        leaveLegacyNetwork()
        hostingGroup = false
        onStatus("Joining ${credentials.networkName}...")
        runCatching {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(credentials.networkName)
                .setWpa2Passphrase(credentials.passphrase)
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
            val connectivity =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivity == null) {
                onStatus("Could not join Windows direct network: no connectivity service.")
                return
            }
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    if (legacyNetworkCallback !== this) return
                    legacyJoinTimeout?.let(legacyJoinHandler::removeCallbacks)
                    legacyJoinTimeout = null
                    // A specifier network never becomes the default route: without
                    // this every socket (probe, discovery, file send) keeps going
                    // out the old LAN/mobile network where 192.168.137.1 does not
                    // exist, so Android->Windows fails while Windows->Android works.
                    if (!connectivity.bindProcessToNetwork(network)) {
                        finishLegacyJoin(this)
                        onStatus("Could not route traffic to the direct network. Stop Wi-Fi Direct and retry.")
                        return
                    }
                    val properties = connectivity.getLinkProperties(network)
                    val gateway = properties?.routes?.firstNotNullOfOrNull { route ->
                        (route.gateway as? java.net.Inet4Address)?.hostAddress?.takeUnless { it == "0.0.0.0" }
                    }
                    val localIp = properties?.linkAddresses?.firstNotNullOfOrNull {
                        (it.address as? java.net.Inet4Address)?.hostAddress
                    }
                    legacyActive = true
                    onStatus("Joined ${credentials.networkName}. Looking for LanDrop...")
                    onConnected(
                        WifiDirectLink(
                            groupOwnerAddress = gateway ?: WINDOWS_DIRECT_GATEWAY,
                            isGroupOwner = false,
                            localAddress = localIp
                        )
                    )
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    if (finishLegacyJoin(this)) {
                        onStatus("Could not join ${credentials.networkName}. Check the password and retry.")
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    if (finishLegacyJoin(this)) {
                        onStatus("Windows direct network lost.")
                        onDisconnected()
                    }
                }
            }
            legacyNetworkCallback = callback
            val timeout = Runnable {
                if (finishLegacyJoin(callback)) {
                    onStatus("Could not join ${credentials.networkName}: connection timed out. Retry the QR scan.")
                }
            }
            legacyJoinTimeout = timeout
            legacyJoinHandler.postDelayed(timeout, 35_000)
            connectivity.requestNetwork(request, callback, legacyJoinHandler)
        }.onFailure { error ->
            legacyNetworkCallback?.let(::finishLegacyJoin)
            val detail = error.message?.takeIf { it.isNotBlank() }
                ?: error.javaClass.simpleName
            onStatus("Could not join Windows direct network: $detail")
        }
    }

    /** Release a WifiNetworkSpecifier request made by [joinWindowsAp]. */
    fun leaveLegacyNetwork() {
        legacyNetworkCallback?.let(::finishLegacyJoin)
    }

    private fun finishLegacyJoin(callback: ConnectivityManager.NetworkCallback): Boolean {
        if (legacyNetworkCallback !== callback) return false
        legacyNetworkCallback = null
        legacyActive = false
        legacyJoinTimeout?.let(legacyJoinHandler::removeCallbacks)
        legacyJoinTimeout = null
        runCatching {
            val connectivity =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            runCatching { connectivity?.unregisterNetworkCallback(callback) }
            // Restore the default route so normal LAN sends work again.
            connectivity?.bindProcessToNetwork(null)
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun wifiManager(): WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /** Always available, including while a join or host request is stuck. */
    fun disconnect() {
        stopped = true
        resetting = false
        session++
        hostingGroup = false
        leaveLegacyNetwork()
        legacyJoinHandler.removeCallbacksAndMessages(null)
        onHostCredentials(null)
        onDisconnected()
        val manager = manager
        val channel = channel
        if (manager == null || channel == null || !hasWifiDirectPermission()) {
            onStatus("Wi-Fi Direct stopped in LanDrop. If the system link remains, turn Wi-Fi off and on.")
            return
        }
        stopDiscovery()
        runCatching { manager.cancelConnect(channel, null) }
        val expectedSession = session
        runCatching {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (session == expectedSession) onStatus("Wi-Fi Direct stopped.")
                }
                override fun onFailure(reason: Int) {
                    if (session == expectedSession) onStatus("LanDrop connection released. If Wi-Fi Direct is still stuck, turn Wi-Fi off and on.")
                }
            })
        }.onFailure {
            onStatus("LanDrop connection released. Turn Wi-Fi off and on if the system link remains.")
        }
    }

    fun refreshLinkInfo() {
        // P2P connection info does not exist on a Windows legacy link; the
        // gateway captured at join time is already the link.
        if (stopped || resetting || legacyNetworkCallback != null) return
        val manager = manager
        val channel = channel
        if (manager == null || channel == null || !hasWifiDirectPermission()) return
        runCatching { manager.requestConnectionInfo(channel, connectionListener) }
    }

    private fun isLocationReady(): Boolean {
        if (Build.VERSION.SDK_INT in 28..32) {
            val location = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            if (location?.isLocationEnabled != true) {
                onStatus("Could not start Wi-Fi Direct: turn on Location in Android settings, then retry the QR code.")
                return false
            }
        }
        return true
    }

    fun hasWifiDirectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun missingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                missing += Manifest.permission.NEARBY_WIFI_DEVICES
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                missing += Manifest.permission.ACCESS_FINE_LOCATION
            }
        }
        return missing
    }

    private fun connectFailure(reason: Int): String {
        return when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P is not supported on this device"
            WifiP2pManager.BUSY -> "busy (already connecting or in a group). Leave the group and retry"
            WifiP2pManager.ERROR -> "internal error, toggle Wi-Fi and retry"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "no service request (retry discovery first)"
            else -> "error $reason"
        }
    }

    private fun localP2pAddress(): String? {
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nic -> nic.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .mapNotNull { it.hostAddress }
                .firstOrNull { it.startsWith("192.168.49.") }
        }.getOrNull()
    }
}
