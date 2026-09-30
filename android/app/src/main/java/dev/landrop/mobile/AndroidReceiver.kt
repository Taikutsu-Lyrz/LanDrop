package dev.landrop.mobile

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

data class IncomingOfferPrompt(
    val senderName: String,
    val senderIp: String,
    val senderDeviceId: String = "",
    val fileCount: Int,
    val directoryCount: Int,
    val totalBytes: Long
)

data class ReceiveProgress(
    val peerName: String,
    val currentFile: String,
    val completedBytes: Long,
    val totalBytes: Long,
    val active: Boolean,
    val speedMbps: Double,
    val fileCount: Int = 0,
    val currentFiles: List<String> = listOf(currentFile)
)

class AndroidReceiver(
    private val context: Context,
    private val deviceId: String,
    private val deviceName: String,
    private val saveTreeUriProvider: () -> Uri?,
    private val receiveEnabledProvider: () -> Boolean,
    private val concurrentFileStreamsProvider: () -> Int,
    private val pairCodeRequiredProvider: () -> Boolean,
    private val pairCodeProvider: () -> String,
    private val requestApproval: (IncomingOfferPrompt) -> Boolean,
    private val onProgress: (ReceiveProgress?) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val cancelCurrent = AtomicBoolean(false)
    private val pauseCurrent = AtomicBoolean(false)
    private val peerPauseCurrent = AtomicBoolean(false)
    private val controlWriteLock = Any()
    private val activeOutput = AtomicReference<DataOutputStream?>(null)
    private val activeTransferId = AtomicReference<String?>(null)
    private val activeWorkerOutputs = ConcurrentHashMap<Int, DataOutputStream>()
    // Open client sockets so cancel/stop can close them and unblock threads stuck in read.
    private val activeSockets = ConcurrentLinkedQueue<Socket>()
    private val parallelSessions = ConcurrentHashMap<String, AndroidParallelReceiveSession>()
    private val instanceId = UUID.randomUUID().toString()
    @Volatile private var latestProgress: ReceiveProgress? = null
    private var server: ServerSocket? = null

    private data class AndroidParallelReceiveSession(
        val transferId: String,
        val sender: String,
        val saveTreeUri: Uri,
        val createdDirs: MutableMap<String, Uri>,
        val totalBytes: Long,
        val fileCount: Int,
        val completedBytes: AtomicLong,
        val startedAtMs: Long,
        val workersDone: CountDownLatch,
        val error: AtomicReference<String?>,
        val activeFiles: ConcurrentHashMap<Int, String>
    )

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "LanDrop-Android-Receiver", isDaemon = true) {
            runCatching {
                ServerSocket(50501, 50, InetAddress.getByName("0.0.0.0")).use { socket ->
                    server = socket
                    val ips = NetworkDetails.localIpv4Addresses().joinToString()
                    AppLog.add("TCP receiver listening on 0.0.0.0:50501; local IPs: $ips")
                    onStatus("Ready to receive.")
                    while (running.get()) {
                        val client = runCatching { socket.accept() }
                            .onFailure {
                                if (running.get()) {
                                    AppLog.add("TCP accept error: ${it.message}")
                                    onStatus("Receive error: ${it.message}")
                                }
                            }
                            .getOrNull() ?: continue

                        thread(
                            name = "LanDrop-Android-Client-${client.inetAddress.hostAddress}",
                            isDaemon = true
                        ) {
                            runCatching { client.use(::handleClient) }
                                .onFailure {
                                    if (running.get()) {
                                        AppLog.add(
                                            "TCP client ${client.inetAddress.hostAddress} failed: ${it.message}"
                                        )
                                    }
                                }
                        }
                    }
                }
            }.onFailure {
                if (running.get()) {
                    AppLog.add("TCP receiver failed to start: ${it.message}")
                    onStatus("Could not start receive mode: ${it.message}")
                }
            }
        }
    }

    private fun receiveFramedV2(
        input: DataInputStream,
        output: DataOutputStream,
        transferId: String,
        sender: String,
        saveTreeUri: Uri,
        createdDirs: MutableMap<String, Uri>,
        totalBytes: Long,
        fileCount: Int,
        initialCompletedBytes: Long
    ) {
        val buffer = ByteArray(1024 * 1024)
        var completedBytes = initialCompletedBytes.coerceAtMost(totalBytes)
        var lastProgressBytes = completedBytes
        var lastProgressAtMs = System.currentTimeMillis()
        var lastUiAtMs = 0L
        var activeRelativePath: String? = null
        var activeOutputUri: Uri? = null
        var activeStream: BufferedOutputStream? = null
        var activeWritten = 0L
        var activeSize = 0L

        fun closeActiveStream() {
            runCatching { activeStream?.flush() }
            runCatching { activeStream?.close() }
            activeStream = null
        }

        try {
            activeOutput.set(output)
            activeTransferId.set(transferId)
            while (true) {
                if (cancelCurrent.get()) {
                    writeControl(output, flowControl(transferId, "cancel_requested"))
                    writeControl(output, transferReceipt(transferId, false, "Canceled on Android."))
                    onProgress(null)
                    return
                }

                var pausedNoticeSent = false
                var resumedByPeer = false
                var prefetched: JSONObject? = null
                while (pauseCurrent.get() && prefetched == null) {
                    // Only announce local pause on the wire. If the peer paused us,
                    // they already know — don't spam duplicate pause frames.
                    if (!pausedNoticeSent && !peerPauseCurrent.get()) {
                        writeControl(output, flowControl(transferId, "paused"))
                        pausedNoticeSent = true
                        latestProgress?.let { progress ->
                            latestProgress = progress.copy(active = false, speedMbps = 0.0)
                            onProgress(latestProgress)
                        }
                        onStatus("Receive paused.")
                    } else if (!pausedNoticeSent) {
                        pausedNoticeSent = true
                        latestProgress?.let { progress ->
                            latestProgress = progress.copy(active = false, speedMbps = 0.0)
                            onProgress(latestProgress)
                        }
                    }
                    if (cancelCurrent.get()) {
                        writeControl(output, flowControl(transferId, "cancel_requested"))
                        writeControl(output, transferReceipt(transferId, false, "Canceled on Android."))
                        onProgress(null)
                        return
                    }
                    if (input.available() > 0) {
                        val control = readControl(input)
                        when (control.optString("type")) {
                            "transfer_flow_control" -> {
                                if (control.optString("transferId") != transferId) {
                                    error("Received flow control for the wrong transfer.")
                                }
                                when (control.optString("state")) {
                                    "resumed" -> {
                                        pauseCurrent.set(false)
                                        peerPauseCurrent.set(false)
                                        resumedByPeer = true
                                        latestProgress?.let { progress ->
                                            latestProgress = progress.copy(active = true, speedMbps = 0.0)
                                            onProgress(latestProgress)
                                        }
                                        onStatus("$sender resumed the transfer.")
                                    }
                                    "paused" -> {
                                        peerPauseCurrent.set(true)
                                        pauseCurrent.set(true)
                                        latestProgress?.let { progress ->
                                            latestProgress = progress.copy(active = false, speedMbps = 0.0)
                                            onProgress(latestProgress)
                                        }
                                        onStatus("$sender paused the transfer.")
                                    }
                                    "cancel_requested" -> {
                                        latestProgress = null
                                        onProgress(null)
                                        onStatus("Transfer canceled by sender.")
                                        writeControl(
                                            output,
                                            transferReceipt(transferId, false, "Canceled by sender.")
                                        )
                                        return
                                    }
                                }
                            }
                            else -> {
                                // Stay paused, but process already-in-flight frames.
                                prefetched = control
                            }
                        }
                    } else {
                        Thread.sleep(120)
                    }
                }
                // Only echo "resumed" when WE initiated resume locally. Echoing after
                // a peer resume races with new chunks and desyncs Windows ACK windows.
                if (pausedNoticeSent && !pauseCurrent.get() && !resumedByPeer && !peerPauseCurrent.get()) {
                    writeControl(output, flowControl(transferId, "resumed"))
                }

                val message = prefetched ?: readControl(input)
                when (message.optString("type")) {
                    "file_start" -> {
                        if (message.optString("transferId") != transferId) {
                            error("Received a file for the wrong transfer.")
                        }
                        closeActiveStream()
                        val relativePath = message.optString("relativePath")
                        val size = message.optLong("size")
                        val offset = message.optLong("offset")
                        val outputUri = findPartialFileForPath(saveTreeUri, createdDirs, relativePath)
                            ?: createPartialFileForPath(saveTreeUri, createdDirs, relativePath)
                        val existingSize = documentSize(outputUri)
                        if (existingSize != offset) {
                            error("Partial file size mismatch for $relativePath.")
                        }
                        activeRelativePath = relativePath
                        activeOutputUri = outputUri
                        activeWritten = offset
                        activeSize = size
                        activeStream = BufferedOutputStream(
                            context.contentResolver.openOutputStream(outputUri, if (offset > 0) "wa" else "w")
                                ?: error("Could not open $relativePath for writing."),
                            buffer.size
                        )
                    }
                    "file_chunk" -> {
                        if (message.optString("transferId") != transferId) {
                            error("Received a chunk for the wrong transfer.")
                        }
                        val relativePath = activeRelativePath ?: error("Received chunk before file start.")
                        val stream = activeStream ?: error("Received chunk without an open file.")
                        val length = message.optInt("length")
                        val offset = message.optLong("offset")
                        val sequence = message.optLong("sequence")
                        if (message.optString("relativePath") != relativePath || offset != activeWritten) {
                            error("Unexpected chunk offset for $relativePath.")
                        }
                        if (length < 0 || length.toLong() > activeSize - activeWritten) {
                            error("Chunk exceeds remaining file size for $relativePath.")
                        }

                        var remaining = length
                        while (remaining > 0) {
                            val read = minOf(buffer.size, remaining)
                            input.readFully(buffer, 0, read)
                            stream.write(buffer, 0, read)
                            remaining -= read
                            activeWritten += read
                            completedBytes += read
                        }

                        val now = System.currentTimeMillis()
                        // 1-second average: emit at most once per window so
                        // the readout holds steady instead of flickering.
                        if (now - lastUiAtMs >= 1000) {
                            val elapsedMs = (now - lastProgressAtMs).coerceAtLeast(1)
                            val deltaBytes = completedBytes - lastProgressBytes
                            val speedMbps = (deltaBytes.toDouble() * 8.0) / elapsedMs.toDouble() / 1000.0
                            lastProgressBytes = completedBytes
                            lastProgressAtMs = now
                            lastUiAtMs = now
                            val progress = ReceiveProgress(
                                peerName = sender,
                                currentFile = relativePath,
                                completedBytes = completedBytes,
                                totalBytes = totalBytes,
                                active = !pauseCurrent.get(),
                                speedMbps = speedMbps,
                                fileCount = fileCount
                            )
                            latestProgress = progress
                            onProgress(progress)
                        } else {
                            latestProgress = ReceiveProgress(
                                peerName = sender,
                                currentFile = relativePath,
                                completedBytes = completedBytes,
                                totalBytes = totalBytes,
                                active = !pauseCurrent.get(),
                                speedMbps = latestProgress?.speedMbps ?: 0.0,
                                fileCount = fileCount
                            )
                        }

                        writeControl(
                            output,
                            JSONObject()
                                .put("type", "file_chunk_ack")
                                .put("protocolVersion", 2)
                                .put("transferId", transferId)
                                .put("relativePath", relativePath)
                                .put("nextOffset", activeWritten)
                                .put("sequence", sequence)
                                .put("acknowledgedAtMs", System.currentTimeMillis())
                        )
                    }
                    "file_end" -> {
                        val relativePath = activeRelativePath ?: error("Received file end before file start.")
                        val outputUri = activeOutputUri ?: error("Received file end without an output file.")
                        if (message.optString("transferId") != transferId ||
                            message.optString("relativePath") != relativePath ||
                            message.optLong("size") != activeSize ||
                            activeWritten != activeSize
                        ) {
                            error("Received incomplete file $relativePath.")
                        }
                        closeActiveStream()
                        finalizePartialFile(saveTreeUri, createdDirs, relativePath, outputUri)
                        activeRelativePath = null
                        activeOutputUri = null
                        activeWritten = 0L
                        activeSize = 0L
                        onStatus("Received $relativePath")
                    }
                    "transfer_complete" -> {
                        if (message.optString("transferId") != transferId) {
                            error("Received completion for the wrong transfer.")
                        }
                        if (activeStream != null) {
                            error("Transfer completed before the active file was finalized.")
                        }
                        writeControl(
                            output,
                            transferReceipt(transferId, true, "Saved on Android.")
                        )
                        onStatus("Received files from $sender.")
                        latestProgress = null
                        onProgress(null)
                        return
                    }
                    "transfer_flow_control" -> {
                        when (message.optString("state")) {
                            "paused" -> {
                                // Peer (Windows) paused us. Keep accepting in-flight file
                                // frames so pipelined chunks don't kill the transfer, and
                                // flip the UI to inactive immediately.
                                peerPauseCurrent.set(true)
                                pauseCurrent.set(true)
                                latestProgress?.let { progress ->
                                    latestProgress = progress.copy(active = false, speedMbps = 0.0)
                                    onProgress(latestProgress)
                                }
                                onStatus("$sender paused the transfer.")
                            }
                            "resumed" -> {
                                peerPauseCurrent.set(false)
                                pauseCurrent.set(false)
                                latestProgress?.let { progress ->
                                    latestProgress = progress.copy(active = true)
                                    onProgress(latestProgress)
                                }
                                onStatus("$sender resumed the transfer.")
                            }
                            "cancel_requested" -> {
                                latestProgress = null
                                onProgress(null)
                                onStatus("Transfer canceled by sender.")
                                writeControl(
                                    output,
                                    transferReceipt(transferId, false, "Canceled by sender.")
                                )
                                return
                            }
                        }
                    }
                    else -> error("Unexpected framed transfer frame.")
                }
            }
        } finally {
            activeOutput.set(null)
            activeTransferId.set(null)
            closeActiveStream()
            // Always clear the receive UI when the framed session ends for any reason
            // (cancel from peer, local cancel, protocol error, completion already nulls it).
            if (latestProgress != null) {
                latestProgress = null
                onProgress(null)
            }
        }
    }

    fun stop() {
        running.set(false)
        cancelCurrent.set(true)
        runCatching { server?.close() }
        server = null
        var pending = activeSockets.poll()
        while (pending != null) {
            runCatching { pending.close() }
            pending = activeSockets.poll()
        }
    }

    fun cancelCurrentTransfer() {
        cancelCurrent.set(true)
        pauseCurrent.set(false)
        peerPauseCurrent.set(false)
        sendFlowControlIfActive("cancel_requested")
        var pending = activeSockets.poll()
        while (pending != null) {
            runCatching { pending.close() }
            pending = activeSockets.poll()
        }
        onStatus("Receive canceled.")
        onProgress(null)
    }

    fun togglePauseCurrentTransfer(): Boolean {
        // Unified pause model that mirrors Windows:
        //   pauseCurrent     — single source of truth ("this side is paused").
        //   peerPauseCurrent — labels how the pause originated (UI/status only).
        // When peer pauses, the streaming thread sets BOTH; this toggle just
        // flips pauseCurrent. The streaming thread's wait loop notices the
        // change and emits the right flow_control frame. We also fire the
        // frame from this thread for the lowest possible UI-to-wire latency;
        // any duplicate the streaming thread might send is idempotent on the
        // peer side.
        val wasPeerPaused = peerPauseCurrent.get() && pauseCurrent.get()
        val paused = !pauseCurrent.get()
        pauseCurrent.set(paused)
        if (!paused) {
            peerPauseCurrent.set(false)
        }
        sendFlowControlIfActive(if (paused) "paused" else "resumed")
        latestProgress?.let { progress ->
            val updated = progress.copy(active = !paused, speedMbps = if (paused) 0.0 else progress.speedMbps)
            latestProgress = updated
            onProgress(updated)
        }
        onStatus(when {
            wasPeerPaused -> "Requested sender resume."
            paused -> "Receive paused."
            else -> "Receive resumed."
        })
        return paused
    }

    private fun sendFlowControlIfActive(state: String) {
        val transferId = activeTransferId.get() ?: return
        val workerOutputs = activeWorkerOutputs.values.toList()
        val outputs = if (workerOutputs.isNotEmpty()) {
            workerOutputs
        } else {
            listOfNotNull(activeOutput.get())
        }
        outputs.forEach { output ->
            runCatching {
                writeControl(output, flowControl(transferId, state))
            }.onFailure {
                AppLog.add("Could not notify peer about receive $state: ${it.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        AppLog.add("TCP client connected from ${socket.inetAddress.hostAddress}:${socket.port}")
        socket.tcpNoDelay = true
        // Large buffers so fast links (Wi-Fi Direct, fast LAN) run at full
        // speed from the first byte. A 64 KiB window throttles high-BDP
        // links and reads as "speed ramps up slowly". Best-effort: the OS
        // clamps values it cannot honour.
        runCatching { socket.sendBufferSize = 2 * 1024 * 1024 }
        runCatching { socket.receiveBufferSize = 2 * 1024 * 1024 }
        socket.soTimeout = 300_000
        activeSockets.add(socket)
        try {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val firstMessage = readControl(input)
            when (val first = firstMessage.optString("type")) {
                "peer_hello_request" -> {
                    AppLog.add("Peer hello request received from ${socket.inetAddress.hostAddress}")
                    writeControl(output, peerHelloResponse())
                }
                "transfer_offer" -> {
                    AppLog.add("Transfer offer received from ${socket.inetAddress.hostAddress}")
                    handleTransferOffer(input, output, firstMessage = firstMessage, senderIp = socket.inetAddress.hostAddress ?: "")
                }
                "parallel_worker_open" -> {
                    handleParallelWorkerOpen(input, output, firstMessage)
                }
                else -> {
                    AppLog.add("Unexpected TCP message: $first")
                    onStatus("Unexpected LAN message: $first")
                }
            }
        } finally {
            activeSockets.remove(socket)
        }
    }

    private fun handleTransferOffer(
        input: DataInputStream,
        output: DataOutputStream,
        firstMessage: JSONObject?,
        senderIp: String
    ) {
        val offer = firstMessage ?: return
        if (!receiveEnabledProvider()) {
            writeControl(output, transferResponse(offer.optString("transferId"), false, "Receiving is off."))
            return
        }

        val saveTreeUri = saveTreeUriProvider()
        if (saveTreeUri == null) {
            writeControl(
                output,
                transferResponse(
                    offer.optString("transferId"),
                    false,
                    "Choose a save folder on Android first."
                )
            )
            onStatus("Choose a save folder before receiving.")
            return
        }

        val transferId = offer.optString("transferId")
        val sender = offer.optString("senderName", "PC")
        val selectedEncoding = selectTransferEncoding(offer)
        // The remote tunnel connects to this listener over loopback. Use one
        // stream for it, while preserving configurable parallel workers for LAN.
        val selectedParallelFileStreams = selectedParallelFileStreams(
            offer,
            selectedEncoding,
            forceSingleStream = senderIp == "127.0.0.1" || senderIp == "::1"
        )
        if (pairCodeRequiredProvider() && offer.optString("pairCode") != pairCodeProvider()) {
            AppLog.add("Incoming offer rejected for invalid pair code from $sender")
            writeControl(output, transferResponse(transferId, false, "Pair code required or invalid."))
            onStatus("Rejected transfer from $sender: pair code required.")
            return
        }
        cancelCurrent.set(false)
        pauseCurrent.set(false)
        val approved = requestApproval(
            IncomingOfferPrompt(
                senderName = sender,
                senderIp = senderIp,
                senderDeviceId = offer.optString("senderDeviceId", ""),
                fileCount = offer.optInt("fileCount"),
                directoryCount = offer.optInt("directoryCount"),
                totalBytes = offer.optLong("totalBytes")
            )
        )
        AppLog.add("Incoming offer decision for $sender: $approved")
        if (!approved) {
            AppLog.add("Incoming offer rejected by user: $sender")
            writeControl(output, transferResponse(transferId, false, "Rejected on Android."))
            return
        }

        onStatus("$sender is sending files...")
        // Keep the radio awake while receiving (see TransferWifiLock).
        TransferWifiLock.acquire(context)
        writeControl(
            output,
            transferResponse(
                transferId,
                true,
                "Android receiver approved.",
                selectedEncoding,
                selectedParallelFileStreams
            )
        )

        val manifest = readControl(input)
        if (manifest.optString("type") != "transfer_manifest") {
            onStatus("Receiver expected a manifest.")
            TransferWifiLock.release()
            return
        }

        val entries = manifest.optJSONArray("entries") ?: JSONArray()
        val totalBytes = manifest.optLong("totalBytes")
        val manifestFileCount = (0 until entries.length())
            .count { index -> entries.optJSONObject(index)?.optString("kind") == "file" }
        val rootDocumentId = DocumentsContract.getTreeDocumentId(saveTreeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(saveTreeUri, rootDocumentId)
        val createdDirs = mutableMapOf<String, Uri>("" to rootUri)
        val resumeFiles = JSONArray()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            if (entry.optString("kind") == "file") {
                val relativePath = entry.optString("relativePath")
                val partialUri = findPartialFileForPath(saveTreeUri, createdDirs, relativePath)
                val completedBytes = partialUri?.let(::documentSize) ?: 0L
                val expectedSize = entry.optLong("size")
                resumeFiles.put(
                    JSONObject()
                        .put("relativePath", relativePath)
                        .put("completedBytes", completedBytes.coerceAtMost(expectedSize))
                        .put("complete", completedBytes >= expectedSize && expectedSize > 0L)
                )
            }
        }
        val initialCompletedBytes = (0 until resumeFiles.length())
            .sumOf { index -> resumeFiles.optJSONObject(index)?.optLong("completedBytes") ?: 0L }
            .coerceAtMost(totalBytes)

        if (selectedEncoding == "framed_v2" && selectedParallelFileStreams > 1 && initialCompletedBytes < totalBytes) {
            val session = AndroidParallelReceiveSession(
                transferId = transferId,
                sender = sender,
                saveTreeUri = saveTreeUri,
                createdDirs = createdDirs,
                totalBytes = totalBytes,
                fileCount = manifestFileCount,
                completedBytes = AtomicLong(initialCompletedBytes),
                startedAtMs = System.currentTimeMillis(),
                workersDone = CountDownLatch(selectedParallelFileStreams),
                error = AtomicReference(null),
                activeFiles = ConcurrentHashMap()
            )
            parallelSessions[transferId] = session
        }

        writeControl(
            output,
            JSONObject()
                .put("type", "transfer_resume_plan")
                .put("protocolVersion", 1)
                .put("transferId", transferId)
                .put("files", resumeFiles)
                .put("respondedAtMs", System.currentTimeMillis())
        )
        ReceiveProgress(
            peerName = sender,
            currentFile = "Preparing ${manifestFileCount} file(s)",
            completedBytes = initialCompletedBytes,
            totalBytes = totalBytes,
            active = true,
            speedMbps = 0.0,
            fileCount = manifestFileCount
        ).also {
            latestProgress = it
            onProgress(it)
        }

        if (selectedEncoding == "framed_v2" && selectedParallelFileStreams > 1 && initialCompletedBytes < totalBytes) {
            try {
                receiveFramedV2ParallelControl(
                    input = input,
                    output = output,
                    transferId = transferId,
                    sender = sender
                )
            } finally {
                TransferWifiLock.release()
            }
            return
        }

        if (selectedEncoding == "framed_v2") {
            try {
                receiveFramedV2(
                    input = input,
                    output = output,
                    transferId = transferId,
                    sender = sender,
                    saveTreeUri = saveTreeUri,
                    createdDirs = createdDirs,
                    totalBytes = totalBytes,
                    fileCount = manifestFileCount,
                    initialCompletedBytes = initialCompletedBytes
                )
            } finally {
                TransferWifiLock.release()
            }
            return
        }

        val buffer = ByteArray(1024 * 1024)
        var completedBytes = initialCompletedBytes
        var lastProgressBytes = completedBytes
        var lastProgressAtMs = System.currentTimeMillis()
        var lastUiAtMs = 0L

        // try/finally keeps the loop's indent to keep the diff small; every
        // return or throw below still runs the finally and drops the lock.
        try {
        while (true) {
            if (cancelCurrent.get()) {
                writeControl(
                    output,
                    JSONObject()
                        .put("type", "transfer_receipt")
                        .put("protocolVersion", 1)
                        .put("transferId", transferId)
                        .put("success", false)
                        .put("message", "Canceled on Android.")
                        .put("savedInto", JSONObject.NULL)
                        .put("respondedAtMs", System.currentTimeMillis())
                )
                onProgress(null)
                return
            }
            val message = readControl(input)
            when (message.optString("type")) {
                    "file_start" -> {
                        val relativePath = message.optString("relativePath")
                        val size = message.optLong("size")
                        val offset = message.optLong("offset")
                        val outputUri = findPartialFileForPath(saveTreeUri, createdDirs, relativePath)
                            ?: createPartialFileForPath(saveTreeUri, createdDirs, relativePath)
                        val existingSize = documentSize(outputUri)
                        if (existingSize != offset) {
                            error("Partial file size mismatch for $relativePath.")
                        }
                        context.contentResolver.openOutputStream(outputUri, if (offset > 0) "wa" else "w")?.use { raw ->
                            val stream = BufferedOutputStream(raw, buffer.size)
                            var remaining = size - offset
                            while (remaining > 0) {
                                if (cancelCurrent.get()) error("Canceled on Android.")
                                while (pauseCurrent.get()) {
                                    if (cancelCurrent.get()) error("Canceled on Android.")
                                    Thread.sleep(120)
                                    lastProgressAtMs = System.currentTimeMillis()
                                }
                                val read = minOf(buffer.size.toLong(), remaining).toInt()
                                input.readFully(buffer, 0, read)
                                stream.write(buffer, 0, read)
                                remaining -= read
                                completedBytes += read
                                val now = System.currentTimeMillis()
                                // 1-second average: measure only across UI
                                // emits so the readout holds steady.
                                if (now - lastUiAtMs >= 1000 || remaining <= 0) {
                                    val elapsedMs = (now - lastProgressAtMs).coerceAtLeast(1)
                                    val deltaBytes = completedBytes - lastProgressBytes
                                    val speedMbps = (deltaBytes.toDouble() * 8.0) / elapsedMs.toDouble() / 1000.0
                                    lastProgressBytes = completedBytes
                                    lastProgressAtMs = now
                                    lastUiAtMs = now
                                    val progress = ReceiveProgress(
                                            peerName = sender,
                                            currentFile = relativePath,
                                            completedBytes = completedBytes,
                                            totalBytes = totalBytes,
                                            active = !pauseCurrent.get(),
                                            speedMbps = speedMbps,
                                            fileCount = manifestFileCount
                                        )
                                    latestProgress = progress
                                    onProgress(progress)
                                }
                            }
                            stream.flush()
                        }
                        finalizePartialFile(saveTreeUri, createdDirs, relativePath, outputUri)
                        onStatus("Received $relativePath")
                    }
                    "transfer_complete" -> {
                        writeControl(
                            output,
                            JSONObject()
                                .put("type", "transfer_receipt")
                                .put("protocolVersion", 1)
                                .put("transferId", transferId)
                                .put("success", true)
                                .put("message", "Saved on Android.")
                                .put("savedInto", "Android selected folder")
                                .put("respondedAtMs", System.currentTimeMillis())
                        )
                        onStatus("Received files from $sender.")
                        latestProgress = null
                        onProgress(null)
                        return
                    }
                    else -> {
                        onStatus("Unexpected transfer frame.")
                        return
                    }
            }
        }
        } finally {
            TransferWifiLock.release()
        }
    }

    private fun handleParallelWorkerOpen(
        input: DataInputStream,
        output: DataOutputStream,
        firstMessage: JSONObject
    ) {
        val transferId = firstMessage.optString("transferId")
        val workerId = firstMessage.optInt("workerId")
        val session = parallelSessions[transferId]
        if (session == null) {
            writeControl(
                output,
                JSONObject()
                    .put("type", "parallel_worker_ready")
                    .put("protocolVersion", 2)
                    .put("transferId", transferId)
                    .put("workerId", workerId)
                    .put("accepted", false)
                    .put("message", "No active Android parallel receive session.")
                    .put("respondedAtMs", System.currentTimeMillis())
            )
            return
        }

        writeControl(
            output,
            JSONObject()
                .put("type", "parallel_worker_ready")
                .put("protocolVersion", 2)
                .put("transferId", transferId)
                .put("workerId", workerId)
                .put("accepted", true)
                .put("message", "Android worker accepted.")
                .put("respondedAtMs", System.currentTimeMillis())
        )

        activeWorkerOutputs[workerId] = output
        try {
            receiveParallelWorker(input, output, session, workerId)
        } catch (error: Throwable) {
            val message = error.message ?: "Android worker failed."
            session.error.compareAndSet(null, message)
            AppLog.add("Parallel worker $workerId failed: $message")
            if (
                message.contains("canceled", ignoreCase = true) ||
                message.contains("cancelled", ignoreCase = true)
            ) {
                latestProgress = null
                onProgress(null)
                onStatus("Transfer canceled.")
            }
        } finally {
            activeWorkerOutputs.remove(workerId, output)
            session.workersDone.countDown()
        }
    }

    private fun receiveFramedV2ParallelControl(
        input: DataInputStream,
        output: DataOutputStream,
        transferId: String,
        sender: String
    ) {
        activeOutput.set(output)
        activeTransferId.set(transferId)
        val session = parallelSessions[transferId] ?: error("Missing Android parallel session.")
        try {
            while (true) {
                if (cancelCurrent.get()) {
                    writeControl(output, flowControl(transferId, "cancel_requested"))
                    writeControl(output, transferReceipt(transferId, false, "Canceled on Android."))
                    onProgress(null)
                    return
                }
                val message = readControl(input)
                when (message.optString("type")) {
                    "transfer_complete" -> {
                        session.workersDone.await()
                        session.error.get()?.let { error(it) }
                        writeControl(output, transferReceipt(transferId, true, "Saved on Android."))
                        onStatus("Received files from $sender.")
                        latestProgress = null
                        onProgress(null)
                        return
                    }
                    "transfer_flow_control" -> {
                        when (message.optString("state")) {
                            "cancel_requested" -> {
                                latestProgress = null
                                onProgress(null)
                                onStatus("Transfer canceled by peer.")
                                writeControl(output, transferReceipt(transferId, false, "Canceled by peer."))
                                return
                            }
                            "paused" -> {
                                peerPauseCurrent.set(true)
                                pauseCurrent.set(true)
                                latestProgress?.let { progress ->
                                    latestProgress = progress.copy(active = false, speedMbps = 0.0)
                                    onProgress(latestProgress)
                                }
                                onStatus("$sender paused the transfer.")
                            }
                            "resumed" -> {
                                peerPauseCurrent.set(false)
                                pauseCurrent.set(false)
                                latestProgress?.let { progress ->
                                    latestProgress = progress.copy(active = true)
                                    onProgress(latestProgress)
                                }
                                onStatus("$sender resumed the transfer.")
                            }
                        }
                    }
                    else -> error("Unexpected parallel control frame.")
                }
            }
        } finally {
            parallelSessions.remove(transferId)
            activeOutput.set(null)
            activeTransferId.set(null)
            if (latestProgress != null) {
                latestProgress = null
                onProgress(null)
            }
        }
    }

    private fun receiveParallelWorker(
        input: DataInputStream,
        output: DataOutputStream,
        session: AndroidParallelReceiveSession,
        workerId: Int
    ) {
        val buffer = ByteArray(1024 * 1024)
        var activeRelativePath: String? = null
        var activeOutputUri: Uri? = null
        var activeStream: BufferedOutputStream? = null
        var activeWritten = 0L
        var activeSize = 0L
        var lastProgressBytes = session.completedBytes.get()
        var lastProgressAtMs = System.currentTimeMillis()
        var lastUiAtMs = 0L

        fun closeActiveStream() {
            runCatching { activeStream?.flush() }
            runCatching { activeStream?.close() }
            activeStream = null
        }

        try {
            while (true) {
                if (cancelCurrent.get()) error("Canceled on Android.")

                var pausedNoticeSent = false
                var resumedByPeer = false
                var prefetched: JSONObject? = null
                while (pauseCurrent.get() && prefetched == null) {
                    if (!pausedNoticeSent && !peerPauseCurrent.get()) {
                        writeControl(output, flowControl(session.transferId, "paused"))
                        pausedNoticeSent = true
                    } else if (!pausedNoticeSent) {
                        pausedNoticeSent = true
                    }
                    if (cancelCurrent.get()) error("Canceled on Android.")
                    if (input.available() > 0) {
                        val control = readControl(input)
                        when (control.optString("type")) {
                            "transfer_flow_control" -> {
                                if (control.optString("transferId") == session.transferId) {
                                    when (control.optString("state")) {
                                        "resumed" -> {
                                            pauseCurrent.set(false)
                                            peerPauseCurrent.set(false)
                                            resumedByPeer = true
                                        }
                                        "paused" -> {
                                            peerPauseCurrent.set(true)
                                            pauseCurrent.set(true)
                                            latestProgress?.let { progress ->
                                                latestProgress = progress.copy(active = false, speedMbps = 0.0)
                                                onProgress(latestProgress)
                                            }
                                            onStatus("${session.sender} paused the transfer.")
                                        }
                                        "cancel_requested" -> error("Transfer canceled by peer.")
                                    }
                                }
                            }
                            else -> prefetched = control
                        }
                    } else {
                        Thread.sleep(40)
                    }
                }
                if (pausedNoticeSent && !pauseCurrent.get() && !resumedByPeer && !peerPauseCurrent.get()) {
                    writeControl(output, flowControl(session.transferId, "resumed"))
                }

                val message = prefetched ?: readControl(input)
                when (message.optString("type")) {
                    "file_start" -> {
                        if (message.optString("transferId") != session.transferId) {
                            error("Worker received a file for the wrong transfer.")
                        }
                        closeActiveStream()
                        val relativePath = message.optString("relativePath")
                        val size = message.optLong("size")
                        val offset = message.optLong("offset")
                        val outputUri = synchronized(session.createdDirs) {
                            findPartialFileForPath(session.saveTreeUri, session.createdDirs, relativePath)
                                ?: createPartialFileForPath(session.saveTreeUri, session.createdDirs, relativePath)
                        }
                        val existingSize = documentSize(outputUri)
                        if (existingSize != offset) {
                            error("Partial file size mismatch for $relativePath.")
                        }
                        val raw = context.contentResolver.openOutputStream(outputUri, if (offset > 0) "wa" else "w")
                            ?: error("Could not open output for $relativePath.")
                        activeRelativePath = relativePath
                        activeOutputUri = outputUri
                        activeStream = BufferedOutputStream(raw, buffer.size)
                        activeWritten = offset
                        activeSize = size
                        session.activeFiles[workerId] = relativePath
                    }
                    "file_chunk" -> {
                        val relativePath = activeRelativePath ?: error("Chunk before file start.")
                        if (message.optString("transferId") != session.transferId ||
                            message.optString("relativePath") != relativePath
                        ) {
                            error("Worker received a chunk for the wrong file.")
                        }
                        val offset = message.optLong("offset")
                        val length = message.optInt("length")
                        val sequence = message.optLong("sequence")
                        if (offset != activeWritten) {
                            error("Unexpected chunk offset for $relativePath.")
                        }
                        var remaining = length
                        while (remaining > 0) {
                            if (cancelCurrent.get()) error("Canceled on Android.")
                            val read = minOf(buffer.size, remaining)
                            input.readFully(buffer, 0, read)
                            activeStream?.write(buffer, 0, read)
                            activeWritten += read
                            remaining -= read
                            val completed = session.completedBytes.addAndGet(read.toLong())
                            val now = System.currentTimeMillis()
                            // 1-second average: emit at most once per window
                            // per worker so the readout holds steady.
                            if (now - lastUiAtMs >= 1000) {
                                val elapsedMs = (now - lastProgressAtMs).coerceAtLeast(1)
                                val deltaBytes = completed - lastProgressBytes
                                val speedMbps = (deltaBytes.toDouble() * 8.0) / elapsedMs.toDouble() / 1000.0
                                lastProgressBytes = completed
                                lastProgressAtMs = now
                                lastUiAtMs = now
                                val progress = ReceiveProgress(
                                    peerName = session.sender,
                                    currentFile = relativePath,
                                    completedBytes = completed.coerceAtMost(session.totalBytes),
                                    totalBytes = session.totalBytes,
                                    active = !pauseCurrent.get(),
                                    speedMbps = speedMbps,
                                    fileCount = session.fileCount,
                                    currentFiles = activeFileNames(session.activeFiles)
                                )
                                latestProgress = progress
                                onProgress(progress)
                            } else {
                                latestProgress = ReceiveProgress(
                                    peerName = session.sender,
                                    currentFile = relativePath,
                                    completedBytes = completed.coerceAtMost(session.totalBytes),
                                    totalBytes = session.totalBytes,
                                    active = !pauseCurrent.get(),
                                    speedMbps = latestProgress?.speedMbps ?: 0.0,
                                    fileCount = session.fileCount,
                                    currentFiles = activeFileNames(session.activeFiles)
                                )
                            }
                        }

                        writeControl(
                            output,
                            JSONObject()
                                .put("type", "file_chunk_ack")
                                .put("protocolVersion", 2)
                                .put("transferId", session.transferId)
                                .put("relativePath", relativePath)
                                .put("nextOffset", activeWritten)
                                .put("sequence", sequence)
                                .put("acknowledgedAtMs", System.currentTimeMillis())
                        )
                    }
                    "file_end" -> {
                        val relativePath = activeRelativePath ?: error("File end before file start.")
                        val outputUri = activeOutputUri ?: error("Missing partial file.")
                        if (message.optString("transferId") != session.transferId ||
                            message.optString("relativePath") != relativePath ||
                            activeWritten != activeSize
                        ) {
                            error("Incomplete file $relativePath.")
                        }
                        closeActiveStream()
                        synchronized(session.createdDirs) {
                            finalizePartialFile(session.saveTreeUri, session.createdDirs, relativePath, outputUri)
                        }
                        session.activeFiles.remove(workerId)
                        onStatus("Received $relativePath")
                        activeRelativePath = null
                        activeOutputUri = null
                        activeWritten = 0L
                        activeSize = 0L
                    }
                    "parallel_worker_complete" -> {
                        if (message.optString("transferId") != session.transferId ||
                            message.optInt("workerId") != workerId
                        ) {
                            error("Worker completion did not match this Android worker.")
                        }
                        closeActiveStream()
                        return
                    }
                    "transfer_flow_control" -> {
                        when (message.optString("state")) {
                            "cancel_requested" -> error("Transfer canceled by peer.")
                            "paused" -> {
                                peerPauseCurrent.set(true)
                                pauseCurrent.set(true)
                                latestProgress?.let { progress ->
                                    latestProgress = progress.copy(active = false, speedMbps = 0.0)
                                    onProgress(latestProgress)
                                }
                                onStatus("${session.sender} paused the transfer.")
                            }
                            "resumed" -> {
                                peerPauseCurrent.set(false)
                                pauseCurrent.set(false)
                                latestProgress?.let { progress ->
                                    latestProgress = progress.copy(active = true)
                                    onProgress(latestProgress)
                                }
                                onStatus("${session.sender} resumed the transfer.")
                            }
                        }
                    }
                    else -> error("Unexpected Android worker frame.")
                }
            }
        } finally {
            session.activeFiles.remove(workerId)
            closeActiveStream()
        }
    }

    private fun activeFileNames(activeFiles: ConcurrentHashMap<Int, String>): List<String> =
        activeFiles.entries
            .sortedBy { it.key }
            .map { it.value }

    private fun createPartialFileForPath(
        treeUri: Uri,
        createdDirs: MutableMap<String, Uri>,
        relativePath: String
    ): Uri {
        val category = categoryFor(relativePath)
        val parts = listOf("LanDrop", category) + relativePath.split('/').filter { it.isNotBlank() }
        var parentKey = ""
        var parentUri = createdDirs.getValue("")
        parts.dropLast(1).forEach { rawSegment ->
            val segment = sanitizeName(rawSegment)
            val nextKey = if (parentKey.isBlank()) segment else "$parentKey/$segment"
            parentUri = createdDirs.getOrPut(nextKey) {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parentUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    segment
                ) ?: error("Could not create $segment")
            }
            parentKey = nextKey
        }
        val fileName = partialName(sanitizeName(parts.lastOrNull() ?: "file"))
        return DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            "application/octet-stream",
            fileName
        ) ?: error("Could not create $fileName")
    }

    private fun findPartialFileForPath(
        treeUri: Uri,
        createdDirs: MutableMap<String, Uri>,
        relativePath: String
    ): Uri? {
        val parentUri = ensureParentDirectory(treeUri, createdDirs, relativePath)
        return findChildByName(treeUri, parentUri, partialName(sanitizeName(relativePath.substringAfterLast('/'))))
    }

    private fun finalizePartialFile(
        treeUri: Uri,
        createdDirs: MutableMap<String, Uri>,
        relativePath: String,
        partialUri: Uri
    ) {
        val finalName = sanitizeName(relativePath.substringAfterLast('/'))
        DocumentsContract.renameDocument(context.contentResolver, partialUri, finalName)
            ?: error("Could not finalize $relativePath")
    }

    private fun ensureParentDirectory(
        treeUri: Uri,
        createdDirs: MutableMap<String, Uri>,
        relativePath: String
    ): Uri {
        val category = categoryFor(relativePath)
        val parts = listOf("LanDrop", category) + relativePath.split('/').filter { it.isNotBlank() }
        var parentKey = ""
        var parentUri = createdDirs.getValue("")
        parts.dropLast(1).forEach { rawSegment ->
            val segment = sanitizeName(rawSegment)
            val nextKey = if (parentKey.isBlank()) segment else "$parentKey/$segment"
            parentUri = createdDirs.getOrPut(nextKey) {
                findChildByName(treeUri, parentUri, segment)
                    ?: DocumentsContract.createDocument(
                        context.contentResolver,
                        parentUri,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        segment
                    )
                    ?: error("Could not create $segment")
            }
            parentKey = nextKey
        }
        return parentUri
    }

    private fun findChildByName(treeUri: Uri, parentUri: Uri, displayName: String): Uri? {
        val parentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == displayName) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
                }
            }
        }
        return null
    }

    private fun documentSize(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                return cursor.getLong(sizeIndex)
            }
        }
        return 0L
    }

    private fun partialName(fileName: String): String = "$fileName.landrop.part"

    private fun peerHelloResponse(): JSONObject {
        return JSONObject()
            .put("type", "peer_hello_response")
            .put("protocolVersion", 1)
            .put("deviceId", deviceId)
            .put("instanceId", instanceId)
            .put("deviceName", deviceName)
            .put("tcpPort", 50501)
            .put("availability", if (receiveEnabledProvider()) "available" else "receive_disabled")
            .put("requiresPairCode", pairCodeRequiredProvider())
            .put("deviceKind", "android")
            .put("respondedAtMs", System.currentTimeMillis())
    }

    private fun selectTransferEncoding(offer: JSONObject): String {
        val encodings = offer.optJSONArray("supportedTransferEncodings") ?: return "raw_v1"
        for (index in 0 until encodings.length()) {
            if (encodings.optString(index) == "framed_v2") {
                return "framed_v2"
            }
        }
        return "raw_v1"
    }

    private fun selectedParallelFileStreams(
        offer: JSONObject,
        selectedEncoding: String,
        forceSingleStream: Boolean = false
    ): Int {
        if (selectedEncoding != "framed_v2") return 1
        if (forceSingleStream) return 1
        val offered = offer.optInt("maxParallelFileStreams", 1).coerceIn(1, 100)
        val local = concurrentFileStreamsProvider().coerceIn(1, 100)
        val fileCount = offer.optInt("fileCount", 1).coerceAtLeast(1)
        return minOf(offered, local, fileCount).coerceAtLeast(1)
    }

    private fun transferResponse(
        transferId: String,
        accepted: Boolean,
        message: String,
        selectedEncoding: String = "raw_v1",
        selectedParallelFileStreams: Int = 1
    ): JSONObject {
        val response = JSONObject()
            .put("type", "transfer_response")
            .put("protocolVersion", 1)
            .put("transferId", transferId)
            .put("accepted", accepted)
            .put("message", message)
            .put("respondedAtMs", System.currentTimeMillis())
        if (accepted) {
            response.put("selectedTransferEncoding", selectedEncoding)
            response.put("selectedParallelFileStreams", selectedParallelFileStreams.coerceIn(1, 100))
        }
        return response
    }

    private fun transferReceipt(transferId: String, success: Boolean, message: String): JSONObject {
        return JSONObject()
            .put("type", "transfer_receipt")
            .put("protocolVersion", 1)
            .put("transferId", transferId)
            .put("success", success)
            .put("message", message)
            .put("savedInto", if (success) "Android selected folder" else JSONObject.NULL)
            .put("respondedAtMs", System.currentTimeMillis())
    }

    private fun flowControl(transferId: String, state: String): JSONObject {
        return JSONObject()
            .put("type", "transfer_flow_control")
            .put("protocolVersion", 2)
            .put("transferId", transferId)
            .put("state", state)
            .put("reason", "user")
            .put("sentAtMs", System.currentTimeMillis())
    }

    private fun readControl(input: DataInputStream): JSONObject {
        val length = input.readInt()
        require(length in 0..(16 * 1024 * 1024)) { "Invalid control size." }
        val payload = ByteArray(length)
        input.readFully(payload)
        return JSONObject(String(payload, Charsets.UTF_8))
    }

    private fun writeControl(output: DataOutputStream, json: JSONObject) {
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        synchronized(controlWriteLock) {
            output.writeInt(payload.size)
            output.write(payload)
            output.flush()
        }
    }

    private fun sanitizeName(value: String): String {
        return value.replace('\\', '_').replace('/', '_').ifBlank { "file" }
    }

    private fun categoryFor(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> "Images"
            "mp4", "mkv", "mov", "avi", "webm", "m4v" -> "Videos"
            "mp3", "wav", "flac", "aac", "m4a", "ogg" -> "Music"
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> "Compressed"
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "epub" -> "Documents"
            else -> "Files"
        }
    }
}
