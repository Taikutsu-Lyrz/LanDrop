package dev.landrop.mobile

import java.io.DataInputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

internal class TransferApproval {
    private val decision = AtomicReference<Boolean?>(null)

    fun decide(accepted: Boolean): Boolean = decision.compareAndSet(null, accepted)

    fun decision(): Boolean? = decision.get()
}

internal enum class ApprovalResult { Accepted, Rejected, Canceled, TimedOut }

/** Wait for the user while still noticing a sender cancel or disconnect. */
internal fun awaitTransferApproval(
    socket: Socket,
    input: DataInputStream,
    approval: TransferApproval,
    transferId: String,
    timeoutMs: Long = 300_000
): ApprovalResult {
    val previousTimeout = socket.soTimeout
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    try {
        while (true) {
            approval.decision()?.let {
                return if (it) ApprovalResult.Accepted else ApprovalResult.Rejected
            }
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) return ApprovalResult.TimedOut
            socket.soTimeout = minOf(200L, remainingMs).coerceAtLeast(1).toInt()
            // Only the first byte uses a short timeout. Once a frame starts,
            // consume it completely so a slow/fragmented cancel cannot corrupt it.
            val firstByte = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (firstByte < 0) return ApprovalResult.Canceled
            socket.soTimeout = minOf(5_000L, remainingMs).coerceAtLeast(1).toInt()
            val length = (firstByte shl 24) or (input.readUnsignedByte() shl 16) or
                (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
            require(length in 1..(4 * 1024 * 1024)) { "Invalid approval control frame." }
            val payload = ByteArray(length)
            input.readFully(payload)
            val message = JSONObject(String(payload, Charsets.UTF_8))
            if (message.optString("type") == "transfer_flow_control" &&
                message.optString("transferId") == transferId &&
                message.optString("state") == "cancel_requested"
            ) {
                return ApprovalResult.Canceled
            }
            error("Unexpected message while waiting for acceptance.")
        }
    } finally {
        // Prevent a late tap on a dismissed offer from approving a dead connection.
        approval.decide(false)
        if (!socket.isClosed) socket.soTimeout = previousTimeout
    }
}
