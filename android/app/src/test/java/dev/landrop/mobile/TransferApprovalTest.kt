package dev.landrop.mobile

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TransferApprovalTest {
    private fun withConnection(block: (Socket, Socket) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { sender ->
                listener.accept().use { receiver -> block(sender, receiver) }
            }
        }
    }

    @Test
    fun acceptsUserDecisionWithoutWaitingForSocketTimeout() = withConnection { _, receiver ->
        receiver.soTimeout = 30_000
        val executor = Executors.newSingleThreadExecutor()
        try {
            val approval = TransferApproval()
            val result = executor.submit<ApprovalResult> {
                awaitTransferApproval(receiver, DataInputStream(receiver.getInputStream()), approval, "test")
            }
            approval.decide(true)
            assertEquals(ApprovalResult.Accepted, result.get(2, TimeUnit.SECONDS))
            assertEquals(30_000, receiver.soTimeout)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun senderCancelDismissesApprovalAndPreventsLateAcceptance() = withConnection { sender, receiver ->
        val approval = TransferApproval()
        val cancel = JSONObject().put("type", "transfer_flow_control")
            .put("transferId", "test").put("state", "cancel_requested")
            .toString().toByteArray(Charsets.UTF_8)
        DataOutputStream(sender.getOutputStream()).apply { writeInt(cancel.size); write(cancel); flush() }
        assertEquals(ApprovalResult.Canceled,
            awaitTransferApproval(receiver, DataInputStream(receiver.getInputStream()), approval, "test"))
        approval.decide(true)
        assertFalse(approval.decision()!!)
    }

    @Test
    fun disconnectedSenderDoesNotLeaveTheNextRequestStuck() {
        repeat(3) {
            withConnection { sender, receiver ->
                sender.close()
                assertEquals(ApprovalResult.Canceled,
                    awaitTransferApproval(receiver, DataInputStream(receiver.getInputStream()), TransferApproval(), "old"))
            }
            withConnection { _, receiver ->
                val approval = TransferApproval().apply { decide(true) }
                assertEquals(ApprovalResult.Accepted,
                    awaitTransferApproval(receiver, DataInputStream(receiver.getInputStream()), approval, "new"))
            }
        }
    }

    @Test
    fun unansweredOfferExpiresAndCannotBeAcceptedAfterward() = withConnection { _, receiver ->
        val approval = TransferApproval()
        assertEquals(ApprovalResult.TimedOut,
            awaitTransferApproval(receiver, DataInputStream(receiver.getInputStream()), approval, "test", timeoutMs = 30))
        approval.decide(true)
        assertFalse(approval.decision()!!)
    }
}
