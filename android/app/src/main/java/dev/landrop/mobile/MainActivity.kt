package dev.landrop.mobile

import android.app.Activity
import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.LaptopWindows
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Unacked 1 MiB chunks allowed in flight per TCP stream (mirrors desktop). */
private const val CHUNK_ACK_WINDOW = 16

internal data class PendingChunkAck(
    val relativePath: String,
    val nextOffset: Long,
    val sequence: Long
)

/**
 * High-performance Wi-Fi lock held for the duration of file transfers.
 * Without it the radio naps between bursts (power save), which reads as
 * slow, sawtooth throughput with stalls — especially on Wi-Fi Direct
 * links. Reference-counted so parallel streams and a simultaneous
 * send+receive share one lock safely.
 */
internal object TransferWifiLock {
    private var lock: WifiManager.WifiLock? = null
    private var holders = 0

    @Synchronized
    fun acquire(context: Context) {
        try {
            if (lock == null) {
                val wifi = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                lock = wifi?.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "LanDrop:transfer"
                )?.apply { setReferenceCounted(false) }
            }
            if (lock != null) {
                if (holders == 0) runCatching { lock?.acquire() }
                holders += 1
            }
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun release() {
        try {
            if (holders > 0) {
                holders -= 1
                if (holders == 0) runCatching { lock?.release() }
            }
        } catch (_: Throwable) {
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(7, 16, 18)
        window.navigationBarColor = android.graphics.Color.rgb(16, 27, 30)
        setContent {
            LanDropRoot()
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Receive("Receive", Icons.Rounded.Download),
    Send("Send", Icons.Rounded.Send),
    Settings("Settings", Icons.Rounded.Settings)
}

private data class ManualPeer(
    val name: String,
    val ip: String,
    val port: Int,
    val availability: String,
    val requiresPairCode: Boolean,
    val deviceKind: String = "unknown",
    val remote: Boolean = false,
    val endpointLabel: String? = null,
    val deviceId: String = ""
)

private data class VpnSavedPeer(
    val name: String,
    val host: String,
    val port: Int = 50501,
    val deviceKind: String = "desktop",
    val deviceId: String = "",
    val secure: Boolean = false,
    val lastSeenMs: Long = System.currentTimeMillis()
)

private data class PickedItem(
    val name: String,
    val kind: String,
    val uri: String,
    val sizeBytes: Long? = null
)

private data class ReceiveHistoryItem(
    val title: String,
    val detail: String,
    val timestampMs: Long = System.currentTimeMillis()
)

private data class SendHistoryItem(
    val title: String,
    val detail: String,
    val timestampMs: Long = System.currentTimeMillis()
)

private enum class SendPhase { Preparing, WaitingForAcceptance, Transferring, Canceling }

private data class SendProgress(
    val peerName: String,
    val currentFile: String,
    val completedBytes: Long,
    val totalBytes: Long,
    val speedMbps: Double,
    val active: Boolean = true,
    val fileCount: Int = 0,
    val currentFiles: List<String> = listOf(currentFile),
    val phase: SendPhase = SendPhase.Transferring
)

private enum class QuickSaveMode {
    Off,
    Favorites,
    On
}

private enum class ThemeMode {
    Dark,
    Oled,
    Light
}

private data class TransferFile(
    val relativePath: String,
    val uri: Uri,
    val sizeBytes: Long,
    val modifiedAtMs: Long?
)

private data class TransferDirectory(
    val relativePath: String,
    val modifiedAtMs: Long?
)

private data class AndroidParallelSendSession(
    val context: Context,
    val peer: ManualPeer,
    val transferId: String,
    val files: List<TransferFile>,
    val offsets: Map<String, Long>,
    val totalBytes: Long,
    val completedBytes: AtomicLong,
    val activeFiles: ConcurrentHashMap<Int, String>,
    val cancelFlag: AtomicBoolean,
    val pauseFlag: AtomicBoolean,
    val onStatus: (String) -> Unit,
    val onProgress: (SendProgress?) -> Unit,
    val onPeerPaused: () -> Unit,
    val onPeerResumed: () -> Unit,
    val sockets: ConcurrentLinkedQueue<Socket>? = null
)

@Composable
private fun LanDropRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("landrop_android", Context.MODE_PRIVATE) }
    val appTranslations = remember { loadDariTranslations(context) }
    var appLanguage by remember {
        mutableStateOf(
            runCatching {
                AppLanguage.valueOf(
                    prefs.getString("app_language", AppLanguage.English.name)
                        ?: AppLanguage.English.name
                )
            }.getOrDefault(AppLanguage.English)
        )
    }
    var themeMode by remember {
        mutableStateOf(
            runCatching {
                ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.Dark.name) ?: ThemeMode.Dark.name)
            }.getOrDefault(ThemeMode.Dark)
        )
    }
    DisposableEffect(themeMode) {
        val activity = context as? Activity
        when (themeMode) {
            ThemeMode.Dark -> {
                activity?.window?.statusBarColor = android.graphics.Color.rgb(7, 16, 18)
                activity?.window?.navigationBarColor = android.graphics.Color.rgb(16, 27, 30)
            }
            ThemeMode.Oled -> {
                activity?.window?.statusBarColor = android.graphics.Color.BLACK
                activity?.window?.navigationBarColor = android.graphics.Color.BLACK
            }
            ThemeMode.Light -> {
                activity?.window?.statusBarColor = android.graphics.Color.rgb(246, 251, 250)
                activity?.window?.navigationBarColor = android.graphics.Color.WHITE
            }
        }
        onDispose { }
    }
    CompositionLocalProvider(
        LocalAppLanguage provides appLanguage,
        LocalAppTranslations provides appTranslations,
        LocalLayoutDirection provides if (appLanguage == AppLanguage.Dari) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
    ) {
        LanDropTheme(themeMode = themeMode) {
            LanDropApp(
                themeMode = themeMode,
                appLanguage = appLanguage,
                onThemeModeChange = { mode ->
                    themeMode = mode
                    prefs.edit().putString("theme_mode", mode.name).apply()
                },
                onAppLanguageChange = { language ->
                    appLanguage = language
                    prefs.edit().putString("app_language", language.name).apply()
                }
            )
        }
    }
}

@Composable
private fun LanDropTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val colors = when (themeMode) {
        ThemeMode.Dark -> darkColorScheme(
            background = Color(0xFF071012),
            surface = Color(0xFF101B1E),
            surfaceVariant = Color(0xFF18272B),
            primary = Color(0xFF7DE4D7),
            secondary = Color(0xFF96B7FF),
            onBackground = Color(0xFFEAF6F3),
            onSurface = Color(0xFFEAF6F3),
            onSurfaceVariant = Color(0xFFA8BCBA),
            onPrimary = Color(0xFF021112)
        )
        ThemeMode.Oled -> darkColorScheme(
            background = Color.Black,
            surface = Color(0xFF080A0B),
            surfaceVariant = Color(0xFF121719),
            primary = Color(0xFF7DE4D7),
            secondary = Color(0xFF96B7FF),
            onBackground = Color(0xFFEAF6F3),
            onSurface = Color(0xFFEAF6F3),
            onSurfaceVariant = Color(0xFFA8BCBA),
            onPrimary = Color(0xFF021112)
        )
        ThemeMode.Light -> lightColorScheme(
            background = Color(0xFFF6FBFA),
            surface = Color.White,
            surfaceVariant = Color(0xFFE3EEEC),
            primary = Color(0xFF006B61),
            secondary = Color(0xFF365F9A),
            onBackground = Color(0xFF10201D),
            onSurface = Color(0xFF10201D),
            onSurfaceVariant = Color(0xFF526663),
            onPrimary = Color.White
        )
    }

    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LanDropApp(
    themeMode: ThemeMode,
    appLanguage: AppLanguage,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit
) {
    var selectedTab by remember { mutableStateOf(Tab.Send) }
    var receiveEnabled by remember { mutableStateOf(true) }
    var pairCodeEnabled by remember { mutableStateOf(false) }
    var pairCode by remember { mutableStateOf("") }
    var manualIp by remember { mutableStateOf("") }
    var manualPeer by remember { mutableStateOf<ManualPeer?>(null) }
    var manualConnecting by remember { mutableStateOf(false) }
    var remotePeer by remember { mutableStateOf<ManualPeer?>(null) }
    var remoteConnecting by remember { mutableStateOf(false) }
    var remoteConnectionMessage by remember {
        mutableStateOf("Scan the QR code shown by the receiving computer.")
    }
    var outboundPairCode by remember { mutableStateOf("") }
    var pickedItems by remember { mutableStateOf<List<PickedItem>>(emptyList()) }
    var discoveredPeers by remember { mutableStateOf<List<DiscoveredPeer>>(emptyList()) }
    var knownPeerIps by remember { mutableStateOf<Set<String>>(emptySet()) }
    var trustedDeviceIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var vpnPeers by remember { mutableStateOf<List<VpnSavedPeer>>(emptyList()) }
    var trustedPrefsLoaded by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var showLogs by remember { mutableStateOf(false) }
    var pendingIncomingOffer by remember { mutableStateOf<IncomingOfferPrompt?>(null) }
    var pendingIncomingDecision by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var receiveProgress by remember { mutableStateOf<ReceiveProgress?>(null) }
    var receiveAutoNavigationUsed by remember { mutableStateOf(false) }
    var sendProgress by remember { mutableStateOf<SendProgress?>(null) }
    var sendInFlight by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var receiveError by remember { mutableStateOf<String?>(null) }
    var receiveFolderError by remember { mutableStateOf<String?>(null) }
    var sendPeerPaused by remember { mutableStateOf(false) }
    val sendCancelFlag = remember { AtomicBoolean(false) }
    val sendPauseFlag = remember { AtomicBoolean(false) }
    val sendControlWriteLock = remember { Any() }
    val sendControlOutput = remember { AtomicReference<DataOutputStream?>(null) }
    val sendControlTransferId = remember { AtomicReference<String?>(null) }
    // Open send sockets (control + parallel workers). Closed on cancel so a
    // thread blocked in readInt/readFully wakes immediately instead of hanging
    // until the 300s socket timeout with the UI stuck on "sending".
    val activeSendSockets = remember { ConcurrentLinkedQueue<Socket>() }
    fun closeActiveSendSockets() {
        while (true) {
            val socket = activeSendSockets.poll() ?: break
            runCatching { socket.close() }
        }
    }
    var sendRefreshing by remember { mutableStateOf(false) }
    var wifiDirectStatus by remember { mutableStateOf("Not started") }
    var wifiDirectLink by remember { mutableStateOf<WifiDirectLink?>(null) }
    var wifiDirectHostCredentials by remember { mutableStateOf<WifiDirectCredentials?>(null) }
    var wifiDirectProbing by remember { mutableStateOf(false) }
    var wifiDirectJoining by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()
    val receiveListState = rememberLazyListState()
    val sendListState = rememberLazyListState()
    val settingsListState = rememberLazyListState()
    var receiveHistory by remember { mutableStateOf<List<ReceiveHistoryItem>>(emptyList()) }
    var sendHistory by remember { mutableStateOf<List<SendHistoryItem>>(emptyList()) }
    var historyLoaded by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf("Enter a PC IP address or refresh nearby devices.")
    }
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("landrop_android", Context.MODE_PRIVATE)
    }
    var receiveSaveTreeUri by remember {
        mutableStateOf(
            prefs.getString("receive_save_tree_uri", null)?.let(Uri::parse)
                ?.takeIf { receiveFolderProblem(context, it) == null }
        )
    }
    val remoteTunnel = remember { AndroidRemoteTunnel() }
    var storedRemoteInvite by remember {
        mutableStateOf(AndroidRemoteInviteStore.load(prefs))
    }
    val localDeviceId = remember {
        prefs.getString("device_id", null) ?: "android-${UUID.randomUUID()}".also {
            prefs.edit().putString("device_id", it).apply()
        }
    }
    var networkDetails by remember {
        mutableStateOf(NetworkDetails.activeNetworkDetails(context))
    }
    var localDeviceName by remember {
        mutableStateOf(
            prefs.getString("device_name", null)
                ?: Build.MODEL.takeIf { it.isNotBlank() }
                ?: "This phone"
        )
    }
    var onboardingComplete by remember {
        mutableStateOf(prefs.getBoolean("onboarding_complete", false))
    }
    var quickSaveMode by remember {
        mutableStateOf(
            runCatching {
                QuickSaveMode.valueOf(prefs.getString("quick_save_mode", QuickSaveMode.Off.name) ?: QuickSaveMode.Off.name)
            }.getOrDefault(QuickSaveMode.Off)
        )
    }
    var concurrentFileStreams by remember {
        mutableStateOf(prefs.getInt("concurrent_file_streams", 4).coerceIn(1, 100))
    }
    if (pairCode.isBlank()) {
        pairCode = prefs.getString("pair_code", null) ?: generatePairCode().also {
            prefs.edit().putString("pair_code", it).apply()
        }
    }
    if (!pairCodeEnabled) {
        pairCodeEnabled = prefs.getBoolean("pair_code_enabled", false)
    }
    if (knownPeerIps.isEmpty()) {
        knownPeerIps = prefs.getStringSet("known_peer_ips", emptySet()) ?: emptySet()
    }
    if (!historyLoaded) {
        receiveHistory = loadReceiveHistory(prefs)
        sendHistory = loadSendHistory(prefs)
        historyLoaded = true
    }
    if (!trustedPrefsLoaded) {
        trustedDeviceIds = loadTrustedDeviceIds(prefs)
        vpnPeers = loadVpnPeers(prefs)
        trustedPrefsLoaded = true
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val probeExecutor = remember { Executors.newSingleThreadExecutor() }
    fun connectRemoteInvite(encodedInvite: String, persist: Boolean) {
        if (remoteConnecting) return
        val trimmed = encodedInvite.trim()
        if (trimmed.isEmpty()) {
            status = "Paste a LanDrop remote invite first."
            return
        }
        remoteConnecting = true
        status = "Opening secure remote connection..."
        remoteConnectionMessage = "Checking the secure endpoint..."
        var overlayHostSnapshot = ""
        probeExecutor.execute {
            val result = runCatching {
                val invite = parseRemoteInvite(trimmed)
                overlayHostSnapshot = invite.host.trim()
                val localPort = remoteTunnel.start(trimmed)
                when (val probe = parseProbeResult(RustBridge.probePeer("127.0.0.1", localPort))) {
                    is ProbeResult.Success -> ProbeResult.Success(
                        probe.peer.copy(
                            ip = "127.0.0.1",
                            port = localPort,
                            remote = true,
                            endpointLabel = "${invite.host}:${invite.port}"
                        )
                    )
                    is ProbeResult.Failure -> {
                        remoteTunnel.stop()
                        probe
                    }
                }
            }.getOrElse { error ->
                remoteTunnel.stop()
                ProbeResult.Failure(error.message ?: "Secure remote connection failed.")
            }
            mainHandler.post {
                remoteConnecting = false
                when (result) {
                    is ProbeResult.Success -> {
                        remotePeer = result.peer
                        manualPeer = result.peer
                        manualIp = ""
                        // Trust the overlay endpoint (Tailscale 100.x, MagicDNS,
                        // or any VPN hostname) so Favorites quick-save and the
                        // trusted list keep working. Never trust loopback: that
                        // is the local tunnel endpoint, not the real peer.
                        val overlayHost = overlayHostSnapshot.trim()
                        if (overlayHost.isNotBlank() && !isLoopbackHost(overlayHost)) {
                            knownPeerIps = knownPeerIps + overlayHost
                            prefs.edit().putStringSet("known_peer_ips", knownPeerIps).apply()
                            AppLog.add("Trusted overlay endpoint $overlayHost for ${result.peer.name}")
                            vpnPeers = upsertVpnPeer(
                                prefs, vpnPeers,
                                VpnSavedPeer(
                                    name = result.peer.name,
                                    host = overlayHost,
                                    port = result.peer.endpointLabel?.substringAfter(':')?.toIntOrNull() ?: 50502,
                                    deviceKind = result.peer.deviceKind,
                                    deviceId = result.peer.deviceId,
                                    secure = true
                                )
                            )
                        }
                        if (result.peer.deviceId.isNotBlank()) {
                            trustedDeviceIds = trustedDeviceIds + result.peer.deviceId
                            saveTrustedDeviceIds(prefs, trustedDeviceIds)
                        }
                        var storedSuccessfully = false
                        if (persist) {
                            runCatching {
                                AndroidRemoteInviteStore.save(prefs, trimmed)
                            }.onSuccess {
                                storedSuccessfully = true
                                storedRemoteInvite = trimmed
                            }.onFailure { error ->
                                AppLog.add("Remote invite could not be stored securely: ${error.message}")
                                status =
                                    "Connected to ${result.peer.name}, but Android could not remember the invite securely."
                            }
                        }
                        if (!persist || storedSuccessfully) {
                            status = "Securely connected to ${result.peer.name}."
                        }
                        remoteConnectionMessage =
                            "Connected securely to ${result.peer.name} at ${result.peer.endpointLabel}."
                    }
                    is ProbeResult.Failure -> {
                        remotePeer = null
                        status = result.message
                        remoteConnectionMessage = result.message
                    }
                }
            }
        }
    }
    DisposableEffect(remoteTunnel) {
        onDispose {
            remoteTunnel.close()
            probeExecutor.shutdownNow()
        }
    }
    LaunchedEffect(storedRemoteInvite) {
        val invite = storedRemoteInvite
        if (!invite.isNullOrBlank() && remotePeer == null && !remoteConnecting) {
            connectRemoteInvite(invite, persist = false)
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val uris = if (result.resultCode == Activity.RESULT_OK && data != null) {
            extractPickedUris(data)
        } else {
            emptyList()
        }
        if (uris.isNotEmpty()) {
            val grantedFlags = data?.flags ?: 0
            val nextItems = uris.map { uri ->
                AppLog.add("Selected file URI: $uri flags=$grantedFlags")
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }.onFailure { error ->
                    AppLog.add("Could not retain selected file access: ${error.message}")
                }
                val picked = describePickedUri(context, uri)
                PickedItem(
                    name = picked.first,
                    kind = "File",
                    uri = uri.toString(),
                    sizeBytes = picked.second
                )
            }
            pickedItems = (pickedItems + nextItems)
                .distinctBy { "${it.kind}:${it.uri}" }
            status = "${pickedItems.size} file(s) selected."
        }
    }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            pickedItems = (pickedItems + PickedItem(
                name = describeTreeUri(it),
                kind = "Folder",
                uri = it.toString(),
                sizeBytes = null
            )).distinctBy { item -> "${item.kind}:${item.uri}" }
            status = "Folder selected."
        }
    }
    val receiveFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val problem = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                receiveFolderProblem(context, it)
            }.getOrElse { "Could not keep access to this folder. Choose it again." }
            receiveFolderError = problem
            if (problem == null) {
                receiveSaveTreeUri = it
                prefs.edit().putString("receive_save_tree_uri", it.toString()).apply()
                status = "Receive folder selected."
            } else {
                status = problem
                if (onboardingComplete && pendingIncomingOffer == null) receiveError = problem
            }
        }
    }
    val receiver = remember(localDeviceName) {
        AndroidReceiver(
            context = context.applicationContext,
            deviceId = localDeviceId,
            deviceName = localDeviceName,
            saveTreeUriProvider = { receiveSaveTreeUri },
            receiveEnabledProvider = { receiveEnabled && onboardingComplete },
            concurrentFileStreamsProvider = { concurrentFileStreams },
            pairCodeRequiredProvider = { pairCodeEnabled },
            pairCodeProvider = { pairCode },
            requestApproval = { offer ->
                // Favorites trusts by deviceId first (stable across VPN IP
                // changes, MagicDNS vs 100.x), then by IP. This mirrors the
                // desktop which trusts device_id. QuickSave On auto-accepts
                // everything, including Tailscale/VPN and secure remote.
                val trustedIdsSnapshot = runCatching {
                    prefs.getStringSet("trusted_device_ids", emptySet()) ?: emptySet()
                }.getOrDefault(emptySet())
                val knownIpsSnapshot = runCatching {
                    prefs.getStringSet("known_peer_ips", emptySet()) ?: emptySet()
                }.getOrDefault(emptySet()) + discoveredPeers.map { it.ip } +
                    listOfNotNull(manualPeer?.ip, remotePeer?.endpointLabel?.substringBefore(':')) +
                    vpnPeers.map { it.host } + knownPeerIps
                val isTrustedDevice = offer.senderDeviceId.isNotBlank() && offer.senderDeviceId in trustedIdsSnapshot
                val isTrustedIp = offer.senderIp in knownIpsSnapshot
                // Loopback here means the sender came through a local tunnel
                // relay; trust follows the active secure session instead.
                val isTrustedTunnel = (offer.senderIp == "127.0.0.1" || offer.senderIp == "::1") &&
                    remotePeer != null
                val shouldAsk = when (quickSaveMode) {
                    QuickSaveMode.Off -> true
                    QuickSaveMode.Favorites -> !(isTrustedDevice || isTrustedIp || isTrustedTunnel)
                    QuickSaveMode.On -> false
                }
                fun rememberSenderAsTrusted() {
                    // Persist immediately (thread-safe); mirror into Compose
                    // state on the main thread for UI.
                    if (offer.senderDeviceId.isNotBlank()) {
                        val next = (loadTrustedDeviceIds(prefs) + offer.senderDeviceId)
                        saveTrustedDeviceIds(prefs, next)
                        mainHandler.post { trustedDeviceIds = next }
                    }
                    if (offer.senderIp.isNotBlank() && !isLoopbackHost(offer.senderIp)) {
                        val nextIps = (prefs.getStringSet("known_peer_ips", emptySet()) ?: emptySet()) + offer.senderIp
                        prefs.edit().putStringSet("known_peer_ips", nextIps).apply()
                        mainHandler.post { knownPeerIps = knownPeerIps + offer.senderIp }
                    }
                    if (offer.senderIp.isNotBlank() && isVpnOrTailscaleIp(offer.senderIp)) {
                        val vpnPeer = VpnSavedPeer(
                            name = offer.senderName.ifBlank { "VPN device" },
                            host = offer.senderIp,
                            deviceId = offer.senderDeviceId,
                            lastSeenMs = System.currentTimeMillis()
                        )
                        val next = upsertVpnPeer(prefs, loadVpnPeers(prefs), vpnPeer)
                        mainHandler.post { vpnPeers = next }
                    }
                }
                val approval = TransferApproval()
                if (!shouldAsk && receiveFolderProblem(context, receiveSaveTreeUri) == null) {
                    AppLog.add("Quick Save ${quickSaveMode.name}: auto-accepted ${offer.senderName} (${offer.senderIp})")
                    rememberSenderAsTrusted()
                    approval.decide(true)
                } else {
                    mainHandler.post {
                        if (approval.decision() != null) return@post
                        if (!receiveAutoNavigationUsed) {
                            selectedTab = Tab.Receive
                            receiveAutoNavigationUsed = true
                        }
                        status = "${offer.senderName} wants to send files."
                        AppLog.add("Showing incoming offer prompt for ${offer.senderName} (${offer.senderIp})")
                        pendingIncomingOffer = offer
                        pendingIncomingDecision = decision@ { accepted ->
                            AppLog.add("Incoming offer prompt answered: $accepted")
                            if (accepted && receiveSaveTreeUri == null) return@decision
                            if (!approval.decide(accepted)) return@decision
                            pendingIncomingOffer = null
                            pendingIncomingDecision = null
                            if (!accepted) {
                                receiveAutoNavigationUsed = false
                            }
                            if (accepted) rememberSenderAsTrusted()
                        }
                    }
                }
                approval
            },
            onApprovalClosed = { transferId ->
                mainHandler.post {
                    if (pendingIncomingOffer?.transferId == transferId) {
                        pendingIncomingOffer = null
                        pendingIncomingDecision = null
                        receiveAutoNavigationUsed = false
                    }
                }
            },
            onSaveFolderUnavailable = { previousUri ->
                mainHandler.post {
                    if (receiveSaveTreeUri == previousUri) {
                        receiveSaveTreeUri = null
                        prefs.edit().remove("receive_save_tree_uri").apply()
                    }
                }
            },
            onError = { message ->
                mainHandler.post { receiveError = message }
            },
            onProgress = { progress ->
                mainHandler.post {
                    if (progress != null && !receiveAutoNavigationUsed) {
                        selectedTab = Tab.Receive
                        receiveAutoNavigationUsed = true
                    }
                    receiveProgress = progress
                    if (progress == null) {
                        receiveAutoNavigationUsed = false
                    }
                }
            },
            onStatus = { message ->
                mainHandler.post {
                    status = message
                    if (message.startsWith("Received files from")) {
                        receiveHistory = listOf(
                            ReceiveHistoryItem(
                                title = message,
                                detail = receiveSaveTreeUri?.let { describeTreeUri(it) } ?: "Android selected folder"
                            )
                        ) + receiveHistory
                        saveReceiveHistory(prefs, receiveHistory)
                    }
                }
            }
        )
    }
    val discovery = remember(localDeviceName) {
        AndroidDiscovery(
            context = context.applicationContext,
            deviceId = localDeviceId,
            deviceName = localDeviceName,
            receiveEnabledProvider = { receiveEnabled && onboardingComplete },
            pairCodeRequiredProvider = { pairCodeEnabled },
            onPeer = { peer ->
                mainHandler.post {
                    discoveredPeers = (discoveredPeers.filterNot {
                        it.ip == peer.ip && it.port == peer.port
                    } + peer).takeLast(20)
                    status = "Found ${peer.name}."
                }
            },
            onStatus = { message -> mainHandler.post { status = message } }
        )
    }
    val wifiDirectProbeGeneration = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    fun probeWifiDirectLink(link: WifiDirectLink) {
        if (wifiDirectProbing) return
        wifiDirectProbing = true
        val probeGeneration = wifiDirectProbeGeneration.incrementAndGet()
        wifiDirectStatus = if (link.isGroupOwner) {
            "Hosting Wi-Fi Direct group at ${link.groupOwnerAddress}. Looking for LanDrop on joined phones."
        } else {
            "Wi-Fi Direct linked at ${link.groupOwnerAddress}. Looking for LanDrop."
        }
        status = wifiDirectStatus
        val p2pSnapshotIps = discoveredPeers.filter {
            it.ip.startsWith("192.168.49.") || it.ip.startsWith("192.168.137.")
        }.map { it.ip }
        val selfNameSnapshot = localDeviceName
        probeExecutor.execute {
            // DHCP needs a moment after group formation.
            Thread.sleep(2500)
            val candidates = link.candidateIps().take(24)
            AppLog.add("Wi-Fi Direct probing LanDrop on ${candidates.joinToString()}")
            var found: ManualPeer? = null
            // Probe sequentially with short timeouts via Rust bridge (5s TCP
            // connect inside). Sequential keeps logs readable; candidates are
            // already ordered by likelihood.
            for (candidate in candidates) {
                if (wifiDirectProbeGeneration.get() != probeGeneration) return@execute
                val result = runCatching {
                    parseProbeResult(RustBridge.probePeer(candidate, 50501))
                }.getOrNull() ?: continue
                if (result is ProbeResult.Success) {
                    if (result.peer.ip.isBlank()) continue
                    AppLog.add("Wi-Fi Direct probe hit ${result.peer.name} at $candidate")
                    // Heuristic: if the probed name matches our own device name
                    // AND the IP equals our local p2p address, it is a loopback
                    // to self. The Rust bridge does not return deviceId, so use
                    // IP equality as the guard (candidateIps already excludes
                    // localAddress, but double-check here).
                    if (candidate == link.localAddress && result.peer.name == selfNameSnapshot) {
                        continue
                    }
                    found = result.peer.copy(deviceKind = result.peer.deviceKind.ifBlank { "android" })
                    break
                }
            }
            // Fallback: UDP discovery over the new p2p interface may have found
            // the peer while TCP probing ran.
            if (found == null) {
                val fallbackIp = p2pSnapshotIps.firstOrNull { ip -> candidates.none { it == ip } }
                if (fallbackIp != null) {
                    val result = runCatching {
                        parseProbeResult(RustBridge.probePeer(fallbackIp, 50501))
                    }.getOrNull()
                    if (result is ProbeResult.Success) {
                        found = result.peer
                    }
                }
            }
            val matched = found
            mainHandler.post {
                if (wifiDirectProbeGeneration.get() != probeGeneration) return@post
                wifiDirectProbing = false
                if (matched != null) {
                    manualPeer = matched
                    manualIp = matched.ip
                    val directPeer = DiscoveredPeer(
                        name = matched.name,
                        ip = matched.ip,
                        port = matched.port,
                        availability = matched.availability,
                        requiresPairCode = matched.requiresPairCode,
                        deviceKind = matched.deviceKind.ifBlank { "android" },
                        deviceId = matched.deviceId,
                        adapterKind = "wifi_direct",
                        lastSeenMs = System.currentTimeMillis()
                    )
                    discoveredPeers = (
                        discoveredPeers.filterNot { peer ->
                            (directPeer.deviceId.isNotBlank() && peer.deviceId == directPeer.deviceId) ||
                                (peer.ip == directPeer.ip && peer.port == directPeer.port)
                        } + directPeer
                    ).takeLast(20)
                    knownPeerIps = knownPeerIps + matched.ip
                    prefs.edit().putStringSet("known_peer_ips", knownPeerIps).apply()
                    discovery.probeNow()
                    selectedTab = Tab.Send
                    val role = if (link.isGroupOwner) "hosting" else "joined"
                    status = "Wi-Fi Direct ready ($role): ${matched.name} at ${matched.ip}."
                    wifiDirectStatus = status
                    AppLog.add("Wi-Fi Direct LanDrop peer: ${matched.name} at ${matched.ip}")
                } else {
                    // Keep the link (group is still usable) but tell the user to
                    // keep LanDrop open on the other phone.
                    manualIp = link.groupOwnerAddress
                    val hint = if (link.isGroupOwner) {
                        "Hosting at ${link.groupOwnerAddress}. Keep LanDrop open on the joining device, then tap Refresh."
                    } else {
                        "Linked to ${link.groupOwnerAddress} but no LanDrop answered on the direct link yet. Keep LanDrop open on the other device."
                    }
                    status = hint
                    wifiDirectStatus = hint
                    AppLog.add("Wi-Fi Direct link up but LanDrop TCP probe found nothing.")
                }
            }
        }
    }
    val wifiDirectTransportHolder = remember { AtomicReference<WifiDirectTransport?>(null) }
    val wifiDirectTransport = remember {
        WifiDirectTransport(
            context = context.applicationContext,
            onStatus = { message ->
                mainHandler.post {
                    wifiDirectStatus = message
                    status = message
                    // A failed join must release the "Joining..." indicator.
                    // onConnected/onDisconnected cover the success/teardown
                    // paths; only failure texts land here.
                    if (message.startsWith("Could not") ||
                        message.startsWith("Wi-Fi Direct QR connect failed") ||
                        message.contains("requires Android") ||
                        message.contains("not supported") ||
                        message.contains("needs nearby")
                    ) {
                        wifiDirectJoining = false
                    }
                    AppLog.add(message)
                }
            },
            onConnected = { link ->
                mainHandler.post {
                    val previousLink = wifiDirectLink
                    wifiDirectLink = link
                    wifiDirectJoining = false
                    // The UDP socket may still be bound to the old network;
                    // rebind so discovery hello/query go out the direct link.
                    discovery.restart()
                    // A fresh DHCP lease may arrive after the first callback;
                    // refresh once more shortly after so candidate IPs settle.
                    discovery.probeNow()
                    if (previousLink != link) {
                        mainHandler.postDelayed({
                            if (wifiDirectLink != null) wifiDirectTransportHolder.get()?.refreshLinkInfo()
                        }, 4000)
                        probeWifiDirectLink(link)
                    }
                }
            },
            onHostCredentials = { credentials ->
                mainHandler.post {
                    wifiDirectHostCredentials = credentials
                }
            },
            onDisconnected = {
                mainHandler.post {
                    wifiDirectProbeGeneration.incrementAndGet()
                    val wasLink = wifiDirectLink
                    wifiDirectLink = null
                    wifiDirectHostCredentials = null
                    wifiDirectProbing = false
                    wifiDirectJoining = false
                    discoveredPeers = discoveredPeers.filterNot { peer ->
                        peer.adapterKind.equals("wifi_direct", ignoreCase = true) ||
                            peer.ip.startsWith("192.168.49.") ||
                            peer.ip.startsWith("192.168.137.")
                    }
                    // Clear an auto-selected Wi-Fi Direct peer so a stale
                    // direct-link address is not reused after the group ends.
                    val current = manualPeer
                    if (current != null && (current.ip.startsWith("192.168.49.") || current.ip.startsWith("192.168.137."))) {
                        manualPeer = null
                        if (manualIp.startsWith("192.168.49.") || manualIp.startsWith("192.168.137.")) manualIp = ""
                    }
                    if (wasLink != null) {
                        status = "Wi-Fi Direct disconnected."
                        wifiDirectStatus = status
                        // Rebind discovery to the restored default network.
                        discovery.restart()
                    }
                }
            }
        ).also { wifiDirectTransportHolder.set(it) }
    }
    var pendingWifiDirectAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val wifiDirectPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val action = pendingWifiDirectAction
        pendingWifiDirectAction = null
        if (wifiDirectTransport.hasWifiDirectPermission()) {
            action?.invoke()
        } else {
            status = "Wi-Fi Direct permission was denied."
            wifiDirectStatus = status
            wifiDirectJoining = false
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            val cutoff = System.currentTimeMillis() - 18_000
            val freshPeers = discoveredPeers.filter { peer ->
                peer.lastSeenMs >= cutoff ||
                    (wifiDirectLink != null &&
                        (peer.adapterKind.equals("wifi_direct", ignoreCase = true) ||
                            peer.ip.startsWith("192.168.49.") ||
                            peer.ip.startsWith("192.168.137.")))
            }
            if (freshPeers.size != discoveredPeers.size) {
                val staleIps = discoveredPeers
                    .filter { it.lastSeenMs < cutoff }
                    .map { it.ip }
                    .toSet()
                discoveredPeers = freshPeers
                // Do not evict an active Wi-Fi Direct peer just because UDP
                // discovery went quiet; the p2p group is the source of truth.
                val wfdActive = wifiDirectLink != null &&
                    (manualPeer?.ip?.startsWith("192.168.49.") == true ||
                        manualPeer?.ip?.startsWith("192.168.137.") == true)
                if (manualPeer?.ip in staleIps && !wfdActive) {
                    manualPeer = null
                }
                status = if (freshPeers.isEmpty()) {
                    "Looking for nearby devices."
                } else {
                    "${freshPeers.size} device(s) nearby."
                }
            }
        }
    }
    DisposableEffect(receiver, discovery) {
        receiver.start()
        discovery.start()
        wifiDirectTransport.start()
        onDispose {
            wifiDirectTransport.stop()
            discovery.stop()
            receiver.stop()
        }
    }

    if (!onboardingComplete) {
        OnboardingScreen(
            saveFolderName = receiveSaveTreeUri?.let(::describeTreeUri),
            folderError = receiveFolderError,
            onChooseSaveFolder = { receiveFolderPicker.launch(receiveSaveTreeUri) },
            onComplete = {
                if (receiveSaveTreeUri != null) {
                    onboardingComplete = true
                    prefs.edit().putBoolean("onboarding_complete", true).apply()
                }
            }
        )
        return
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        pendingIncomingOffer?.let { offer ->
            IncomingOfferDialog(
                offer = offer,
                saveFolderName = receiveSaveTreeUri?.let(::describeTreeUri),
                folderError = receiveFolderError,
                onChooseSaveFolder = { receiveFolderPicker.launch(receiveSaveTreeUri) },
                onAccept = { pendingIncomingDecision?.invoke(true) },
                onReject = { pendingIncomingDecision?.invoke(false) }
            )
        }
        sendError?.let { message ->
            AlertDialog(
                onDismissRequest = { sendError = null },
                title = { Text("Could not send files") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { sendError = null }) { Text("OK") }
                }
            )
        }
        receiveError?.let { message ->
            AlertDialog(
                onDismissRequest = { receiveError = null },
                title = { Text("Could not receive files") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { receiveError = null }) { Text("OK") }
                }
            )
        }
        PullToRefreshBox(
            isRefreshing = sendRefreshing,
            onRefresh = {
                if (selectedTab == Tab.Send && !sendRefreshing) {
                    sendRefreshing = true
                    networkDetails = NetworkDetails.activeNetworkDetails(context)
                    status = "Searching the local network."
                    discovery.probeNow()
                    mainHandler.postDelayed({ sendRefreshing = false }, 2200)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = pullToRefreshState,
            enabled = selectedTab == Tab.Send,
            indicator = {
                if (selectedTab == Tab.Send) {
                    PullToRefreshDefaults.LoadingIndicator(
                        state = pullToRefreshState,
                        isRefreshing = sendRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            }
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = when (selectedTab) {
                    Tab.Receive -> receiveListState
                    Tab.Send -> sendListState
                    Tab.Settings -> settingsListState
                },
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (selectedTab) {
                Tab.Receive -> {
                    item {
                        ReceiveHomeScreen(
                            deviceName = localDeviceName,
                            centerInitial = deviceInitial(manualPeer?.name ?: localDeviceName),
                            receiveEnabled = receiveEnabled,
                            pairCodeEnabled = pairCodeEnabled,
                            pairCode = pairCode,
                            saveFolderName = receiveSaveTreeUri?.let { describeTreeUri(it) },
                            quickSaveMode = quickSaveMode,
                            receiveProgress = receiveProgress,
                            pendingIncomingOffer = pendingIncomingOffer,
                            onChooseSaveFolder = { receiveFolderPicker.launch(null) },
                            onQuickSaveModeChange = { mode ->
                                quickSaveMode = mode
                                prefs.edit().putString("quick_save_mode", mode.name).apply()
                            },
                            onAcceptIncoming = { pendingIncomingDecision?.invoke(true) },
                            onRejectIncoming = { pendingIncomingDecision?.invoke(false) },
                            onCancelReceive = { receiver.cancelCurrentTransfer() },
                            onPauseReceive = { receiver.togglePauseCurrentTransfer() },
                            onToggleReceive = {
                                receiveEnabled = !receiveEnabled
                                status = if (receiveEnabled) {
                                    "Receiving is on."
                                } else {
                                    "Receiving is off."
                                }
                            }
                        )
                    }
                    item {
                        ReceiveHistoryPanel(
                            history = receiveHistory,
                            onDelete = { item ->
                                receiveHistory = receiveHistory.filterNot { it == item }
                                saveReceiveHistory(prefs, receiveHistory)
                            }
                        )
                    }
                }
                Tab.Send -> {
                    item {
                        SendScreen(
                            pickedItems = pickedItems,
                            connectedPeer = manualPeer,
                            discoveredPeers = discoveredPeers,
                            vpnPeers = vpnPeers,
                            remotePeer = remotePeer,
                            wifiDirectLink = wifiDirectLink,
                            sendProgress = sendProgress,
                            sendInFlight = sendInFlight,
                            outboundPairCode = outboundPairCode,
                            onPairCodeChange = { outboundPairCode = it.filter(Char::isDigit).take(6) },
                            onClearSelection = {
                                pickedItems = emptyList()
                                status = "Selection cleared."
                            },
                            onRemoveSelected = { item ->
                                pickedItems = pickedItems.filterNot { it == item }
                                status = "Removed ${item.name}."
                            },
                            onPauseSend = {
                                // If the peer (e.g. Windows) initiated the pause, this
                                // tap means the local user wants to resume the transfer.
                                // Send "resumed" to the peer and optimistically clear the
                                // local "peer paused" flag so the UI flips back to active
                                // immediately. Otherwise, toggle the local pause flag and
                                // notify the peer accordingly.
                                val resumingPeerPause = sendPeerPaused
                                val paused = if (resumingPeerPause) {
                                    false
                                } else {
                                    !sendPauseFlag.get()
                                }
                                if (!resumingPeerPause) {
                                    sendPauseFlag.set(paused)
                                } else {
                                    sendPeerPaused = false
                                    sendPauseFlag.set(false)
                                }
                                sendControlOutput.get()?.let { output ->
                                    sendControlTransferId.get()?.let { transferId ->
                                        runCatching {
                                            writeControlMessage(
                                                output,
                                                flowControlMessage(transferId, if (paused) "paused" else "resumed"),
                                                sendControlWriteLock
                                            )
                                        }.onFailure { error ->
                                            AppLog.add("Could not notify peer about send pause state: ${error.message}")
                                        }
                                    }
                                }
                                sendProgress = sendProgress?.copy(
                                    active = !paused,
                                    speedMbps = if (paused) 0.0 else sendProgress?.speedMbps ?: 0.0
                                )
                                status = when {
                                    resumingPeerPause -> "Requested receiver resume."
                                    paused -> "Send paused."
                                    else -> "Send resumed."
                                }
                            },
                            onCancelSend = {
                                sendCancelFlag.set(true)
                                sendPauseFlag.set(false)
                                sendPeerPaused = false
                                sendProgress = sendProgress?.copy(active = false, speedMbps = 0.0, phase = SendPhase.Canceling)
                                sendControlOutput.get()?.let { output ->
                                    sendControlTransferId.get()?.let { transferId ->
                                        runCatching {
                                            writeControlMessage(
                                                output,
                                                flowControlMessage(transferId, "cancel_requested"),
                                                sendControlWriteLock
                                            )
                                        }.onFailure { error ->
                                            AppLog.add("Could not notify peer about send cancel: ${error.message}")
                                        }
                                    }
                                }
                                closeActiveSendSockets()
                                status = "Canceling send..."
                            },
                            onFiles = { filePicker.launch(openFilesIntent()) },
                            onFolder = { folderPicker.launch(null) },
                            onRefresh = {
                                sendRefreshing = true
                                networkDetails = NetworkDetails.activeNetworkDetails(context)
                                status = "Searching the local network."
                                discovery.probeNow()
                                mainHandler.postDelayed({ sendRefreshing = false }, 2200)
                            },
                            onPeerSelected = { peer ->
                                manualPeer = ManualPeer(
                                    name = peer.name,
                                    ip = peer.ip,
                                    port = peer.port,
                                    availability = peer.availability,
                                    requiresPairCode = peer.requiresPairCode,
                                    deviceKind = peer.deviceKind,
                                    deviceId = peer.deviceId
                                )
                                manualIp = peer.ip
                                knownPeerIps = knownPeerIps + peer.ip
                                prefs.edit().putStringSet("known_peer_ips", knownPeerIps + peer.ip).apply()
                                if (peer.deviceId.isNotBlank()) {
                                    trustedDeviceIds = trustedDeviceIds + peer.deviceId
                                    saveTrustedDeviceIds(prefs, trustedDeviceIds)
                                }
                                val link = peer.linkLabel
                                status = "Selected ${peer.name} at ${peer.ip}:${peer.port} ($link)."
                            },
                            onVpnPeerSelected = { vpn ->
                                // Probe the saved VPN host to refresh availability,
                                // then select it. Host may be 100.x or MagicDNS.
                                manualConnecting = true
                                status = "Connecting to ${vpn.name} at ${vpn.host}..."
                                val hostSnapshot = vpn.host
                                val portSnapshot = vpn.port
                                probeExecutor.execute {
                                    val result = runCatching {
                                        parseProbeResult(RustBridge.probePeer(hostSnapshot, portSnapshot))
                                    }.getOrElse { error ->
                                        ProbeResult.Failure(error.message ?: "Could not reach ${vpn.host}.")
                                    }
                                    mainHandler.post {
                                        manualConnecting = false
                                        when (result) {
                                            is ProbeResult.Success -> {
                                                manualPeer = result.peer
                                                manualIp = result.peer.ip.ifBlank { hostSnapshot }
                                                knownPeerIps = knownPeerIps + hostSnapshot + result.peer.ip
                                                prefs.edit().putStringSet("known_peer_ips", knownPeerIps).apply()
                                                if (result.peer.deviceId.isNotBlank()) {
                                                    trustedDeviceIds = trustedDeviceIds + result.peer.deviceId
                                                    saveTrustedDeviceIds(prefs, trustedDeviceIds)
                                                }
                                                vpnPeers = upsertVpnPeer(
                                                    prefs, vpnPeers,
                                                    VpnSavedPeer(
                                                        name = result.peer.name,
                                                        host = hostSnapshot,
                                                        port = portSnapshot,
                                                        deviceKind = result.peer.deviceKind,
                                                        deviceId = result.peer.deviceId,
                                                        secure = vpn.secure
                                                    )
                                                )
                                                status = "Connected to ${result.peer.name} at $hostSnapshot (VPN)."
                                            }
                                            is ProbeResult.Failure -> {
                                                // Keep the saved entry selectable even when offline:
                                                // select it directly so Send can retry.
                                                manualPeer = ManualPeer(
                                                    name = vpn.name,
                                                    ip = vpn.host,
                                                    port = vpn.port,
                                                    availability = "unknown",
                                                    requiresPairCode = false,
                                                    deviceKind = vpn.deviceKind,
                                                    deviceId = vpn.deviceId
                                                )
                                                manualIp = vpn.host
                                                status = result.message
                                            }
                                        }
                                    }
                                }
                            },
                            onRemotePeerSelected = {
                                remotePeer?.let { peer ->
                                    manualPeer = peer
                                    selectedTab = Tab.Send
                                    status = "Selected secure remote ${peer.name}."
                                }
                            },
                            onForgetVpnPeer = { vpn ->
                                vpnPeers = vpnPeers.filterNot { it.host.equals(vpn.host, ignoreCase = true) }
                                saveVpnPeers(prefs, vpnPeers)
                                knownPeerIps = knownPeerIps - vpn.host
                                prefs.edit().putStringSet("known_peer_ips", knownPeerIps).apply()
                                status = "Removed VPN device ${vpn.host}."
                            },
                            onSend = {
                                val peer = manualPeer
                                when {
                                    peer == null -> {
                                        status = "Connect to a PC first."
                                    }
                                    pickedItems.isEmpty() -> {
                                        status = "Choose files or a folder first."
                                    }
                                    sendInFlight -> {
                                        status = "A send request is already waiting on ${peer.name}."
                                    }
                                    else -> {
                                        sendCancelFlag.set(false)
                                        sendPauseFlag.set(false)
                                        sendInFlight = true
                                        sendError = null
                                        sendProgress = SendProgress(
                                            peerName = peer.name,
                                            currentFile = "Scanning selection...",
                                            completedBytes = 0L,
                                            totalBytes = 0L,
                                            speedMbps = 0.0,
                                            fileCount = pickedItems.size,
                                            phase = SendPhase.Preparing
                                        )
                                        val itemsToSend = pickedItems
                                        status = "Preparing files for ${peer.name}..."
                                        probeExecutor.execute {
                                            val result = runCatching {
                                                sendPickedFiles(
                                                    context = context,
                                                    peer = peer,
                                                    senderDeviceId = localDeviceId,
                                                    senderName = localDeviceName,
                                                    pairCode = outboundPairCode.takeIf { it.length == 6 },
                                                    items = itemsToSend,
                                                    concurrentFileStreams = concurrentFileStreams,
                                                    cancelFlag = sendCancelFlag,
                                                    pauseFlag = sendPauseFlag,
                                                    controlOutputRef = sendControlOutput,
                                                    controlTransferIdRef = sendControlTransferId,
                                                    controlWriteLock = sendControlWriteLock,
                                                    onStatus = { progress ->
                                                        mainHandler.post { status = progress }
                                                    },
                                                    onProgress = { progress ->
                                                        mainHandler.post {
                                                            sendProgress = if (progress != null && sendCancelFlag.get()) {
                                                                progress.copy(active = false, speedMbps = 0.0, phase = SendPhase.Canceling)
                                                            } else progress
                                                        }
                                                    },
                                                    onPeerPaused = {
                                                        mainHandler.post {
                                                            sendPeerPaused = true
                                                        }
                                                    },
                                                    onPeerResumed = {
                                                        mainHandler.post {
                                                            sendPeerPaused = false
                                                        }
                                                    },
                                                    activeSockets = activeSendSockets
                                                )
                                            }.getOrElse { error ->
                                                android.util.Log.e("LanDropTransfer", "Send failed", error)
                                                SendResult.Failure(
                                                    if (sendCancelFlag.get()) "Transfer canceled by user."
                                                    else error.message ?: "Transfer failed."
                                                )
                                            }
                                            if (result is SendResult.Failure) {
                                                AppLog.add("Send failed: ${result.message}")
                                                android.util.Log.e("LanDropTransfer", "Send failed: ${result.message}")
                                            }

                                            mainHandler.post {
                                                sendProgress = null
                                                sendPeerPaused = false
                                                sendInFlight = false
                                                status = when (result) {
                                                    is SendResult.Success -> {
                                                        sendHistory = listOf(
                                                            SendHistoryItem(
                                                                title = "Sent to ${peer.name}",
                                                                detail = itemsToSend.joinToString(
                                                                    limit = 2,
                                                                    truncated = if (itemsToSend.size > 2) "..." else ""
                                                                ) { it.name }
                                                            )
                                                        ) + sendHistory
                                                        saveSendHistory(prefs, sendHistory)
                                                        "Sent to ${peer.name}."
                                                    }
                                                    is SendResult.Rejected ->
                                                        result.message.ifBlank { "Transfer was rejected." }
                                                    is SendResult.Failure -> {
                                                        val message = result.message
                                                        if (
                                                            message.contains("canceled", ignoreCase = true) ||
                                                            message.contains("cancelled", ignoreCase = true)
                                                        ) {
                                                            "Send canceled."
                                                        } else {
                                                            message
                                                        }
                                                    }
                                                }
                                                sendError = when (result) {
                                                    is SendResult.Success -> null
                                                    is SendResult.Rejected -> status
                                                    is SendResult.Failure -> status.takeUnless { it == "Send canceled." }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                    item {
                        ManualConnectCard(
                            manualIp = manualIp,
                            peer = manualPeer,
                            connecting = manualConnecting,
                            outboundPairCode = outboundPairCode,
                            onManualIpChange = { manualIp = it },
                            onPairCodeChange = { outboundPairCode = it.filter(Char::isDigit).take(6) },
                            onConnect = {
                                val targetIp = manualIp.trim()
                                if (targetIp.isBlank()) {
                                    status = "Enter the Windows PC IP address first."
                                } else {
                                    manualConnecting = true
                                    status = "Connecting to $targetIp:50501..."
                                    probeExecutor.execute {
                                        val result = runCatching {
                                            parseProbeResult(RustBridge.probePeer(targetIp, 50501))
                                        }.getOrElse { error ->
                                            ProbeResult.Failure(
                                                error.message
                                                    ?: "Android Rust bridge is not packaged yet."
                                            )
                                        }

                                        mainHandler.post {
                                            manualConnecting = false
                                            when (result) {
                                                is ProbeResult.Success -> {
                                                    manualPeer = result.peer
                                                    val enteredHost = targetIp.substringBefore(':').trim()
                                                    val resolvedIp = result.peer.ip
                                                    knownPeerIps = knownPeerIps + resolvedIp + enteredHost
                                                    prefs.edit().putStringSet("known_peer_ips", knownPeerIps).apply()
                                                    if (result.peer.deviceId.isNotBlank()) {
                                                        trustedDeviceIds = trustedDeviceIds + result.peer.deviceId
                                                        saveTrustedDeviceIds(prefs, trustedDeviceIds)
                                                    }
                                                    val enteredIsHostname = enteredHost.isNotBlank() &&
                                                        !enteredHost.equals(resolvedIp, ignoreCase = true) &&
                                                        enteredHost.any { it.isLetter() || it == '-' }
                                                    if (isVpnOrTailscaleIp(resolvedIp) || enteredIsHostname || isVpnOrTailscaleIp(enteredHost)) {
                                                        // Preserve the user-entered MagicDNS/hostname so the
                                                        // saved VPN entry stays readable.
                                                        vpnPeers = upsertVpnPeer(
                                                            prefs, vpnPeers,
                                                            VpnSavedPeer(
                                                                name = result.peer.name,
                                                                host = enteredHost.ifBlank { resolvedIp },
                                                                port = result.peer.port,
                                                                deviceKind = result.peer.deviceKind,
                                                                deviceId = result.peer.deviceId
                                                            )
                                                        )
                                                    }
                                                    val link = linkLabelForIp(resolvedIp)
                                                    status =
                                                        "Connected to ${result.peer.name} at $enteredHost ($link)."
                                                }
                                                is ProbeResult.Failure -> {
                                                    manualPeer = null
                                                    status = result.message
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                    item {
                        SendHistoryPanel(
                            history = sendHistory,
                            onDelete = { item ->
                                sendHistory = sendHistory.filterNot { it == item }
                                saveSendHistory(prefs, sendHistory)
                            }
                        )
                    }
                }
                Tab.Settings -> {
                    item {
                        SettingsScreen(
                            deviceName = localDeviceName,
                            receiveEnabled = receiveEnabled,
                            pairCodeEnabled = pairCodeEnabled,
                            pairCode = pairCode,
                            themeMode = themeMode,
                            appLanguage = appLanguage,
                            networkDetails = networkDetails,
                            showLogs = showLogs,
                            logs = logs,
                            trustedDeviceIps = knownPeerIps.toList().sorted(),
                            vpnPeers = vpnPeers,
                            trustedDeviceIds = trustedDeviceIds,
                            concurrentFileStreams = concurrentFileStreams,
                            wifiDirectStatus = wifiDirectStatus,
                            wifiDirectLink = wifiDirectLink,
                            wifiDirectHostCredentials = wifiDirectHostCredentials,
                            wifiDirectProbing = wifiDirectProbing,
                            wifiDirectJoining = wifiDirectJoining,
                            remotePeer = remotePeer,
                            remoteConnecting = remoteConnecting,
                            remoteConnectionMessage = remoteConnectionMessage,
                            hasStoredRemoteInvite = !storedRemoteInvite.isNullOrBlank(),
                            onSaveDeviceName = { nextName ->
                                val sanitized = nextName.trim().take(48)
                                if (sanitized.isNotBlank()) {
                                    localDeviceName = sanitized
                                    prefs.edit().putString("device_name", sanitized).apply()
                                    status = "Device name saved as $sanitized."
                                    AppLog.add("Device name changed to $sanitized")
                                }
                            },
                            onToggleReceive = { receiveEnabled = !receiveEnabled },
                            onTogglePairCode = {
                                pairCodeEnabled = !pairCodeEnabled
                                prefs.edit().putBoolean("pair_code_enabled", pairCodeEnabled).apply()
                            },
                            onThemeModeChange = onThemeModeChange,
                            onAppLanguageChange = onAppLanguageChange,
                            onConcurrentFileStreamsChange = { count ->
                                concurrentFileStreams = count.coerceIn(1, 100)
                                prefs.edit().putInt("concurrent_file_streams", concurrentFileStreams).apply()
                                status = "Concurrent file streams set to $concurrentFileStreams."
                            },
                            onRefreshDiscovery = {
                                networkDetails = NetworkDetails.activeNetworkDetails(context)
                                status = "Searching the local network."
                                discovery.probeNow()
                            },
                            onLoadLogs = {
                                logs = AppLog.snapshot()
                                showLogs = !showLogs
                            },
                            onOpenHotspotSettings = {
                                runCatching {
                                    context.startActivity(Intent("android.settings.TETHER_SETTINGS"))
                                }.onFailure {
                                    context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                                }
                            },
                            onDisconnectWifiDirect = {
                                pendingWifiDirectAction = null
                                wifiDirectTransport.disconnect()
                            },
                            directLanDropPeers = discoveredPeers.filter { peer ->
                                peer.adapterKind.equals("wifi_direct", ignoreCase = true) ||
                                    peer.ip.startsWith("192.168.49.") ||
                                    peer.ip.startsWith("192.168.137.")
                            },
                            onSendToDirectPeer = { peer ->
                                manualPeer = ManualPeer(
                                    name = peer.name,
                                    ip = peer.ip,
                                    port = peer.port,
                                    availability = peer.availability,
                                    requiresPairCode = peer.requiresPairCode,
                                    deviceKind = peer.deviceKind,
                                    deviceId = peer.deviceId
                                )
                                manualIp = peer.ip
                                selectedTab = Tab.Send
                                status = "Selected ${peer.name} for Send."
                            },
                            onHostWifiDirect = {
                                val missing = wifiDirectTransport.missingPermissions()
                                if (missing.isEmpty()) {
                                    wifiDirectTransport.createGroup()
                                } else {
                                    pendingWifiDirectAction = { wifiDirectTransport.createGroup() }
                                    wifiDirectPermissionLauncher.launch(missing.toTypedArray())
                                }
                            },
            onJoinWifiDirectQr = { payload ->
                val credentials = WifiDirectCredentials.fromQrPayload(payload)
                if (credentials == null) {
                    status = "Invalid LanDrop Wi-Fi Direct QR code."
                    wifiDirectStatus = status
                    wifiDirectJoining = false
                } else {
                    val connectAction = {
                        wifiDirectHostCredentials = null
                        wifiDirectJoining = true
                        wifiDirectTransport.connect(credentials)
                    }
                                    val missing = wifiDirectTransport.missingPermissions()
                                    if (missing.isEmpty()) {
                                        connectAction()
                                    } else {
                                        pendingWifiDirectAction = connectAction
                                        wifiDirectPermissionLauncher.launch(missing.toTypedArray())
                                    }
                                }
                            },
                            onRetryWifiDirectProbe = {
                                wifiDirectLink?.let { probeWifiDirectLink(it) }
                                    ?: wifiDirectTransport.refreshLinkInfo()
                            },
                            onConnectRemote = { invite ->
                                connectRemoteInvite(invite, persist = true)
                            },
                            onReconnectRemote = {
                                val invite = storedRemoteInvite
                                if (!invite.isNullOrBlank()) {
                                    connectRemoteInvite(invite, persist = false)
                                } else {
                                    status = "No saved remote invite to reconnect."
                                }
                            },
                            onUseRemote = {
                                remotePeer?.let { peer ->
                                    manualPeer = peer
                                    selectedTab = Tab.Send
                                    status = "Selected secure remote computer ${peer.name}."
                                }
                            },
                            onForgetRemote = {
                                remoteTunnel.stop()
                                AndroidRemoteInviteStore.clear(prefs)
                                storedRemoteInvite = null
                                if (manualPeer?.remote == true) {
                                    manualPeer = null
                                }
                                remotePeer = null
                                status = "Remote computer removed and its invite revoked on this phone."
                                remoteConnectionMessage =
                                    "Scan the QR code shown by the receiving computer."
                            },
                            onNewPairCode = {
                                pairCode = generatePairCode()
                                prefs.edit().putString("pair_code", pairCode).apply()
                                status = "New pair code: $pairCode."
                            },
                            onForgetTrustedIp = { ip ->
                                knownPeerIps = knownPeerIps - ip
                                prefs.edit().putStringSet("known_peer_ips", knownPeerIps).apply()
                                status = "Removed trusted device $ip."
                            },
                            onForgetVpnPeer = { vpn ->
                                vpnPeers = vpnPeers.filterNot { it.host.equals(vpn.host, ignoreCase = true) }
                                saveVpnPeers(prefs, vpnPeers)
                                knownPeerIps = knownPeerIps - vpn.host
                                prefs.edit().putStringSet("known_peer_ips", knownPeerIps).apply()
                                status = "Removed VPN device ${vpn.host}."
                            }
                        )
                    }
                }
                }
            }
        }
    }
}

private fun openFilesIntent(): Intent {
    return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }
}

private fun extractPickedUris(intent: Intent): List<Uri> {
    val uris = mutableListOf<Uri>()
    val clipData = intent.clipData
    if (clipData != null) {
        for (index in 0 until clipData.itemCount) {
            clipData.getItemAt(index).uri?.let { uris += it }
        }
    }
    intent.data?.let { uri ->
        if (uri !in uris) {
            uris += uri
        }
    }
    return uris
}

private fun describePickedUri(context: Context, uri: Uri): Pair<String, Long?> {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                cursor.getLong(sizeIndex)
            } else {
                null
            }
            return cursor.getString(nameIndex) to size
        }
    }
    return (uri.lastPathSegment ?: "Selected file") to null
}

private fun describeTreeUri(uri: Uri): String {
    val path = uri.lastPathSegment ?: return "Selected folder"
    return path.substringAfterLast(':').ifBlank { "Selected folder" }
}

private fun deviceInitial(name: String): String {
    return name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "L"
}

private fun generatePairCode(): String {
    return (100000..999999).random().toString()
}

private fun loadReceiveHistory(prefs: SharedPreferences): List<ReceiveHistoryItem> {
    return runCatching {
        val array = JSONArray(prefs.getString("receive_history", "[]"))
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            ReceiveHistoryItem(
                title = item.getString("title"),
                detail = item.getString("detail"),
                timestampMs = item.optLong("timestampMs", System.currentTimeMillis())
            )
        }
    }.getOrDefault(emptyList())
}

private fun saveReceiveHistory(prefs: SharedPreferences, history: List<ReceiveHistoryItem>) {
    val array = JSONArray()
    history.take(100).forEach { item ->
        array.put(
            JSONObject()
                .put("title", item.title)
                .put("detail", item.detail)
                .put("timestampMs", item.timestampMs)
        )
    }
    prefs.edit().putString("receive_history", array.toString()).apply()
}

private fun loadSendHistory(prefs: SharedPreferences): List<SendHistoryItem> {
    return runCatching {
        val array = JSONArray(prefs.getString("send_history", "[]"))
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            SendHistoryItem(
                title = item.getString("title"),
                detail = item.getString("detail"),
                timestampMs = item.optLong("timestampMs", System.currentTimeMillis())
            )
        }
    }.getOrDefault(emptyList())
}

private fun saveSendHistory(prefs: SharedPreferences, history: List<SendHistoryItem>) {
    val array = JSONArray()
    history.take(100).forEach { item ->
        array.put(
            JSONObject()
                .put("title", item.title)
                .put("detail", item.detail)
                .put("timestampMs", item.timestampMs)
        )
    }
    prefs.edit().putString("send_history", array.toString()).apply()
}

private sealed interface ProbeResult {
    data class Success(val peer: ManualPeer) : ProbeResult
    data class Failure(val message: String) : ProbeResult
}

private sealed interface OfferResult {
    data class Accepted(val transferId: String, val message: String) : OfferResult
    data class Rejected(val message: String) : OfferResult
    data class Failure(val message: String) : OfferResult
}

private sealed interface SendResult {
    data object Success : SendResult
    data class Rejected(val message: String) : SendResult
    data class Failure(val message: String) : SendResult
}

private fun parseProbeResult(raw: String): ProbeResult {
    val json = JSONObject(raw)
    if (!json.optBoolean("ok")) {
        return ProbeResult.Failure(json.optString("error", "Could not connect to this PC."))
    }

    return ProbeResult.Success(
        ManualPeer(
            name = json.optString("name", "LanDrop PC"),
            ip = json.optString("ip"),
            port = json.optInt("port", 50501),
            availability = json.optString("availability", "available"),
            requiresPairCode = json.optBoolean("requiresPairCode"),
            deviceKind = json.optString("deviceKind", "desktop").ifBlank { "desktop" },
            deviceId = json.optString("deviceId", "")
        )
    )
}

private fun manualPeerLinkLabel(peer: ManualPeer): String {
    if (peer.remote) return "Secure"
    return linkLabelForIp(peer.ip)
}

private fun linkLabelForIp(ip: String): String {
    val trimmed = ip.trim()
    if (trimmed.isBlank()) return "LAN"
    if (isVpnOrTailscaleIp(trimmed)) return "VPN"
    if (trimmed.startsWith("192.168.49.")) return "Wi-Fi Direct"
    if (trimmed.startsWith("192.168.137.")) return "Wi-Fi Direct"
    return "LAN"
}

private fun formatBytesApprox(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0 || value >= 100.0) {
        "${value.toInt()} ${units[unit]}"
    } else {
        "${"%.1f".format(value)} ${units[unit]}"
    }
}

private fun formatTransferLine(completedBytes: Long, totalBytes: Long, speedMbps: Double): String {
    val percent = if (totalBytes <= 0L) 0 else ((completedBytes.toDouble() / totalBytes.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    val size = "${formatBytesApprox(completedBytes)} / ${formatBytesApprox(totalBytes)}"
    return "$percent% • $size • ${"%.2f".format(speedMbps)} Mbps"
}

private fun loadVpnPeers(prefs: SharedPreferences): List<VpnSavedPeer> {
    return runCatching {
        val array = JSONArray(prefs.getString("vpn_peers", "[]"))
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            VpnSavedPeer(
                name = item.optString("name", "VPN device"),
                host = item.optString("host", ""),
                port = item.optInt("port", 50501),
                deviceKind = item.optString("deviceKind", "desktop"),
                deviceId = item.optString("deviceId", ""),
                secure = item.optBoolean("secure", false),
                lastSeenMs = item.optLong("lastSeenMs", System.currentTimeMillis())
            )
        }.filter { it.host.isNotBlank() }
    }.getOrDefault(emptyList())
}

private fun saveVpnPeers(prefs: SharedPreferences, peers: List<VpnSavedPeer>) {
    val array = JSONArray()
    peers.take(20).forEach { peer ->
        array.put(
            JSONObject()
                .put("name", peer.name)
                .put("host", peer.host)
                .put("port", peer.port)
                .put("deviceKind", peer.deviceKind)
                .put("deviceId", peer.deviceId)
                .put("secure", peer.secure)
                .put("lastSeenMs", peer.lastSeenMs)
        )
    }
    prefs.edit().putString("vpn_peers", array.toString()).apply()
}

private fun upsertVpnPeer(
    prefs: SharedPreferences,
    current: List<VpnSavedPeer>,
    peer: VpnSavedPeer
): List<VpnSavedPeer> {
    val key = peer.host.trim().lowercase()
    val next = (current.filterNot { it.host.trim().lowercase() == key } + peer)
        .sortedByDescending { it.lastSeenMs }
        .take(20)
    saveVpnPeers(prefs, next)
    return next
}

private fun loadTrustedDeviceIds(prefs: SharedPreferences): Set<String> {
    return prefs.getStringSet("trusted_device_ids", emptySet()) ?: emptySet()
}

private fun saveTrustedDeviceIds(prefs: SharedPreferences, ids: Set<String>) {
    prefs.edit().putStringSet("trusted_device_ids", ids).apply()
}

private fun parseOfferResult(raw: String): OfferResult {
    val json = JSONObject(raw)
    if (!json.optBoolean("ok")) {
        return OfferResult.Failure(json.optString("error", "Could not send transfer offer."))
    }

    val message = json.optString("message")
    return if (json.optBoolean("accepted")) {
        OfferResult.Accepted(json.optString("transferId"), message)
    } else {
        OfferResult.Rejected(message)
    }
}

private fun buildOfferPayload(items: List<PickedItem>): String {
    val topLevelItems = JSONArray()
    var totalBytes = 0L
    var fileCount = 0
    var directoryCount = 0

    items.forEach { item ->
        val isFolder = item.kind == "Folder"
        if (isFolder) {
            directoryCount += 1
        } else {
            fileCount += 1
        }
        item.sizeBytes?.let { totalBytes += it }
        topLevelItems.put(
            JSONObject()
                .put("kind", item.kind.lowercase())
                .put("name", item.name)
                .put("size", item.sizeBytes ?: JSONObject.NULL)
        )
    }

    return JSONObject()
        .put("fileCount", fileCount)
        .put("directoryCount", directoryCount)
        .put("totalBytes", totalBytes)
        .put("topLevelItems", topLevelItems)
        .toString()
}

private fun sendPickedFiles(
    context: Context,
    peer: ManualPeer,
    senderDeviceId: String,
    senderName: String,
    pairCode: String?,
    items: List<PickedItem>,
    concurrentFileStreams: Int,
    cancelFlag: AtomicBoolean,
    pauseFlag: AtomicBoolean,
    controlOutputRef: AtomicReference<DataOutputStream?>,
    controlTransferIdRef: AtomicReference<String?>,
    controlWriteLock: Any,
    onStatus: (String) -> Unit,
    onProgress: (SendProgress?) -> Unit,
    onPeerPaused: () -> Unit,
    onPeerResumed: () -> Unit,
    activeSockets: ConcurrentLinkedQueue<Socket>? = null
): SendResult {
    onStatus("Scanning selection...")
    if (cancelFlag.get()) return SendResult.Failure("Transfer canceled by user.")
    val expanded = expandPickedItems(context, items, cancelFlag)
    if (cancelFlag.get()) return SendResult.Failure("Transfer canceled by user.")
    val files = expanded.files
    val directories = expanded.directories
    if (files.isEmpty()) {
        return SendResult.Failure("No files found in this selection.")
    }

    val transferId = "android-${System.currentTimeMillis()}"
    val totalBytes = files.sumOf { it.sizeBytes }
    // A secure remote peer is reached through the local TLS tunnel. Keep that
    // tunnel to one data connection; parallel workers are only useful on LAN
    // and can race the remote session setup on mobile networks.
    val maxParallelFileStreams = if (peer.remote) 1 else concurrentFileStreams.coerceIn(1, 100)
    onProgress(
        SendProgress(
            peerName = peer.name,
            currentFile = "Preparing ${files.size} file(s)",
            completedBytes = 0L,
            totalBytes = totalBytes,
            speedMbps = 0.0,
            active = true,
            fileCount = files.size,
            phase = SendPhase.Preparing
        )
    )
    // Keep the radio awake for the whole transfer; without a
    // high-perf lock Wi-Fi naps between bursts and reads as slow,
    // sawtooth throughput with stalls.
    TransferWifiLock.acquire(context)
    val socket = Socket()
    socket.tcpNoDelay = true
    // Match the receiver's large buffers so transfers start fast.
    runCatching { socket.sendBufferSize = 2 * 1024 * 1024 }
    runCatching { socket.receiveBufferSize = 2 * 1024 * 1024 }
    activeSockets?.add(socket)
    try {
        if (cancelFlag.get()) return SendResult.Failure("Transfer canceled by user.")
        socket.connect(InetSocketAddress(peer.ip, peer.port), 5000)
        socket.soTimeout = 300_000
        socket.use {
            val input = DataInputStream(it.getInputStream())
            val output = DataOutputStream(it.getOutputStream())
            val offer = JSONObject()
            .put("type", "transfer_offer")
            .put("protocolVersion", 1)
            .put("transferId", transferId)
            .put("senderDeviceId", senderDeviceId)
            .put("senderName", senderName)
            .put("receiverDeviceId", JSONObject.NULL)
            .put("pairCode", pairCode ?: JSONObject.NULL)
            .put("fileCount", files.size)
            .put("directoryCount", directories.size)
            .put("totalBytes", totalBytes)
            .put("requiresPairCode", pairCode != null)
            .put("maxParallelFileStreams", maxParallelFileStreams)
            .put("supportedTransferEncodings", JSONArray().apply {
                put("framed_v2")
                put("raw_v1")
            })
            .put("topLevelItems", JSONArray().apply {
                items.forEach { item ->
                    put(
                        JSONObject()
                            .put("kind", item.kind.lowercase())
                            .put("name", item.name)
                            .put("size", item.sizeBytes ?: JSONObject.NULL)
                    )
                }
            })
            .put("sentAtMs", System.currentTimeMillis())

        onStatus("Waiting for ${peer.name} to accept...")
        onProgress(SendProgress(
            peerName = peer.name,
            currentFile = "Waiting for ${peer.name} to accept...",
            completedBytes = 0L,
            totalBytes = totalBytes,
            speedMbps = 0.0,
            fileCount = files.size,
            currentFiles = listOf("Waiting for ${peer.name} to accept...") + files.take(4).map { it.relativePath },
            phase = SendPhase.WaitingForAcceptance
        ))
        controlOutputRef.set(output)
        controlTransferIdRef.set(transferId)
        writeControlMessage(output, offer, controlWriteLock)
        val response = readControlMessage(input)
        if (response.optString("type") != "transfer_response") {
            return SendResult.Failure("Unexpected response from ${peer.name}.")
        }
        if (!response.optBoolean("accepted")) {
            return SendResult.Rejected(response.optString("message", "Transfer rejected."))
        }
        if (cancelFlag.get()) return SendResult.Failure("Transfer canceled by user.")
        onProgress(SendProgress(
            peerName = peer.name,
            currentFile = "Preparing ${files.size} file(s)",
            completedBytes = 0L,
            totalBytes = totalBytes,
            speedMbps = 0.0,
            fileCount = files.size
        ))
        val selectedEncoding = response.optString("selectedTransferEncoding", "raw_v1")
        if (selectedEncoding != "raw_v1" && selectedEncoding != "framed_v2") {
            return SendResult.Failure("Peer selected unknown transfer encoding: $selectedEncoding")
        }
        if (selectedEncoding == "raw_v1") {
            // Raw data cannot contain JSON flow control after acceptance.
            controlOutputRef.set(null)
            controlTransferIdRef.set(null)
        }
        val selectedParallelFileStreams = response
            .optInt("selectedParallelFileStreams", 1)
            .coerceIn(1, maxParallelFileStreams)
        if (selectedEncoding == "framed_v2") {
            controlOutputRef.set(output)
            controlTransferIdRef.set(transferId)
        }

        val entries = JSONArray()
        directories.forEach { directory ->
            entries.put(
                JSONObject()
                    .put("relativePath", directory.relativePath)
                    .put("kind", "directory")
                    .put("size", JSONObject.NULL)
                    .put("modifiedAtMs", directory.modifiedAtMs ?: JSONObject.NULL)
            )
        }
        files.forEach { file ->
            entries.put(
                JSONObject()
                    .put("relativePath", file.relativePath)
                    .put("kind", "file")
                    .put("size", file.sizeBytes)
                    .put("modifiedAtMs", file.modifiedAtMs ?: JSONObject.NULL)
            )
        }
        val manifest = JSONObject()
            .put("type", "transfer_manifest")
            .put("protocolVersion", 1)
            .put("transferId", transferId)
            .put("totalBytes", totalBytes)
            .put("selectedParallelFileStreams", selectedParallelFileStreams)
            .put("entries", entries)
            .put("sentAtMs", System.currentTimeMillis())

        onStatus("Sending manifest...")
        writeControlMessage(output, manifest, controlWriteLock)
        val resumePlan = readControlMessage(input)
        if (resumePlan.optString("type") != "transfer_resume_plan") {
            return SendResult.Failure("Receiver did not return a resume plan.")
        }
        val offsets = mutableMapOf<String, Long>()
        val resumeFiles = resumePlan.optJSONArray("files") ?: JSONArray()
        for (index in 0 until resumeFiles.length()) {
            val file = resumeFiles.optJSONObject(index) ?: continue
            offsets[file.optString("relativePath")] = file.optLong("completedBytes", 0L)
        }

        if (selectedEncoding == "framed_v2" && selectedParallelFileStreams > 1) {
            return sendPickedFilesFramedV2Parallel(
                context = context,
                peer = peer,
                input = input,
                output = output,
                transferId = transferId,
                files = files,
                offsets = offsets,
                totalBytes = totalBytes,
                workerCount = selectedParallelFileStreams,
                cancelFlag = cancelFlag,
                pauseFlag = pauseFlag,
                controlWriteLock = controlWriteLock,
                onStatus = onStatus,
                onProgress = onProgress,
                onPeerPaused = onPeerPaused,
                onPeerResumed = onPeerResumed,
                activeSockets = activeSockets
            )
        }

        if (selectedEncoding == "framed_v2") {
            return sendPickedFilesFramedV2(
                context = context,
                peer = peer,
                input = input,
                output = output,
                transferId = transferId,
                files = files,
                offsets = offsets,
                totalBytes = totalBytes,
                cancelFlag = cancelFlag,
                pauseFlag = pauseFlag,
                controlWriteLock = controlWriteLock,
                onStatus = onStatus,
                onProgress = onProgress,
                onPeerPaused = onPeerPaused,
                onPeerResumed = onPeerResumed
            )
        }

        val buffer = ByteArray(1024 * 1024)
        var completedBytes = offsets.values.sum().coerceAtMost(totalBytes)
        var lastProgressBytes = completedBytes
        var lastProgressAtMs = System.currentTimeMillis()
        var lastUiAtMs = 0L
        files.forEach { file ->
            val relativePath = file.relativePath
            val size = file.sizeBytes
            val offset = offsets[relativePath]?.coerceAtMost(size) ?: 0L
            if (offset >= size) {
                return@forEach
            }

            val start = JSONObject()
                .put("type", "file_start")
                .put("protocolVersion", 1)
                .put("transferId", transferId)
                .put("relativePath", relativePath)
                .put("size", size)
                .put("offset", offset)
            writeControlMessage(output, start, controlWriteLock)

            useTransferSource(openTransferInputStream(context, file), file.relativePath) { raw ->
                val stream = BufferedInputStream(raw, buffer.size)
                skipFully(stream, offset)
                var remaining = size - offset
                while (remaining > 0) {
                    if (cancelFlag.get()) {
                        return SendResult.Failure("Transfer canceled by user.")
                    }
                    while (pauseFlag.get()) {
                        if (cancelFlag.get()) {
                            return SendResult.Failure("Transfer canceled by user.")
                        }
                        Thread.sleep(120)
                        lastProgressAtMs = System.currentTimeMillis()
                    }
                    val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) {
                        return SendResult.Failure("Could not finish reading ${file.relativePath}.")
                    }
                    output.write(buffer, 0, read)
                    completedBytes += read
                    remaining -= read
                    val nowMs = System.currentTimeMillis()
                    // 1-second average: measure only across UI emits so the
                    // readout holds steady instead of flickering per chunk.
                    if (nowMs - lastUiAtMs >= 1000 || remaining <= 0) {
                        val elapsedMs = (nowMs - lastProgressAtMs).coerceAtLeast(1L)
                        val deltaBytes = completedBytes - lastProgressBytes
                        val speedMbps = (deltaBytes.toDouble() * 8.0) / elapsedMs.toDouble() / 1000.0
                        lastProgressBytes = completedBytes
                        lastProgressAtMs = nowMs
                        lastUiAtMs = nowMs
                        onStatus("Sending ${file.relativePath} ${progressPercent(completedBytes, totalBytes)}")
                        onProgress(
                            SendProgress(
                                peerName = peer.name,
                                currentFile = file.relativePath,
                                completedBytes = completedBytes,
                                totalBytes = totalBytes,
                                speedMbps = speedMbps,
                                active = !pauseFlag.get(),
                                fileCount = files.size
                            )
                        )
                    }
                }
            }
        }

        writeControlMessage(
            output,
            JSONObject()
                .put("type", "transfer_complete")
                .put("protocolVersion", 1)
                .put("transferId", transferId)
                .put("completedAtMs", System.currentTimeMillis())
            ,
            controlWriteLock
        )
        output.flush()
        val receipt = readControlMessage(input)
        if (receipt.optString("type") != "transfer_receipt" || !receipt.optBoolean("success")) {
            return SendResult.Failure(receipt.optString("message", "Receiver did not confirm completion."))
        }
            return SendResult.Success
        }
    } finally {
        runCatching { socket.close() }
        activeSockets?.remove(socket)
        controlOutputRef.set(null)
        controlTransferIdRef.set(null)
        TransferWifiLock.release()
    }
}

private fun sendPickedFilesFramedV2(
    context: Context,
    peer: ManualPeer,
    input: DataInputStream,
    output: DataOutputStream,
    transferId: String,
    files: List<TransferFile>,
    offsets: Map<String, Long>,
    totalBytes: Long,
    cancelFlag: AtomicBoolean,
    pauseFlag: AtomicBoolean,
    controlWriteLock: Any,
    onStatus: (String) -> Unit,
    onProgress: (SendProgress?) -> Unit,
    onPeerPaused: () -> Unit,
    onPeerResumed: () -> Unit
): SendResult {
    val buffer = ByteArray(1024 * 1024)
    var completedBytes = offsets.values.sum().coerceAtMost(totalBytes)
        var lastProgressBytes = completedBytes
        var lastProgressAtMs = System.currentTimeMillis()
        var lastUiAtMs = 0L
        var sequence = 0L
    val pendingAcks = ArrayDeque<PendingChunkAck>()

    files.forEach { file ->
        val relativePath = file.relativePath
        val size = file.sizeBytes
        val offset = offsets[relativePath]?.coerceAtMost(size) ?: 0L
        if (offset >= size) {
            return@forEach
        }

        writeControlMessage(
            output,
            JSONObject()
                .put("type", "file_start")
                .put("protocolVersion", 1)
                .put("transferId", transferId)
                .put("relativePath", relativePath)
                .put("size", size)
                .put("offset", offset)
        )

        useTransferSource(openTransferInputStream(context, file), file.relativePath) { raw ->
            val stream = BufferedInputStream(raw, buffer.size)
            skipFully(stream, offset)
            var remaining = size - offset
            var currentOffset = offset
            while (remaining > 0) {
                if (cancelFlag.get()) {
                    writeControlMessage(output, flowControlMessage(transferId, "cancel_requested"), controlWriteLock)
                    return SendResult.Failure("Transfer canceled by user.")
                }
                if (pauseFlag.get()) {
                    flushPendingChunkAcks(
                        input = input,
                        output = output,
                        controlWriteLock = controlWriteLock,
                        transferId = transferId,
                        pending = pendingAcks,
                        cancelFlag = cancelFlag,
                        pauseFlag = pauseFlag,
                        onStatus = onStatus,
                        onPeerPaused = onPeerPaused,
                        onPeerResumed = onPeerResumed
                    )?.let { return SendResult.Failure(it) }
                    var pausedNoticeSent = false
                    while (pauseFlag.get()) {
                        if (!pausedNoticeSent) {
                            writeControlMessage(output, flowControlMessage(transferId, "paused"), controlWriteLock)
                            pausedNoticeSent = true
                            onProgress(
                                SendProgress(
                                    peerName = peer.name,
                                    currentFile = relativePath,
                                    completedBytes = completedBytes,
                                    totalBytes = totalBytes,
                                    speedMbps = 0.0,
                                    active = false,
                                    fileCount = files.size
                                )
                            )
                        }
                        if (cancelFlag.get()) {
                            writeControlMessage(output, flowControlMessage(transferId, "cancel_requested"), controlWriteLock)
                            return SendResult.Failure("Transfer canceled by user.")
                        }
                        if (input.available() > 0) {
                            val control = readControlMessage(input)
                            when (control.optString("type")) {
                                "file_chunk_ack" -> {
                                    val expected = pendingAcks.peekFirst()
                                        ?: return SendResult.Failure(
                                            "Received chunk acknowledgement with no pending chunks."
                                        )
                                    val ok = control.optString("transferId") == transferId &&
                                        control.optString("relativePath") == expected.relativePath &&
                                        control.optLong("nextOffset") == expected.nextOffset &&
                                        control.optLong("sequence") == expected.sequence
                                    if (!ok) {
                                        return SendResult.Failure("Receiver acknowledged the wrong file chunk.")
                                    }
                                    pendingAcks.removeFirst()
                                }
                                "transfer_flow_control" -> {
                                    if (control.optString("transferId") != transferId) {
                                        return SendResult.Failure("Unexpected frame while paused.")
                                    }
                                    when (control.optString("state")) {
                                        "resumed" -> {
                                            pauseFlag.set(false)
                                            onPeerResumed()
                                            onStatus("Peer resumed the transfer.")
                                        }
                                        "cancel_requested" ->
                                            return SendResult.Failure("Transfer canceled by receiver.")
                                    }
                                }
                                else -> return SendResult.Failure("Unexpected frame while paused.")
                            }
                        } else {
                            Thread.sleep(120)
                        }
                        lastProgressAtMs = System.currentTimeMillis()
                    }
                    if (pausedNoticeSent) {
                        writeControlMessage(output, flowControlMessage(transferId, "resumed"), controlWriteLock)
                    }
                }
                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) {
                    return SendResult.Failure("Could not finish reading ${file.relativePath}.")
                }

                synchronized(controlWriteLock) {
                    writeControlMessage(
                        output,
                        JSONObject()
                            .put("type", "file_chunk")
                            .put("protocolVersion", 2)
                            .put("transferId", transferId)
                            .put("relativePath", relativePath)
                            .put("offset", currentOffset)
                            .put("length", read)
                            .put("sequence", sequence)
                    )
                    output.write(buffer, 0, read)
                    output.flush()
                }

                val ackResult = noteChunkSentAndManageAckWindow(
                    input = input,
                    output = output,
                    controlWriteLock = controlWriteLock,
                    transferId = transferId,
                    pending = pendingAcks,
                    ack = PendingChunkAck(
                        relativePath = relativePath,
                        nextOffset = currentOffset + read,
                        sequence = sequence
                    ),
                    cancelFlag = cancelFlag,
                    pauseFlag = pauseFlag,
                    onStatus = onStatus,
                    onPeerPaused = {
                        onPeerPaused()
                        onProgress(
                            SendProgress(
                                peerName = peer.name,
                                currentFile = relativePath,
                                completedBytes = completedBytes,
                                totalBytes = totalBytes,
                                speedMbps = 0.0,
                                active = false,
                                fileCount = files.size
                            )
                        )
                    },
                    onPeerResumed = {
                        onPeerResumed()
                        onProgress(
                            SendProgress(
                                peerName = peer.name,
                                currentFile = relativePath,
                                completedBytes = completedBytes,
                                totalBytes = totalBytes,
                                speedMbps = 0.0,
                                active = true,
                                fileCount = files.size
                            )
                        )
                    }
                )
                if (ackResult != null) {
                    return SendResult.Failure(ackResult)
                }

                completedBytes += read
                remaining -= read
                currentOffset += read
                val nowMs = System.currentTimeMillis()
                // 1-second average: measure only across UI emits so the
                // readout holds steady instead of flickering per chunk.
                if (nowMs - lastUiAtMs >= 1000 || remaining <= 0) {
                    val elapsedMs = (nowMs - lastProgressAtMs).coerceAtLeast(1L)
                    val deltaBytes = completedBytes - lastProgressBytes
                    val speedMbps = (deltaBytes.toDouble() * 8.0) / elapsedMs.toDouble() / 1000.0
                    lastProgressBytes = completedBytes
                    lastProgressAtMs = nowMs
                    lastUiAtMs = nowMs
                    onStatus("Sending ${file.relativePath} ${progressPercent(completedBytes, totalBytes)}")
                    onProgress(
                        SendProgress(
                            peerName = peer.name,
                            currentFile = file.relativePath,
                            completedBytes = completedBytes,
                            totalBytes = totalBytes,
                            speedMbps = speedMbps,
                            active = !pauseFlag.get(),
                            fileCount = files.size
                        )
                    )
                }
                sequence += 1
            }
        }

        flushPendingChunkAcks(
            input = input,
            output = output,
            controlWriteLock = controlWriteLock,
            transferId = transferId,
            pending = pendingAcks,
            cancelFlag = cancelFlag,
            pauseFlag = pauseFlag,
            onStatus = onStatus,
            onPeerPaused = onPeerPaused,
            onPeerResumed = onPeerResumed
        )?.let { return SendResult.Failure(it) }

        writeControlMessage(
            output,
            JSONObject()
                .put("type", "file_end")
                .put("protocolVersion", 2)
                .put("transferId", transferId)
                .put("relativePath", relativePath)
                .put("size", size)
                .put("completedAtMs", System.currentTimeMillis())
            ,
            controlWriteLock
        )
    }

    writeControlMessage(
        output,
        JSONObject()
            .put("type", "transfer_complete")
            .put("protocolVersion", 1)
            .put("transferId", transferId)
            .put("completedAtMs", System.currentTimeMillis())
        ,
        controlWriteLock
    )
    output.flush()
    val receipt = readControlMessage(input)
    if (receipt.optString("type") != "transfer_receipt" || !receipt.optBoolean("success")) {
        return SendResult.Failure(receipt.optString("message", "Receiver did not confirm completion."))
    }
    return SendResult.Success
}

private fun sendPickedFilesFramedV2Parallel(
    context: Context,
    peer: ManualPeer,
    input: DataInputStream,
    output: DataOutputStream,
    transferId: String,
    files: List<TransferFile>,
    offsets: Map<String, Long>,
    totalBytes: Long,
    workerCount: Int,
    cancelFlag: AtomicBoolean,
    pauseFlag: AtomicBoolean,
    controlWriteLock: Any,
    onStatus: (String) -> Unit,
    onProgress: (SendProgress?) -> Unit,
    onPeerPaused: () -> Unit,
    onPeerResumed: () -> Unit,
    activeSockets: ConcurrentLinkedQueue<Socket>? = null
): SendResult {
    val pendingFiles = ConcurrentLinkedQueue(
        files.filter { file ->
            val offset = offsets[file.relativePath]?.coerceAtMost(file.sizeBytes) ?: 0L
            offset < file.sizeBytes
        }
    )
    val completedBytes = AtomicLong(offsets.values.sum().coerceAtMost(totalBytes))
    if (pendingFiles.isEmpty()) {
        writeControlMessage(
            output,
            JSONObject()
                .put("type", "transfer_complete")
                .put("protocolVersion", 1)
                .put("transferId", transferId)
                .put("completedAtMs", System.currentTimeMillis()),
            controlWriteLock
        )
        val receipt = readControlMessage(input)
        return if (receipt.optString("type") == "transfer_receipt" && receipt.optBoolean("success")) {
            SendResult.Success
        } else {
            SendResult.Failure(receipt.optString("message", "Receiver did not confirm completion."))
        }
    }

    val session = AndroidParallelSendSession(
        context = context,
        peer = peer,
        transferId = transferId,
        files = files,
        offsets = offsets,
        totalBytes = totalBytes,
        completedBytes = completedBytes,
        activeFiles = ConcurrentHashMap(),
        cancelFlag = cancelFlag,
        pauseFlag = pauseFlag,
        onStatus = onStatus,
        onProgress = onProgress,
        onPeerPaused = onPeerPaused,
        onPeerResumed = onPeerResumed,
        sockets = activeSockets
    )
    val actualWorkers = workerCount.coerceIn(1, 100)
    val done = CountDownLatch(actualWorkers)
    val error = AtomicReference<String?>(null)
    onStatus("Sending with $actualWorkers parallel file worker(s).")

    repeat(actualWorkers) { index ->
        kotlin.concurrent.thread(name = "LanDrop-Android-SendWorker-$index", isDaemon = true) {
            try {
                sendAndroidParallelWorker(
                    session = session,
                    workerId = index,
                    queue = pendingFiles,
                    error = error
                )
            } catch (failure: Throwable) {
                val message = "Worker $index: ${failure.message ?: failure.javaClass.simpleName}"
                AppLog.add(message)
                android.util.Log.e("LanDropTransfer", message, failure)
                error.compareAndSet(null, message)
                cancelFlag.set(true)
            } finally {
                done.countDown()
            }
        }
    }

    done.await()
    error.get()?.let { message ->
        runCatching {
            writeControlMessage(output, flowControlMessage(transferId, "cancel_requested"), controlWriteLock)
        }
        return SendResult.Failure(message)
    }

    writeControlMessage(
        output,
        JSONObject()
            .put("type", "transfer_complete")
            .put("protocolVersion", 1)
            .put("transferId", transferId)
            .put("completedAtMs", System.currentTimeMillis()),
        controlWriteLock
    )
    output.flush()
    val receipt = readControlMessage(input)
    if (receipt.optString("type") != "transfer_receipt" || !receipt.optBoolean("success")) {
        return SendResult.Failure(receipt.optString("message", "Receiver did not confirm completion."))
    }
    return SendResult.Success
}

private fun sendAndroidParallelWorker(
    session: AndroidParallelSendSession,
    workerId: Int,
    queue: ConcurrentLinkedQueue<TransferFile>,
    error: AtomicReference<String?>
) {
    val socket = Socket()
    socket.tcpNoDelay = true
    // Match the receiver's large buffers so transfers start fast.
    runCatching { socket.sendBufferSize = 2 * 1024 * 1024 }
    runCatching { socket.receiveBufferSize = 2 * 1024 * 1024 }
    session.sockets?.add(socket)
    socket.connect(InetSocketAddress(session.peer.ip, session.peer.port), 5000)
    socket.soTimeout = 300_000
    try {
        socket.use {
        val input = DataInputStream(it.getInputStream())
        val output = DataOutputStream(it.getOutputStream())
        val workerLock = Any()
        writeControlMessage(
            output,
            JSONObject()
                .put("type", "parallel_worker_open")
                .put("protocolVersion", 2)
                .put("transferId", session.transferId)
                .put("workerId", workerId)
                .put("openedAtMs", System.currentTimeMillis()),
            workerLock
        )
        val ready = readControlMessage(input)
        if (ready.optString("type") != "parallel_worker_ready" ||
            !ready.optBoolean("accepted") ||
            ready.optString("transferId") != session.transferId
        ) {
            error.compareAndSet(null, ready.optString("message", "Peer rejected worker $workerId."))
            return
        }

        while (!session.cancelFlag.get()) {
            val file = queue.poll() ?: break
            session.activeFiles[workerId] = file.relativePath
            android.util.Log.i("LanDropTransfer", "Worker $workerId sending ${file.relativePath} (${file.sizeBytes} bytes)")
            try {
                sendAndroidParallelFile(
                    session = session,
                    input = input,
                    output = output,
                    workerLock = workerLock,
                    workerId = workerId,
                    file = file
                )
            } finally {
                session.activeFiles.remove(workerId)
            }
        }

        if (session.cancelFlag.get()) {
            writeControlMessage(output, flowControlMessage(session.transferId, "cancel_requested"), workerLock)
            error.compareAndSet(null, "Transfer canceled by user.")
            return
        }

        writeControlMessage(
            output,
            JSONObject()
                .put("type", "parallel_worker_complete")
                .put("protocolVersion", 2)
                .put("transferId", session.transferId)
                .put("workerId", workerId)
                .put("completedAtMs", System.currentTimeMillis()),
            workerLock
        )
        }
    } finally {
        session.sockets?.remove(socket)
    }
}

private fun sendAndroidParallelFile(
    session: AndroidParallelSendSession,
    input: DataInputStream,
    output: DataOutputStream,
    workerLock: Any,
    workerId: Int,
    file: TransferFile
) {
    val buffer = ByteArray(1024 * 1024)
    val relativePath = file.relativePath
    val size = file.sizeBytes
    val offset = session.offsets[relativePath]?.coerceAtMost(size) ?: 0L
    if (offset >= size) return

    writeControlMessage(
        output,
        JSONObject()
            .put("type", "file_start")
            .put("protocolVersion", 1)
            .put("transferId", session.transferId)
            .put("relativePath", relativePath)
            .put("size", size)
            .put("offset", offset),
        workerLock
    )

    useTransferSource(openTransferInputStream(session.context, file), relativePath) { raw ->
        val stream = BufferedInputStream(raw, buffer.size)
        skipFully(stream, offset)
        var remaining = size - offset
        var currentOffset = offset
        var sequence = 0L
        var lastProgressBytes = session.completedBytes.get()
        var lastProgressAtMs = System.currentTimeMillis()
        var lastUiAtMs = 0L
        val pendingAcks = ArrayDeque<PendingChunkAck>()
        while (remaining > 0) {
            if (session.cancelFlag.get()) error("Transfer canceled by user.")
            if (session.pauseFlag.get()) {
                flushPendingChunkAcks(
                    input = input,
                    output = output,
                    controlWriteLock = workerLock,
                    transferId = session.transferId,
                    pending = pendingAcks,
                    cancelFlag = session.cancelFlag,
                    pauseFlag = session.pauseFlag,
                    onStatus = session.onStatus,
                    onPeerPaused = session.onPeerPaused,
                    onPeerResumed = session.onPeerResumed
                )?.let { error(it) }
                var pausedNoticeSent = false
                while (session.pauseFlag.get()) {
                    if (!pausedNoticeSent) {
                        writeControlMessage(output, flowControlMessage(session.transferId, "paused"), workerLock)
                        pausedNoticeSent = true
                    }
                    if (session.cancelFlag.get()) error("Transfer canceled by user.")
                    if (input.available() > 0) {
                        val control = readControlMessage(input)
                        when (control.optString("type")) {
                            "file_chunk_ack" -> {
                                val expected = pendingAcks.peekFirst()
                                    ?: error("Received chunk acknowledgement with no pending chunks.")
                                val ok = control.optString("transferId") == session.transferId &&
                                    control.optString("relativePath") == expected.relativePath &&
                                    control.optLong("nextOffset") == expected.nextOffset &&
                                    control.optLong("sequence") == expected.sequence
                                if (!ok) error("Receiver acknowledged the wrong file chunk.")
                                pendingAcks.removeFirst()
                            }
                            "transfer_flow_control" -> {
                                if (control.optString("transferId") == session.transferId &&
                                    control.optString("state") == "resumed"
                                ) {
                                    session.pauseFlag.set(false)
                                    session.onPeerResumed()
                                }
                                if (control.optString("state") == "cancel_requested") {
                                    error("Transfer canceled by receiver.")
                                }
                            }
                            else -> error("Unexpected frame while paused.")
                        }
                    } else {
                        Thread.sleep(120)
                    }
                    lastProgressAtMs = System.currentTimeMillis()
                }
                if (pausedNoticeSent) {
                    writeControlMessage(output, flowControlMessage(session.transferId, "resumed"), workerLock)
                }
            }

            val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) error("Worker $workerId could not finish reading $relativePath.")
            synchronized(workerLock) {
                writeControlMessage(
                    output,
                    JSONObject()
                        .put("type", "file_chunk")
                        .put("protocolVersion", 2)
                        .put("transferId", session.transferId)
                        .put("relativePath", relativePath)
                        .put("offset", currentOffset)
                        .put("length", read)
                        .put("sequence", sequence)
                )
                output.write(buffer, 0, read)
                output.flush()
            }

            val ackResult = noteChunkSentAndManageAckWindow(
                input = input,
                output = output,
                controlWriteLock = workerLock,
                transferId = session.transferId,
                pending = pendingAcks,
                ack = PendingChunkAck(
                    relativePath = relativePath,
                    nextOffset = currentOffset + read,
                    sequence = sequence
                ),
                cancelFlag = session.cancelFlag,
                pauseFlag = session.pauseFlag,
                onStatus = session.onStatus,
                onPeerPaused = {
                    session.onPeerPaused()
                    session.onProgress(
                        SendProgress(
                            peerName = session.peer.name,
                            currentFile = relativePath,
                            completedBytes = session.completedBytes.get(),
                            totalBytes = session.totalBytes,
                            speedMbps = 0.0,
                            active = false,
                            fileCount = session.files.size,
                            currentFiles = activeFileNames(session.activeFiles)
                        )
                    )
                },
                onPeerResumed = {
                    session.onPeerResumed()
                    session.onProgress(
                        SendProgress(
                            peerName = session.peer.name,
                            currentFile = relativePath,
                            completedBytes = session.completedBytes.get(),
                            totalBytes = session.totalBytes,
                            speedMbps = 0.0,
                            active = true,
                            fileCount = session.files.size,
                            currentFiles = activeFileNames(session.activeFiles)
                        )
                    )
                }
            )
            ackResult?.let { error(it) }

            val completed = session.completedBytes.addAndGet(read.toLong())
            remaining -= read
            currentOffset += read
            val nowMs = System.currentTimeMillis()
            // 1-second average: measure only across UI emits so the
            // readout holds steady instead of flickering per chunk.
            if (nowMs - lastUiAtMs >= 1000 || remaining <= 0) {
                val elapsedMs = (nowMs - lastProgressAtMs).coerceAtLeast(1L)
                val deltaBytes = completed - lastProgressBytes
                val speedMbps = (deltaBytes.toDouble() * 8.0) / elapsedMs.toDouble() / 1000.0
                lastProgressBytes = completed
                lastProgressAtMs = nowMs
                lastUiAtMs = nowMs
                session.onStatus("Sending $relativePath ${progressPercent(completed, session.totalBytes)}")
                session.onProgress(
                    SendProgress(
                        peerName = session.peer.name,
                        currentFile = relativePath,
                        completedBytes = completed.coerceAtMost(session.totalBytes),
                        totalBytes = session.totalBytes,
                        speedMbps = speedMbps,
                        active = !session.pauseFlag.get(),
                        fileCount = session.files.size,
                        currentFiles = activeFileNames(session.activeFiles)
                    )
                )
            }
            sequence += 1
        }

        flushPendingChunkAcks(
            input = input,
            output = output,
            controlWriteLock = workerLock,
            transferId = session.transferId,
            pending = pendingAcks,
            cancelFlag = session.cancelFlag,
            pauseFlag = session.pauseFlag,
            onStatus = session.onStatus,
            onPeerPaused = session.onPeerPaused,
            onPeerResumed = session.onPeerResumed
        )?.let { error(it) }
    }

    writeControlMessage(
        output,
        JSONObject()
            .put("type", "file_end")
            .put("protocolVersion", 2)
            .put("transferId", session.transferId)
            .put("relativePath", relativePath)
            .put("size", size)
            .put("completedAtMs", System.currentTimeMillis()),
        workerLock
    )
}

private fun activeFileNames(activeFiles: ConcurrentHashMap<Int, String>): List<String> =
    activeFiles.entries
        .sortedBy { it.key }
        .map { it.value }

private fun flowControlMessage(transferId: String, state: String): JSONObject {
    return JSONObject()
        .put("type", "transfer_flow_control")
        .put("protocolVersion", 2)
        .put("transferId", transferId)
        .put("state", state)
        .put("reason", "user")
        .put("sentAtMs", System.currentTimeMillis())
}

internal fun noteChunkSentAndManageAckWindow(
    input: DataInputStream,
    output: DataOutputStream,
    controlWriteLock: Any,
    transferId: String,
    pending: ArrayDeque<PendingChunkAck>,
    ack: PendingChunkAck,
    cancelFlag: AtomicBoolean,
    pauseFlag: AtomicBoolean,
    onStatus: (String) -> Unit,
    onPeerPaused: () -> Unit,
    onPeerResumed: () -> Unit
): String? {
    // Register before reading: Windows may already have acknowledged this chunk.
    pending.addLast(ack)
    drainReadyChunkAcks(
        input = input,
        output = output,
        controlWriteLock = controlWriteLock,
        transferId = transferId,
        pending = pending,
        cancelFlag = cancelFlag,
        pauseFlag = pauseFlag,
        onStatus = onStatus,
        onPeerPaused = onPeerPaused,
        onPeerResumed = onPeerResumed
    )?.let { return it }
    while (pending.size >= CHUNK_ACK_WINDOW) {
        waitForNextChunkAck(
            input = input,
            output = output,
            controlWriteLock = controlWriteLock,
            transferId = transferId,
            pending = pending,
            cancelFlag = cancelFlag,
            pauseFlag = pauseFlag,
            onStatus = onStatus,
            onPeerPaused = onPeerPaused,
            onPeerResumed = onPeerResumed
        )?.let { return it }
    }
    return null
}

private fun flushPendingChunkAcks(
    input: DataInputStream,
    output: DataOutputStream,
    controlWriteLock: Any,
    transferId: String,
    pending: ArrayDeque<PendingChunkAck>,
    cancelFlag: AtomicBoolean,
    pauseFlag: AtomicBoolean,
    onStatus: (String) -> Unit,
    onPeerPaused: () -> Unit,
    onPeerResumed: () -> Unit
): String? {
    while (pending.isNotEmpty()) {
        if (cancelFlag.get()) {
            writeControlMessage(output, flowControlMessage(transferId, "cancel_requested"), controlWriteLock)
            return "Transfer canceled by user."
        }
        waitForNextChunkAck(
            input = input,
            output = output,
            controlWriteLock = controlWriteLock,
            transferId = transferId,
            pending = pending,
            cancelFlag = cancelFlag,
            pauseFlag = pauseFlag,
            onStatus = onStatus,
            onPeerPaused = onPeerPaused,
            onPeerResumed = onPeerResumed
        )?.let { return it }
    }
    return null
}

private fun drainReadyChunkAcks(
    input: DataInputStream,
    output: DataOutputStream,
    controlWriteLock: Any,
    transferId: String,
    pending: ArrayDeque<PendingChunkAck>,
    cancelFlag: AtomicBoolean,
    pauseFlag: AtomicBoolean,
    onStatus: (String) -> Unit,
    onPeerPaused: () -> Unit,
    onPeerResumed: () -> Unit
): String? {
    while (input.available() > 0) {
        val message = readControlMessage(input)
        when (val result = applyAckOrFlowControlMessage(
            message = message,
            input = input,
            output = output,
            controlWriteLock = controlWriteLock,
            transferId = transferId,
            pending = pending,
            cancelFlag = cancelFlag,
            pauseFlag = pauseFlag,
            onStatus = onStatus,
            onPeerPaused = onPeerPaused,
            onPeerResumed = onPeerResumed
        )) {
            is AckApplyResult.Error -> return result.message
            AckApplyResult.AckConsumed, AckApplyResult.Continue -> Unit
        }
    }
    return null
}

private fun waitForNextChunkAck(
    input: DataInputStream,
    output: DataOutputStream,
    controlWriteLock: Any,
    transferId: String,
    pending: ArrayDeque<PendingChunkAck>,
    cancelFlag: AtomicBoolean,
    pauseFlag: AtomicBoolean,
    onStatus: (String) -> Unit,
    onPeerPaused: () -> Unit,
    onPeerResumed: () -> Unit
): String? {
    while (true) {
        if (cancelFlag.get()) {
            writeControlMessage(output, flowControlMessage(transferId, "cancel_requested"), controlWriteLock)
            return "Transfer canceled by user."
        }
        val message = try {
            readControlMessage(input)
        } catch (error: Throwable) {
            if (cancelFlag.get()) {
                return "Transfer canceled by user."
            }
            throw error
        }
        when (val result = applyAckOrFlowControlMessage(
            message = message,
            input = input,
            output = output,
            controlWriteLock = controlWriteLock,
            transferId = transferId,
            pending = pending,
            cancelFlag = cancelFlag,
            pauseFlag = pauseFlag,
            onStatus = onStatus,
            onPeerPaused = onPeerPaused,
            onPeerResumed = onPeerResumed
        )) {
            is AckApplyResult.Error -> return result.message
            AckApplyResult.AckConsumed -> return null
            AckApplyResult.Continue -> Unit
        }
    }
}

private sealed class AckApplyResult {
    data object AckConsumed : AckApplyResult()
    data object Continue : AckApplyResult()
    data class Error(val message: String) : AckApplyResult()
}

private fun applyAckOrFlowControlMessage(
    message: JSONObject,
    input: DataInputStream,
    output: DataOutputStream,
    controlWriteLock: Any,
    transferId: String,
    pending: ArrayDeque<PendingChunkAck>,
    cancelFlag: AtomicBoolean,
    pauseFlag: AtomicBoolean,
    onStatus: (String) -> Unit,
    onPeerPaused: () -> Unit,
    onPeerResumed: () -> Unit
): AckApplyResult {
    return when (message.optString("type")) {
        "file_chunk_ack" -> {
            if (message.optString("transferId") != transferId) {
                return AckApplyResult.Error("Receiver acknowledged the wrong transfer.")
            }
            val path = message.optString("relativePath")
            val sequence = message.optLong("sequence")
            val before = pending.size
            pending.removeAll { entry ->
                entry.relativePath == path && entry.sequence <= sequence
            }
            if (pending.size < before) AckApplyResult.AckConsumed else AckApplyResult.Continue
        }
        "transfer_flow_control" -> {
            if (message.optString("transferId") != transferId) {
                return AckApplyResult.Error("Receiver sent flow control for the wrong transfer.")
            }
            when (message.optString("state")) {
                "paused" -> {
                    onStatus("Receiver paused the transfer.")
                    onPeerPaused()
                    pauseFlag.set(true)
                    var resumedSent = false
                    while (true) {
                        if (cancelFlag.get()) {
                            writeControlMessage(output, flowControlMessage(transferId, "cancel_requested"), controlWriteLock)
                            return AckApplyResult.Error("Transfer canceled by user.")
                        }
                        if (!pauseFlag.get() && !resumedSent) {
                            writeControlMessage(output, flowControlMessage(transferId, "resumed"), controlWriteLock)
                            resumedSent = true
                            onStatus("Asked receiver to resume.")
                        }
                        if (input.available() > 0) {
                            val resume = readControlMessage(input)
                            when (resume.optString("type")) {
                                "file_chunk_ack" -> {
                                    if (resume.optString("transferId") == transferId) {
                                        val path = resume.optString("relativePath")
                                        val sequence = resume.optLong("sequence")
                                        pending.removeAll { entry ->
                                            entry.relativePath == path && entry.sequence <= sequence
                                        }
                                    }
                                    // Do NOT treat ACK as resume — keep waiting for flow control.
                                }
                                "transfer_flow_control" -> {
                                    if (resume.optString("transferId") != transferId) {
                                        return AckApplyResult.Error(
                                            "Receiver sent flow control for the wrong transfer."
                                        )
                                    }
                                    when (resume.optString("state")) {
                                        "resumed" -> {
                                            pauseFlag.set(false)
                                            onStatus("Receiver resumed the transfer.")
                                            onPeerResumed()
                                            return AckApplyResult.Continue
                                        }
                                        "paused" -> onStatus("Receiver is still paused.")
                                        "cancel_requested" ->
                                            return AckApplyResult.Error("Transfer canceled by receiver.")
                                    }
                                }
                                else -> return AckApplyResult.Error(
                                    "Unexpected frame while waiting for receiver resume."
                                )
                            }
                        } else {
                            Thread.sleep(120)
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    AckApplyResult.Continue
                }
                "resumed" -> {
                    onStatus("Receiver resumed the transfer.")
                    onPeerResumed()
                    AckApplyResult.Continue
                }
                "cancel_requested" -> AckApplyResult.Error("Transfer canceled by receiver.")
                else -> AckApplyResult.Continue
            }
        }
        else -> AckApplyResult.Error("Expected receiver chunk acknowledgement.")
    }
}

private fun writeControlMessage(output: DataOutputStream, json: JSONObject, lock: Any? = null) {
    if (lock != null) {
        synchronized(lock) {
            writeControlMessage(output, json)
        }
        return
    }
    val payload = json.toString().toByteArray(Charsets.UTF_8)
    output.writeInt(payload.size)
    output.write(payload)
    output.flush()
}

private fun readControlMessage(input: DataInputStream): JSONObject {
    val length = input.readInt()
    require(length in 0..(16 * 1024 * 1024)) { "Invalid control message size." }
    val payload = ByteArray(length)
    input.readFully(payload)
    return JSONObject(String(payload, Charsets.UTF_8))
}

private fun skipFully(input: BufferedInputStream, bytes: Long) {
    var remaining = bytes
    while (remaining > 0) {
        val skipped = input.skip(remaining)
        if (skipped <= 0) {
            if (input.read() == -1) break else remaining -= 1
        } else {
            remaining -= skipped
        }
    }
}

private fun safeRelativeName(name: String): String {
    return name
        .replace('\\', '_')
        .replace('/', '_')
        .ifBlank { "file" }
}

private fun progressPercent(done: Long, total: Long): String {
    if (total <= 0) return ""
    return "${((done.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)}%"
}

private fun wifiDirectPermissionName(): String {
    return if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }
}

private fun isLoopbackHost(host: String): Boolean {
    val trimmed = host.trim().trim('[', ']').substringBefore(':').trim()
    if (trimmed.equals("localhost", ignoreCase = true)) return true
    if (trimmed == "::1") return true
    if (trimmed.startsWith("127.")) return true
    return runCatching {
        java.net.InetAddress.getByName(trimmed).isLoopbackAddress
    }.getOrDefault(false)
}

private fun isVpnOrTailscaleIp(ip: String): Boolean {
    val trimmed = ip.trim()
    if (trimmed.startsWith("100.")) {
        // Tailscale / Headscale use 100.64.0.0/10 (100.64.x - 100.127.x).
        val second = trimmed.split('.').getOrNull(1)?.toIntOrNull()
        if (second != null && second in 64..127) return true
    }
    return false
}

private fun openTransferInputStream(context: Context, file: TransferFile): java.io.InputStream? {
    return try {
        context.contentResolver.openInputStream(file.uri)
    } catch (error: SecurityException) {
        AppLog.add("Cannot open ${file.relativePath}: ${error.message}")
        android.util.Log.e("LanDropStorage", "Selected-file access denied for ${file.relativePath}", error)
        throw IllegalStateException(
            "Android denied access to ${file.relativePath}. Select it again with the file picker.",
            error
        )
    }
}

private data class ExpandedSelection(
    val directories: List<TransferDirectory>,
    val files: List<TransferFile>
)

private fun expandPickedItems(context: Context, items: List<PickedItem>, cancelFlag: AtomicBoolean): ExpandedSelection {
    val directories = mutableListOf<TransferDirectory>()
    val files = mutableListOf<TransferFile>()

    items.forEach { item ->
        check(!cancelFlag.get()) { "Transfer canceled by user." }
        if (item.kind == "Folder") {
            val rootUri = Uri.parse(item.uri)
            val rootName = safeRelativeName(item.name)
            directories += TransferDirectory(rootName, null)
            walkDocumentTree(
                context = context,
                treeUri = rootUri,
                documentId = DocumentsContract.getTreeDocumentId(rootUri),
                relativeRoot = rootName,
                directories = directories,
                files = files,
                cancelFlag = cancelFlag
            )
        } else {
            files += TransferFile(
                relativePath = safeRelativeName(item.name),
                uri = Uri.parse(item.uri),
                sizeBytes = item.sizeBytes ?: contentLength(context, Uri.parse(item.uri)),
                modifiedAtMs = null
            )
        }
    }

    return ExpandedSelection(
        directories = directories.distinctBy { it.relativePath },
        files = files
    )
}

private fun walkDocumentTree(
    context: Context,
    treeUri: Uri,
    documentId: String,
    relativeRoot: String,
    directories: MutableList<TransferDirectory>,
    files: MutableList<TransferFile>,
    cancelFlag: AtomicBoolean
) {
    check(!cancelFlag.get()) { "Transfer canceled by user." }
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )

    context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

        while (cursor.moveToNext()) {
            check(!cancelFlag.get()) { "Transfer canceled by user." }
            val childId = cursor.getString(idIndex)
            val name = safeRelativeName(cursor.getString(nameIndex) ?: "item")
            val mime = cursor.getString(mimeIndex)
            val modifiedAtMs = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                cursor.getLong(modifiedIndex)
            } else {
                null
            }
            val relativePath = "$relativeRoot/$name"

            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                directories += TransferDirectory(relativePath, modifiedAtMs)
                walkDocumentTree(
                    context = context,
                    treeUri = treeUri,
                    documentId = childId,
                    relativeRoot = relativePath,
                    directories = directories,
                    files = files,
                    cancelFlag = cancelFlag
                )
            } else {
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex)
                } else {
                    contentLength(context, documentUri)
                }
                files += TransferFile(
                    relativePath = relativePath,
                    uri = documentUri,
                    sizeBytes = size,
                    modifiedAtMs = modifiedAtMs
                )
            }
        }
    }
}

private fun contentLength(context: Context, uri: Uri): Long {
    return context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
        descriptor.length.takeIf { it >= 0 }
    } ?: throw IllegalStateException("Could not read size for $uri")
}

@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF7DE4D7), Color(0xFF86A8FF))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text("L", color = Color(0xFF021112), fontWeight = FontWeight.Black, fontSize = 22.sp)
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun OnboardingScreen(
    saveFolderName: String?,
    folderError: String?,
    onChooseSaveFolder: () -> Unit,
    onComplete: () -> Unit
) {
    var page by remember { mutableStateOf(0) }
    val pages = listOf(
        Triple(
            "Local transfer",
            "LanDrop sends files directly over your local network. No account, cloud, or mobile data is needed.",
            Icons.Rounded.CompareArrows
        ),
        Triple(
            "File access",
            "Android's file picker gives LanDrop access only to the files and folders you choose.",
            Icons.Rounded.Description
        ),
        Triple(
            "Choose what to share",
            "Select files or folders with Android's file picker. LanDrop streams them directly without ZIP archives or temporary copies.",
            Icons.Rounded.Security
        ),
        Triple(
            "Choose a save folder",
            "Select where incoming files will be saved. You can change this folder later on the Receive screen.",
            Icons.Rounded.Folder
        )
    )
    val heroScale by animateFloatAsState(
        targetValue = if (page == pages.lastIndex) 1.08f else 1f,
        animationSpec = spring(),
        label = "onboardingHeroScale"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text("LanDrop", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            AnimatedContent(
                targetState = page,
                modifier = Modifier.weight(1f).padding(vertical = 20.dp),
                label = "onboardingPage"
            ) { targetPage ->
                val animatedPage = pages[targetPage]
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .size(88.dp)
                            .scale(heroScale),
                        shape = RoundedCornerShape(30.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                animatedPage.third,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(42.dp)
                            )
                        }
                    }
                    Text(animatedPage.first, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(
                        animatedPage.second,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp,
                        lineHeight = 23.sp
                    )
                    if (targetPage == pages.lastIndex) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("Save incoming files", fontWeight = FontWeight.Bold)
                                Text(
                                    "Android grants LanDrop access only to the receive folder you select.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                )
                                OutlinedButton(onClick = onChooseSaveFolder, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Rounded.Folder, contentDescription = null)
                                    Spacer(Modifier.size(8.dp))
                                    Text(saveFolderName ?: "Choose save folder", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                folderError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pages.indices.forEach { index ->
                        AnimatedPageDot(selected = index == page)
                    }
                }
                Button(
                    onClick = {
                        if (page < pages.lastIndex) {
                            page += 1
                        } else {
                            onComplete()
                        }
                    },
                    enabled = page < pages.lastIndex || saveFolderName != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (page < pages.lastIndex) "Next" else "Start using LanDrop"
                    )
                }
            }
        }
    }
}

@Composable
private fun AnimatedPageDot(selected: Boolean) {
    val width by animateDpAsState(
        targetValue = if (selected) 32.dp else 10.dp,
        animationSpec = spring(),
        label = "onboardingDotWidth"
    )
    val color by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(220),
        label = "onboardingDotColor"
    )
    Box(
        modifier = Modifier
            .height(7.dp)
            .width(width)
            .clip(RoundedCornerShape(999.dp))
            .background(color)
    )
}

@Composable
private fun IncomingOfferDialog(
    offer: IncomingOfferPrompt,
    saveFolderName: String?,
    folderError: String?,
    onChooseSaveFolder: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        icon = {
            Icon(Icons.Rounded.Download, contentDescription = null)
        },
        title = {
            Text("Incoming transfer")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${offer.senderName} wants to send ${offer.fileCount} file(s)" +
                        if (offer.directoryCount > 0) " and ${offer.directoryCount} folder(s)." else "."
                )
                Text(
                    "${formatBytesApprox(offer.totalBytes)} • ${linkLabelForIp(offer.senderIp)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                if (saveFolderName == null) {
                    Text("Choose a save folder to accept this transfer.")
                    OutlinedButton(onClick = onChooseSaveFolder) { Text("Choose save folder") }
                } else {
                    Text("Save to $saveFolderName", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                folderError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept, enabled = saveFolderName != null) {
                Text("Accept")
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text("Reject")
            }
        }
    )
}

@Composable
private fun IncomingOfferInlineCard(
    offer: IncomingOfferPrompt,
    saveFolderName: String?,
    onChooseSaveFolder: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.width(310.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Incoming transfer", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "${offer.senderName} wants to send ${offer.fileCount} file(s)" +
                    if (offer.directoryCount > 0) " and ${offer.directoryCount} folder(s)." else ".",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Text(
                "${formatBytesApprox(offer.totalBytes)} • ${linkLabelForIp(offer.senderIp)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = if (saveFolderName == null) onChooseSaveFolder else onAccept, modifier = Modifier.weight(1f)) {
                    Text(if (saveFolderName == null) "Choose save folder" else "Accept")
                }
                OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) {
                    Text("Reject")
                }
            }
        }
    }
}

@Composable
private fun ReceiveHistoryPanel(
    history: List<ReceiveHistoryItem>,
    onDelete: (ReceiveHistoryItem) -> Unit
) {
    val visibleHistory = history.take(20)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(620.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Receive history", fontWeight = FontWeight.Bold, fontSize = 28.sp)
            if (history.isEmpty()) {
                HistoryEmptyState(
                    icon = Icons.Rounded.Download,
                    text = "Received files will appear here."
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        count = visibleHistory.size,
                        key = { index ->
                            val item = visibleHistory[index]
                            "${item.timestampMs}:${item.title}:${item.detail}"
                        }
                    ) { index ->
                        val item = visibleHistory[index]
                        DismissibleHistoryRow(
                            onDelete = { onDelete(item) }
                        ) {
                            HistoryRow(
                                icon = Icons.Rounded.Download,
                                title = item.title,
                                detail = item.detail
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SendHistoryPanel(
    history: List<SendHistoryItem>,
    onDelete: (SendHistoryItem) -> Unit
) {
    val visibleHistory = history.take(20)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(620.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Send history", fontWeight = FontWeight.Bold, fontSize = 28.sp)
            if (history.isEmpty()) {
                HistoryEmptyState(
                    icon = Icons.Rounded.Send,
                    text = "Sent files will appear here."
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        count = visibleHistory.size,
                        key = { index ->
                            val item = visibleHistory[index]
                            "${item.timestampMs}:${item.title}:${item.detail}"
                        }
                    ) { index ->
                        val item = visibleHistory[index]
                        DismissibleHistoryRow(
                            onDelete = { onDelete(item) }
                        ) {
                            HistoryRow(
                                icon = Icons.Rounded.Send,
                                title = item.title,
                                detail = item.detail
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryEmptyState(icon: ImageVector, text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DismissibleHistoryRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState()

    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)),
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "Delete",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        onDismiss = { direction ->
            if (direction == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
            }
        }
    ) {
        content()
    }
}

@Composable
private fun HistoryRow(icon: ImageVector, title: String, detail: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun MaterialLoadingMark(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    centerText: String = "",
    large: Boolean = false,
    progress: Float? = null
) {
    val heroTransition = rememberInfiniteTransition(label = "hero-loading")
    val heroProgress by heroTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200),
            repeatMode = RepeatMode.Restart
        ),
        label = "hero-progress"
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            !active -> LoadingIndicator(
                progress = { progress ?: 0f },
                modifier = Modifier
                    .matchParentSize()
                    .scale(0.86f),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
                polygons = LoadingIndicatorDefaults.DeterminateIndicatorPolygons
            )
            large -> LoadingIndicator(
                progress = { progress ?: heroProgress },
                modifier = Modifier
                    .matchParentSize()
                    .align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
                polygons = LoadingIndicatorDefaults.DeterminateIndicatorPolygons +
                    LoadingIndicatorDefaults.DeterminateIndicatorPolygons.first()
            )
            else -> LoadingIndicator(
                modifier = Modifier.matchParentSize(),
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (centerText.isNotBlank()) {
            Surface(
                modifier = Modifier.size(if (large) 88.dp else 22.dp),
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.94f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        centerText,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = if (active) 1f else 0.42f),
                        fontWeight = FontWeight.Black,
                        fontSize = if (large) 42.sp else 10.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceiveHomeScreen(
    deviceName: String,
    centerInitial: String,
    receiveEnabled: Boolean,
    pairCodeEnabled: Boolean,
    pairCode: String,
    saveFolderName: String?,
    quickSaveMode: QuickSaveMode,
    receiveProgress: ReceiveProgress?,
    pendingIncomingOffer: IncomingOfferPrompt?,
    onChooseSaveFolder: () -> Unit,
    onQuickSaveModeChange: (QuickSaveMode) -> Unit,
    onAcceptIncoming: () -> Unit,
    onRejectIncoming: () -> Unit,
    onCancelReceive: () -> Unit,
    onPauseReceive: () -> Unit,
    onToggleReceive: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(720.dp)
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1.35f))

        val activeReceive = receiveProgress
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (activeReceive != null) 500.dp else 500.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            item {
                ReceiveHeroPage(
                    deviceName = deviceName,
                    centerInitial = centerInitial,
                    receiveEnabled = receiveEnabled,
                    pairCodeEnabled = pairCodeEnabled,
                    pairCode = pairCode,
                    receiveProgress = activeReceive
                )
            }
            if (activeReceive != null) {
                item {
                    ReceiveDetailPage(progress = activeReceive)
                }
            }
        }

        pendingIncomingOffer?.let { offer ->
            Spacer(Modifier.height(18.dp))
            IncomingOfferInlineCard(
                offer = offer,
                saveFolderName = saveFolderName,
                onChooseSaveFolder = onChooseSaveFolder,
                onAccept = onAcceptIncoming,
                onReject = onRejectIncoming
            )
        }

        receiveProgress?.let { progress ->
            Spacer(Modifier.height(12.dp))
            ReceiveInlineControls(
                progress = progress,
                onPause = onPauseReceive,
                onCancel = onCancelReceive
            )
        }

        Spacer(Modifier.weight(1.7f))

        Text(
            "Quick Save",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp
        )
        Spacer(Modifier.height(14.dp))
        QuickSaveControl(mode = quickSaveMode, onModeChange = onQuickSaveModeChange)
        Spacer(Modifier.height(12.dp))
        ReceiveModeControl(receiveEnabled = receiveEnabled, onToggleReceive = onToggleReceive)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onChooseSaveFolder,
            modifier = Modifier
                .width(310.dp)
                .height(48.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Icon(Icons.Rounded.Folder, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(
                saveFolderName ?: "Choose save folder",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
private fun ReceiveHeroPage(
    deviceName: String,
    centerInitial: String,
    receiveEnabled: Boolean,
    pairCodeEnabled: Boolean,
    pairCode: String,
    receiveProgress: ReceiveProgress?
) {
    Column(
        modifier = Modifier
            .width(340.dp)
            .height(455.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(340.dp),
            contentAlignment = Alignment.Center
        ) {
            val receiveFraction = receiveProgress?.let { progress ->
                if (progress.totalBytes <= 0L) 0f else {
                    (progress.completedBytes.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
                }
            }
            if (receiveProgress != null && receiveFraction != null) {
                ReceiveHeroProgress(
                    fraction = receiveFraction,
                    active = receiveProgress.active
                )
            } else {
                MaterialLoadingMark(
                    active = receiveEnabled,
                    centerText = centerInitial,
                    modifier = Modifier.size(280.dp),
                    large = true
                )
            }
        }
        Spacer(Modifier.height(34.dp))
        Text(
            when {
                receiveProgress != null -> formatTransferLine(
                    receiveProgress.completedBytes,
                    receiveProgress.totalBytes,
                    receiveProgress.speedMbps
                )
                receiveEnabled -> deviceName
                else -> "Receiving off"
            },
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Light,
            fontSize = if (receiveProgress != null) 30.sp else 48.sp,
            lineHeight = if (receiveProgress != null) 34.sp else 54.sp,
            maxLines = if (receiveProgress != null) 1 else 2,
            overflow = TextOverflow.Ellipsis
        )
        if (pairCodeEnabled) {
            Text(
                "#$pairCode",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 30.sp
            )
        }
    }
}

@Composable
private fun ReceiveDetailPage(progress: ReceiveProgress) {
    Card(
        modifier = Modifier
            .width(310.dp)
            .height(380.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Ongoing files", fontWeight = FontWeight.Bold, fontSize = 24.sp)
            Text(
                "${progress.fileCount.coerceAtLeast(1)} file(s) • ${formatBytesApprox(progress.completedBytes)} / ${formatBytesApprox(progress.totalBytes)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Current files", fontWeight = FontWeight.Bold)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(progress.currentFiles.size) { index ->
                    Text(
                        progress.currentFiles[index],
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                formatTransferLine(progress.completedBytes, progress.totalBytes, progress.speedMbps),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReceiveHeroProgress(fraction: Float, active: Boolean) {
    val density = LocalDensity.current
    val thickStroke = remember(density) {
        Stroke(width = with(density) { 18.dp.toPx() }, cap = StrokeCap.Round)
    }
    Box(
        modifier = Modifier.size(304.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularWavyProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxSize(),
            color = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.56f)
            },
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            stroke = thickStroke,
            trackStroke = thickStroke
        )
        Text(
            "${(fraction * 100).toInt()}%",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Light,
            fontSize = 42.sp
        )
    }
}

@Composable
private fun ReceiveInlineControls(progress: ReceiveProgress, onPause: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.width(310.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Receiving from ${progress.peerName}",
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        progress.currentFiles.take(4).forEach { fileName ->
            Text(
                fileName,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onPause) {
                Text(if (progress.active) "Pause" else "Resume")
            }
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun QuickSaveControl(mode: QuickSaveMode, onModeChange: (QuickSaveMode) -> Unit) {
    val options = listOf(
        QuickSaveMode.Off to "Off",
        QuickSaveMode.Favorites to "Favorites",
        QuickSaveMode.On to "On"
    )
    // Recreate Material outlines when direction changes; cached paths can retain the old ends.
    key(LocalLayoutDirection.current) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = mode == option.first,
                    onClick = { onModeChange(option.first) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    ),
                    label = { Text(option.second, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
    }
}

@Composable
private fun ReceiveModeControl(receiveEnabled: Boolean, onToggleReceive: () -> Unit) {
    val options = listOf(false to "Receive off", true to "Receive on")
    // Recreate Material outlines when direction changes; cached paths can retain the old ends.
    key(LocalLayoutDirection.current) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = receiveEnabled == option.first,
                    onClick = {
                        if (receiveEnabled != option.first) {
                            onToggleReceive()
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    ),
                    label = { Text(option.second, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
    }
}

@Composable
private fun SendScreen(
    pickedItems: List<PickedItem>,
    connectedPeer: ManualPeer?,
    discoveredPeers: List<DiscoveredPeer>,
    vpnPeers: List<VpnSavedPeer> = emptyList(),
    remotePeer: ManualPeer? = null,
    wifiDirectLink: WifiDirectLink? = null,
    sendProgress: SendProgress?,
    sendInFlight: Boolean,
    outboundPairCode: String,
    onPairCodeChange: (String) -> Unit,
    onClearSelection: () -> Unit,
    onRemoveSelected: (PickedItem) -> Unit,
    onPauseSend: () -> Unit,
    onCancelSend: () -> Unit,
    onFiles: () -> Unit,
    onFolder: () -> Unit,
    onRefresh: () -> Unit,
    onPeerSelected: (DiscoveredPeer) -> Unit,
    onVpnPeerSelected: (VpnSavedPeer) -> Unit = {},
    onRemotePeerSelected: () -> Unit = {},
    onForgetVpnPeer: (VpnSavedPeer) -> Unit = {},
    onSend: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Send", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(
            "Choose what to send, then choose a device.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = onFiles,
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                Icon(Icons.Rounded.Description, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Files")
            }
            FilledTonalButton(
                onClick = onFolder,
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                Icon(Icons.Rounded.Folder, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Folder")
            }
        }

        sendProgress?.let { progress ->
            SendProgressPanel(progress = progress, onPause = onPauseSend, onCancel = onCancelSend)
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Nearby devices", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        val totalCount = unifiedNearbyCount(discoveredPeers, vpnPeers, remotePeer)
                        Text(
                            when {
                                totalCount == 0 -> "Searching your local network"
                                else -> "$totalCount found"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                }
                if (wifiDirectLink != null) {
                    val link = wifiDirectLink!!
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                if (link.isGroupOwner) "Wi-Fi Direct hosting ${link.groupOwnerAddress}"
                                else "Wi-Fi Direct linked ${link.groupOwnerAddress}"
                            )
                        }
                    )
                }
                if (discoveredPeers.isEmpty() && vpnPeers.isEmpty() && remotePeer == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MaterialLoadingMark(modifier = Modifier.size(32.dp))
                        Text(
                            "No devices yet. You can still connect by IP below.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    UnifiedNearbyPanel(
                        discoveredPeers = discoveredPeers,
                        vpnPeers = vpnPeers,
                        remotePeer = remotePeer,
                        connectedPeer = connectedPeer,
                        onPeerSelected = onPeerSelected,
                        onVpnPeerSelected = onVpnPeerSelected,
                        onRemotePeerSelected = onRemotePeerSelected,
                        onForgetVpnPeer = onForgetVpnPeer
                    )
                }
            }
        }

        StagedItemsPanel(
            items = pickedItems,
            connectedPeer = connectedPeer,
            sendInFlight = sendInFlight,
            outboundPairCode = outboundPairCode,
            onPairCodeChange = onPairCodeChange,
            onClearSelection = onClearSelection,
            onRemoveSelected = onRemoveSelected,
            onSend = onSend
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SendProgressPanel(progress: SendProgress, onPause: () -> Unit, onCancel: () -> Unit) {
    val fraction = if (progress.totalBytes <= 0L) {
        0f
    } else {
        val actual = (progress.completedBytes.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
        if (progress.completedBytes > 0L && actual < 0.01f) 0.01f else actual
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when (progress.phase) {
                            SendPhase.Preparing -> "Preparing files"
                            SendPhase.WaitingForAcceptance -> "Waiting for acceptance"
                            SendPhase.Canceling -> "Canceling send..."
                            SendPhase.Transferring -> "Sending to ${progress.peerName}"
                        },
                        fontWeight = FontWeight.Bold, fontSize = 20.sp
                    )
                    Text(
                        "${progress.fileCount.coerceAtLeast(1)} file(s) • ${formatBytesApprox(progress.completedBytes)} / ${formatBytesApprox(progress.totalBytes)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
                if (progress.phase == SendPhase.Transferring) Text(
                    formatTransferLine(progress.completedBytes, progress.totalBytes, progress.speedMbps),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
            LinearWavyProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                progress.currentFiles.forEach { fileName ->
                    Text(
                        fileName,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (progress.phase == SendPhase.Transferring) {
                    FilledTonalButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                        Text(if (progress.active) "Pause" else "Resume")
                    }
                }
                OutlinedButton(onClick = onCancel, enabled = progress.phase != SendPhase.Canceling, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun DiscoveredPeersPanel(
    peers: List<DiscoveredPeer>,
    selectedPeer: ManualPeer?,
    onPeerSelected: (DiscoveredPeer) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        peers.forEach { peer ->
            Card(
                onClick = { onPeerSelected(peer) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedPeer?.ip == peer.ip) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        peerDeviceIcon(peer.deviceKind),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(peer.name, fontWeight = FontWeight.Bold)
                        Text(
                            "${peer.ip}:${peer.port} • ${peer.availability}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(onClick = {}, label = { Text(peer.linkLabel) })
                            if (peer.requiresPairCode) {
                                AssistChip(onClick = {}, label = { Text("Code") })
                            }
                        }
                    }
                    if (selectedPeer?.ip == peer.ip) {
                        AssistChip(onClick = {}, label = { Text("Selected") })
                    }
                }
            }
        }
    }
}

private data class NearbyEntry(
    val key: String,
    val name: String,
    val addressLine: String,
    val linkType: String,
    val deviceKind: String,
    val availability: String? = null,
    val requiresPairCode: Boolean = false,
    val isSelected: Boolean = false,
    val canForget: Boolean = false,
    val onSelect: () -> Unit,
    val onForget: (() -> Unit)? = null
)

private fun unifiedNearbyCount(
    discoveredPeers: List<DiscoveredPeer>,
    vpnPeers: List<VpnSavedPeer>,
    remotePeer: ManualPeer?
): Int {
    return buildUnifiedNearbyEntries(
        discoveredPeers = discoveredPeers,
        vpnPeers = vpnPeers,
        remotePeer = remotePeer,
        connectedPeer = null,
        onPeerSelected = {},
        onVpnPeerSelected = {},
        onRemotePeerSelected = {},
        onForgetVpnPeer = {}
    ).size
}

private fun buildUnifiedNearbyEntries(
    discoveredPeers: List<DiscoveredPeer>,
    vpnPeers: List<VpnSavedPeer>,
    remotePeer: ManualPeer?,
    connectedPeer: ManualPeer?,
    onPeerSelected: (DiscoveredPeer) -> Unit,
    onVpnPeerSelected: (VpnSavedPeer) -> Unit,
    onRemotePeerSelected: () -> Unit,
    onForgetVpnPeer: (VpnSavedPeer) -> Unit
): List<NearbyEntry> {
    val entries = mutableListOf<NearbyEntry>()
    val seenDeviceIds = mutableSetOf<String>()
    val seenHosts = mutableSetOf<String>()
    // Live LAN / Wi-Fi Direct discoveries first, alphabetical so rows
    // never reshuffle on re-announce — only joining/leaving changes order.
    discoveredPeers.sortedWith(
        compareBy({ it.name.lowercase() }, { it.ip }, { it.port })
    ).forEach { peer ->
        val key = if (peer.deviceId.isNotBlank()) "id:${peer.deviceId}" else "ip:${peer.ip}:${peer.port}"
        if (peer.deviceId.isNotBlank()) seenDeviceIds += peer.deviceId
        seenHosts += peer.ip.trim().lowercase()
        entries += NearbyEntry(
            key = key,
            name = peer.name,
            addressLine = "${peer.ip}:${peer.port}",
            linkType = peer.linkLabel,
            deviceKind = peer.deviceKind,
            availability = peer.availability,
            requiresPairCode = peer.requiresPairCode,
            isSelected = connectedPeer?.ip == peer.ip && connectedPeer?.remote != true,
            onSelect = { onPeerSelected(peer) }
        )
    }
    // Saved VPN hosts, skipping anything already shown live (same device).
    vpnPeers.forEach { vpn ->
        val hostKey = vpn.host.trim().lowercase()
        val dupById = vpn.deviceId.isNotBlank() && vpn.deviceId in seenDeviceIds
        val dupByHost = hostKey in seenHosts
        // An active secure tunnel for the same device replaces the saved row.
        val dupByRemote = remotePeer != null &&
            vpn.deviceId.isNotBlank() && vpn.deviceId == remotePeer.deviceId
        if (dupById || dupByHost || dupByRemote) return@forEach
        if (vpn.deviceId.isNotBlank()) seenDeviceIds += vpn.deviceId
        seenHosts += hostKey
        entries += NearbyEntry(
            key = "vpn:$hostKey",
            name = vpn.name,
            addressLine = "${vpn.host}:${vpn.port}",
            linkType = "VPN",
            deviceKind = vpn.deviceKind,
            isSelected = connectedPeer?.ip == vpn.host,
            canForget = true,
            onSelect = { onVpnPeerSelected(vpn) },
            onForget = { onForgetVpnPeer(vpn) }
        )
    }
    // Active secure tunnel, unless its device is already listed above.
    remotePeer?.let { peer ->
        val dup = (peer.deviceId.isNotBlank() && peer.deviceId in seenDeviceIds) ||
            (peer.endpointLabel?.substringBefore(':')?.trim()?.lowercase() in seenHosts)
        if (!dup) {
            entries += NearbyEntry(
                key = "secure:${peer.deviceId.ifBlank { peer.endpointLabel ?: "tunnel" }}",
                name = peer.name,
                addressLine = peer.endpointLabel ?: "Encrypted tunnel",
                linkType = "Secure",
                deviceKind = peer.deviceKind,
                isSelected = connectedPeer?.remote == true,
                onSelect = onRemotePeerSelected
            )
        }
    }
    return entries
}

@Composable
private fun UnifiedNearbyPanel(
    discoveredPeers: List<DiscoveredPeer>,
    vpnPeers: List<VpnSavedPeer>,
    remotePeer: ManualPeer?,
    connectedPeer: ManualPeer?,
    onPeerSelected: (DiscoveredPeer) -> Unit,
    onVpnPeerSelected: (VpnSavedPeer) -> Unit,
    onRemotePeerSelected: () -> Unit,
    onForgetVpnPeer: (VpnSavedPeer) -> Unit
) {
    // Small list (tens of rows): rebuild each recomposition so tap
    // handlers never go stale.
    val entries = buildUnifiedNearbyEntries(
        discoveredPeers, vpnPeers, remotePeer, connectedPeer,
        onPeerSelected, onVpnPeerSelected, onRemotePeerSelected, onForgetVpnPeer
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEach { entry ->
            Card(
                onClick = entry.onSelect,
                colors = CardDefaults.cardColors(
                    containerColor = if (entry.isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        peerDeviceIcon(entry.deviceKind),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.name, fontWeight = FontWeight.Bold)
                        Text(
                            entry.addressLine,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AssistChip(onClick = entry.onSelect, label = { Text(entry.linkType) })
                            if (entry.requiresPairCode) {
                                AssistChip(onClick = entry.onSelect, label = { Text("Code") })
                            }
                            if (entry.isSelected) {
                                AssistChip(onClick = entry.onSelect, label = { Text("Selected") })
                            }
                        }
                    }
                    entry.onForget?.let { forget ->
                        TextButton(onClick = forget) { Text("Forget") }
                    }
                }
            }
        }
    }
}

@Composable
private fun VpnPeersPanel(
    peers: List<VpnSavedPeer>,
    selectedPeer: ManualPeer?,
    onPeerSelected: (VpnSavedPeer) -> Unit,
    onForget: (VpnSavedPeer) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        peers.forEach { peer ->
            Card(
                onClick = { onPeerSelected(peer) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedPeer?.ip == peer.host || selectedPeer?.ip == peer.host) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        peerDeviceIcon(peer.deviceKind),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(peer.name, fontWeight = FontWeight.Bold)
                        Text(
                            "${peer.host}:${peer.port}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(onClick = {}, label = { Text(if (peer.secure) "Secure VPN" else "VPN") })
                            AssistChip(onClick = { onForget(peer) }, label = { Text("Forget") })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StagedItemsPanel(
    items: List<PickedItem>,
    connectedPeer: ManualPeer?,
    sendInFlight: Boolean,
    outboundPairCode: String,
    onPairCodeChange: (String) -> Unit,
    onClearSelection: () -> Unit,
    onRemoveSelected: (PickedItem) -> Unit,
    onSend: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                    Text("Selected files", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(
                        if (items.isEmpty()) "Nothing selected yet" else "${items.size} ready to send",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
                if (items.isNotEmpty() && !sendInFlight) {
                    TextButton(onClick = onClearSelection) {
                        Text("Clear")
                    }
                }
            }
            if (items.isEmpty()) {
                EmptyPanel("Choose files or a folder above.")
            } else {
                items.take(4).forEach { item ->
                    DismissibleSelectedItemRow(
                        enabled = !sendInFlight,
                        onRemove = { onRemoveSelected(item) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (item.kind == "Folder") Icons.Rounded.Folder else Icons.Rounded.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    item.kind,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
            if (items.size > 4) {
                Text(
                    "+${items.size - 4} more",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
            if (connectedPeer != null) {
                OutlinedTextField(
                    value = outboundPairCode,
                    onValueChange = onPairCodeChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !sendInFlight,
                    label = { Text(if (connectedPeer.requiresPairCode) "Pair code required" else "Pair code if required") },
                    placeholder = { Text(if (connectedPeer.requiresPairCode) "Enter receiver code" else "Optional 6 digits") },
                    leadingIcon = { Icon(Icons.Rounded.Security, contentDescription = null) }
                )
            }
            FilledTonalButton(
                onClick = onSend,
                enabled = connectedPeer != null &&
                    items.isNotEmpty() &&
                    !sendInFlight &&
                    (connectedPeer.requiresPairCode.not() || outboundPairCode.length == 6) &&
                    (outboundPairCode.isBlank() || outboundPairCode.length == 6),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.Rounded.ArrowForward, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    when {
                        items.isEmpty() -> "Choose files"
                        connectedPeer == null -> "Choose a device"
                        sendInFlight -> "Send in progress"
                        else -> "Send to ${connectedPeer.name}"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DismissibleSelectedItemRow(
    enabled: Boolean,
    onRemove: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val removeThreshold = with(density) { 46.dp.toPx() }
    val maxReveal = with(density) { 112.dp.toPx() }
    var offsetX by remember { mutableStateOf(0f) }
    var dismissed by remember { mutableStateOf(false) }
    var dismissDirection by remember { mutableStateOf(-1f) }
    val animatedOffset by animateFloatAsState(
        targetValue = if (dismissed) dismissDirection * 720f else offsetX,
        animationSpec = spring(),
        label = "selectedItemOffset"
    )
    val active = abs(offsetX) >= removeThreshold * 0.55f || dismissed
    val backgroundColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        label = "selectedRemoveColor"
    )

    LaunchedEffect(dismissed) {
        if (dismissed) {
            delay(150)
            onRemove()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
    ) {
        Surface(
            modifier = Modifier.matchParentSize(),
            color = backgroundColor,
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = if (offsetX >= 0f) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Remove",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .then(
                    if (enabled) {
                        Modifier.draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                if (!dismissed) {
                                    offsetX = (offsetX + delta).coerceIn(-maxReveal, maxReveal)
                                }
                            },
                            onDragStopped = {
                                if (abs(offsetX) >= removeThreshold) {
                                    dismissDirection = if (offsetX >= 0f) 1f else -1f
                                    dismissed = true
                                } else {
                                    offsetX = 0f
                                }
                            }
                        )
                    } else {
                        Modifier
                    }
                )
        ) {
            content()
        }
    }
}

@Composable
private fun ManualConnectCard(
    manualIp: String,
    peer: ManualPeer?,
    connecting: Boolean,
    outboundPairCode: String,
    onManualIpChange: (String) -> Unit,
    onPairCodeChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Manual connect", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "Use a device IP when discovery cannot see it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
            OutlinedTextField(
                value = manualIp,
                onValueChange = onManualIpChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Device IP address") },
                placeholder = { Text("192.168.1.20") },
                leadingIcon = { Icon(Icons.Rounded.Lan, contentDescription = null) }
            )
            if (peer != null) {
                OutlinedTextField(
                    value = outboundPairCode,
                    onValueChange = onPairCodeChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(if (peer.requiresPairCode) "Pair code required" else "Pair code if required") },
                    placeholder = { Text(if (peer.requiresPairCode) "Enter receiver code" else "Optional 6 digits") },
                    leadingIcon = { Icon(Icons.Rounded.Security, contentDescription = null) }
                )
            }
            FilledTonalButton(
                onClick = onConnect,
                enabled = !connecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                if (connecting) {
                    MaterialLoadingMark(modifier = Modifier.size(22.dp))
                } else {
                    Icon(Icons.Rounded.CompareArrows, contentDescription = null)
                }
                Spacer(Modifier.size(8.dp))
                Text(if (connecting) "Connecting" else "Connect")
            }
            peer?.let { ConnectedPeerCard(peer = it) }
        }
    }
}

@Composable
private fun ConnectedPeerCard(peer: ManualPeer) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                peerDeviceIcon(peer.deviceKind),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    if (peer.remote) {
                        "Secure remote - ${peer.endpointLabel ?: peer.name}"
                    } else {
                        "${peer.ip}:${peer.port} - ${peer.availability}"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
            if (peer.requiresPairCode) {
                AssistChip(onClick = {}, label = { Text("Code") })
            }
        }
    }
}

private fun peerDeviceIcon(deviceKind: String): ImageVector {
    return when (deviceKind.lowercase()) {
        "android", "ios", "phone", "mobile" -> Icons.Rounded.PhoneAndroid
        else -> Icons.Rounded.LaptopWindows
    }
}

@Composable
private fun SettingsScreen(
    deviceName: String,
    receiveEnabled: Boolean,
    pairCodeEnabled: Boolean,
    pairCode: String,
    themeMode: ThemeMode,
    appLanguage: AppLanguage,
    networkDetails: ActiveNetworkDetails,
    showLogs: Boolean,
    logs: List<String>,
    trustedDeviceIps: List<String>,
    vpnPeers: List<VpnSavedPeer> = emptyList(),
    trustedDeviceIds: Set<String> = emptySet(),
    concurrentFileStreams: Int,
    wifiDirectStatus: String,
    wifiDirectLink: WifiDirectLink? = null,
    wifiDirectHostCredentials: WifiDirectCredentials? = null,
    wifiDirectProbing: Boolean = false,
    wifiDirectJoining: Boolean = false,
    directLanDropPeers: List<DiscoveredPeer> = emptyList(),
    remotePeer: ManualPeer?,
    remoteConnecting: Boolean,
    remoteConnectionMessage: String,
    hasStoredRemoteInvite: Boolean,
    onSaveDeviceName: (String) -> Unit,
    onToggleReceive: () -> Unit,
    onTogglePairCode: () -> Unit,
    onNewPairCode: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onConcurrentFileStreamsChange: (Int) -> Unit,
    onRefreshDiscovery: () -> Unit,
    onLoadLogs: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
    onSendToDirectPeer: (DiscoveredPeer) -> Unit = {},
    onDisconnectWifiDirect: () -> Unit = {},
    onHostWifiDirect: () -> Unit = {},
    onJoinWifiDirectQr: (String) -> Unit = {},
    onRetryWifiDirectProbe: () -> Unit = {},
    onConnectRemote: (String) -> Unit,
    onReconnectRemote: () -> Unit,
    onUseRemote: () -> Unit,
    onForgetRemote: () -> Unit,
    onForgetTrustedIp: (String) -> Unit,
    onForgetVpnPeer: (VpnSavedPeer) -> Unit = {}
) {
    var deviceNameDraft by remember(deviceName) { mutableStateOf(deviceName) }
    var concurrencyDraft by remember(concurrentFileStreams) {
        mutableStateOf(concurrentFileStreams.toString())
    }
    var remoteInviteDraft by remember(remotePeer?.endpointLabel) { mutableStateOf("") }
    val appTranslations = LocalAppTranslations.current
    val remoteInviteScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scannedInvite = result.contents?.trim()
        if (!scannedInvite.isNullOrBlank()) {
            remoteInviteDraft = scannedInvite
            onConnectRemote(scannedInvite)
        }
    }
    fun launchRemoteInviteScanner() {
        remoteInviteScanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(
                    if (appLanguage == AppLanguage.Dari) {
                        appTranslations.translate("Scan the QR code shown by the receiving computer")
                    } else {
                        "Scan the QR code shown by the receiving computer"
                    }
                )
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .setCaptureActivity(RemoteQrCaptureActivity::class.java)
        )
    }

    val wifiDirectQrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val payload = result.contents?.trim()
        if (!payload.isNullOrBlank()) {
            onJoinWifiDirectQr(payload)
        }
    }
    fun launchWifiDirectQrScanner() {
        wifiDirectQrScanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(
                    if (appLanguage == AppLanguage.Dari) {
                        appTranslations.translate("Scan the Wi-Fi Direct QR code shown by the host")
                    } else {
                        "Scan the Wi-Fi Direct QR code shown by the host"
                    }
                )
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .setCaptureActivity(RemoteQrCaptureActivity::class.java)
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Settings", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(
            "Receive behavior, remote file sharing, and local network details.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Remote file sharing", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "Paste or scan the encrypted invite created in Settings on the Windows PC. The invite is protected by Android Keystore after connection.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Text(
                    "Using Tailscale or another VPN? First turn on the VPN on this phone and on the Windows PC, with both devices in the same private network. On Windows, select VPN / overlay, enter its Tailscale 100.x address or MagicDNS name, then create a VPN invite. Scan or paste that invite here—no router port forwarding is needed.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                if (remotePeer == null) {
                    if (hasStoredRemoteInvite) {
                        Text(
                            "A saved remote computer invite is available, but the connection is offline.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onReconnectRemote,
                                enabled = !remoteConnecting,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (remoteConnecting) {
                                    MaterialLoadingMark(modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.size(8.dp))
                                }
                                Text(if (remoteConnecting) "Connecting" else "Reconnect")
                            }
                            OutlinedButton(
                                onClick = onForgetRemote,
                                enabled = !remoteConnecting,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Forget")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = remoteInviteDraft,
                        onValueChange = { remoteInviteDraft = it.trimStart() },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Secure remote invite") },
                        placeholder = { Text("landrop://r/...") },
                        leadingIcon = {
                            Icon(Icons.Rounded.Security, contentDescription = null)
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = ::launchRemoteInviteScanner,
                                enabled = !remoteConnecting
                            ) {
                                Icon(
                                    Icons.Rounded.QrCodeScanner,
                                    contentDescription = "Scan remote invite QR code"
                                )
                            }
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = ::launchRemoteInviteScanner,
                            enabled = !remoteConnecting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Scan QR")
                        }
                        Button(
                            onClick = { onConnectRemote(remoteInviteDraft) },
                            enabled = remoteInviteDraft.isNotBlank() && !remoteConnecting,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (remoteConnecting) {
                                MaterialLoadingMark(modifier = Modifier.size(22.dp))
                                Spacer(Modifier.size(8.dp))
                            }
                            Text(if (remoteConnecting) "Connecting" else "Connect")
                        }
                    }
                } else {
                    ConnectedPeerCard(peer = remotePeer)
                    Text(
                        "The certificate is pinned to this invite. Connections with another certificate are rejected.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onUseRemote,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Use for send")
                        }
                        OutlinedButton(
                            onClick = onForgetRemote,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Forget")
                        }
                    }
                }
                Text(
                    remoteConnectionMessage,
                    color = when {
                        remoteConnectionMessage.startsWith("Connected securely") ->
                            MaterialTheme.colorScheme.primary
                        remoteConnectionMessage.startsWith("Timed out") ||
                            remoteConnectionMessage.startsWith("Connection to") ->
                            MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 13.sp
                )
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("This device", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "This is the name other LanDrop devices see during discovery and transfers.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = deviceNameDraft,
                    onValueChange = { deviceNameDraft = it.take(48) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Device name") }
                )
                Button(
                    onClick = { onSaveDeviceName(deviceNameDraft) },
                    enabled = deviceNameDraft.trim().isNotEmpty() && deviceNameDraft.trim() != deviceName,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save name")
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column {
                SettingsToggleRow(
                    icon = Icons.Rounded.Download,
                    title = "Receive",
                    supporting = if (receiveEnabled) "Accept incoming offers" else "Incoming offers paused",
                    checked = receiveEnabled,
                    onCheckedChange = { onToggleReceive() }
                )
                SettingsDivider()
                SettingsToggleRow(
                    icon = Icons.Rounded.Security,
                    title = "Pair code",
                    supporting = if (pairCodeEnabled) "Required: #$pairCode" else "Not required",
                    checked = pairCodeEnabled,
                    onCheckedChange = { onTogglePairCode() }
                )
                SettingsDivider()
                SettingsActionRow(
                    icon = Icons.Rounded.Refresh,
                    title = "Change pair code",
                    value = "New code",
                    onClick = onNewPairCode
                )
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Theme", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "Choose standard dark, true-black OLED, or light mode.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                ThemeModeControl(mode = themeMode, onModeChange = onThemeModeChange)
                SettingsDivider()
                Text("Language", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "Choose the language used by LanDrop.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                LanguageControl(
                    language = appLanguage,
                    onLanguageChange = onAppLanguageChange
                )
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Transfer performance", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "For project folders with many small files, allow LanDrop to negotiate more parallel file workers when both devices support it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = concurrencyDraft,
                    onValueChange = { value ->
                        concurrencyDraft = value.filter(Char::isDigit).take(3)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Concurrent files (1-100)") }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1, 4, 8, 16).forEach { count ->
                        OutlinedButton(
                            onClick = {
                                concurrencyDraft = count.toString()
                                onConcurrentFileStreamsChange(count)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(count.toString(), maxLines = 1)
                        }
                    }
                }
                Button(
                    onClick = {
                        val next = concurrencyDraft.toIntOrNull()?.coerceIn(1, 100) ?: concurrentFileStreams
                        concurrencyDraft = next.toString()
                        onConcurrentFileStreamsChange(next)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save parallel limit")
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column {
                SettingsValueRow(
                    icon = Icons.Rounded.Lan,
                    title = "Active path",
                    value = networkDetails.transport
                )
                SettingsDivider()
                SettingsValueRow(
                    icon = Icons.Rounded.Settings,
                    title = "Interface",
                    value = networkDetails.interfaceName
                )
                SettingsDivider()
                SettingsValueRow(
                    icon = Icons.Rounded.PhoneAndroid,
                    title = "IPv4 address",
                    value = networkDetails.addresses
                )
                SettingsDivider()
                SettingsActionRow(
                    icon = Icons.Rounded.Refresh,
                    title = "Refresh discovery",
                    value = "Scan",
                    onClick = onRefreshDiscovery
                )
                SettingsDivider()
                SettingsActionRow(
                    icon = Icons.Rounded.PhoneAndroid,
                    title = "Phone hotspot",
                    value = "Open",
                    onClick = onOpenHotspotSettings
                )
                SettingsDivider()
                SettingsActionRow(
                    icon = Icons.Rounded.CompareArrows,
                    title = "Wi-Fi Direct",
                    value = "Stop",
                    onClick = onDisconnectWifiDirect
                )
                SettingsDivider()
                SettingsActionRow(
                    icon = Icons.Rounded.Lan,
                    title = "Network logs",
                    value = if (showLogs) "Hide" else "Load",
                    onClick = onLoadLogs
                )
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Wi-Fi Direct", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "Direct link without a router — phone-to-phone or a Windows Direct Wi-Fi network. After the link forms, LanDrop finds the other device automatically and it appears below and in Send.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                if (wifiDirectLink != null) {
                    val link = wifiDirectLink!!
                    Text(
                        if (link.isGroupOwner) "Hosting group at ${link.groupOwnerAddress}" else "Joined group at ${link.groupOwnerAddress}",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    wifiDirectStatus,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                if (wifiDirectJoining) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MaterialLoadingMark(modifier = Modifier.size(22.dp))
                        Text(
                            "Joining the Wi-Fi Direct network...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                }
                if (wifiDirectProbing) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MaterialLoadingMark(modifier = Modifier.size(22.dp))
                        Text(
                            "Looking for LanDrop on the Wi-Fi Direct link...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onHostWifiDirect,
                        enabled = !wifiDirectJoining,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Host with QR")
                    }
                    OutlinedButton(
                        onClick = ::launchWifiDirectQrScanner,
                        enabled = !wifiDirectJoining,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Join by QR")
                    }
                }
                OutlinedButton(
                    onClick = onDisconnectWifiDirect,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Stop Wi-Fi Direct")
                }
                wifiDirectHostCredentials?.let { credentials ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "Scan this QR on the joining Android phone.",
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color.White
                            ) {
                                WifiDirectQrCode(
                                    credentials = credentials,
                                    modifier = Modifier.size(220.dp).padding(10.dp)
                                )
                            }
                            Text(
                                credentials.networkName,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                if (wifiDirectLink != null) {
                    Text(
                        "Connected devices",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    if (directLanDropPeers.isEmpty()) {
                        Text(
                            "Link is up. No LanDrop devices found on it yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    } else {
                        directLanDropPeers.forEach { peer ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(peer.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${peer.ip}:${peer.port} • ${peer.linkLabel}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                                TextButton(onClick = { onSendToDirectPeer(peer) }) {
                                    Text("Send")
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (directLanDropPeers.isEmpty()) {
                            OutlinedButton(
                                onClick = onRetryWifiDirectProbe,
                                enabled = !wifiDirectProbing,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (wifiDirectProbing) "Refreshing" else "Refresh")
                            }
                        }
                        OutlinedButton(
                            onClick = onDisconnectWifiDirect,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Leave group")
                        }
                    }
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Trusted devices", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "Known devices are used by Favorites quick save. VPN entries stay listed with a VPN link badge so Tailscale devices remain visible. Quick Save On auto-saves from any device, including VPN.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                if (vpnPeers.isNotEmpty()) {
                    Text("Tailscale / VPN", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    vpnPeers.forEach { vpn ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(vpn.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${vpn.host}:${vpn.port}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            AssistChip(onClick = {}, label = { Text(if (vpn.secure) "Secure VPN" else "VPN") })
                            TextButton(onClick = { onForgetVpnPeer(vpn) }) {
                                Text("Forget")
                            }
                        }
                    }
                }
                if (trustedDeviceIps.isEmpty() && vpnPeers.isEmpty()) {
                    Text("No trusted devices yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (trustedDeviceIps.isNotEmpty()) {
                    Text("Known IPs", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    trustedDeviceIps.forEach { ip ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ip, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    linkLabelForIp(ip),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                            TextButton(onClick = { onForgetTrustedIp(ip) }) {
                                Text("Forget")
                            }
                        }
                    }
                }
                if (trustedDeviceIds.isNotEmpty()) {
                    Text(
                        "${trustedDeviceIds.size} trusted device ID(s) for Favorites quick save.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
        }
        AboutLinksCard()
        if (showLogs) {
            LogsPanel(logs = logs)
        }
    }
}

@Composable
private fun AboutLinksCard() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val browserErrorMessage = localizeAppText("Could not open this link.")
    val appVersion = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    fun openLink(url: String) {
        runCatching { uriHandler.openUri(url) }.onFailure { error ->
            AppLog.add("Could not open app link: ${error.message}")
            Toast.makeText(context, browserErrorMessage, Toast.LENGTH_LONG).show()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("About LanDrop", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Version $appVersion", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            SettingsLinkRow(
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_github),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                },
                title = "Source code",
                supporting = "View on GitHub",
                onClick = { openLink(context.getString(R.string.source_code_url)) }
            )
            SettingsDivider()
            SettingsLinkRow(
                icon = {
                    Icon(Icons.Rounded.LaptopWindows, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                title = "Windows version",
                supporting = "Installers and portable download",
                onClick = { openLink(context.getString(R.string.windows_download_url)) }
            )
        }
    }
}

@Composable
private fun SettingsLinkRow(
    icon: @Composable () -> Unit,
    title: String,
    supporting: String,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(supporting, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Icon(
                Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ThemeModeControl(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit) {
    val options = listOf(
        ThemeMode.Dark to "Dark",
        ThemeMode.Oled to "OLED",
        ThemeMode.Light to "Light"
    )
    // Recreate Material outlines when direction changes; cached paths can retain the old ends.
    key(LocalLayoutDirection.current) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = mode == option.first,
                    onClick = { onModeChange(option.first) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    ),
                    label = { Text(option.second, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
    }
}

@Composable
private fun LanguageControl(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit
) {
    val options = listOf(
        AppLanguage.English to "English",
        AppLanguage.Dari to "دری"
    )
    // Recreate Material outlines when direction changes; cached paths can retain the old ends.
    key(LocalLayoutDirection.current) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = language == option.first,
                    onClick = { onLanguageChange(option.first) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    ),
                    label = { Text(option.second, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
    }
}

@Composable
private fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE
) {
    MaterialText(
        text = localizeAppText(text),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        maxLines = maxLines
    )
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(supporting, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsValueRow(icon: ImageVector, title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SettingsActionRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        TextButton(onClick = onClick) { Text(value) }
    }
}

@Composable
private fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

@Composable
private fun LogsPanel(logs: List<String>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Logs", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (logs.isEmpty()) {
                Text(
                    "No logs yet. Reopen the app or tap Refresh on Send.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            } else {
                logs.take(80).forEach { line ->
                    Text(
                        line,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCard(
    icon: ImageVector,
    title: String,
    body: String,
    chips: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips()
            }
        }
    }
}

@Composable
private fun ActionTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.height(128.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ReceiveLoadingCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    MaterialLoadingMark(modifier = Modifier.size(40.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Listening", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                Text(
                    "Waiting for a nearby PC or manual IP request.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun EmptyPanel(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}
