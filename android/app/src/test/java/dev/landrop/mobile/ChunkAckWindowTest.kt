package dev.landrop.mobile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkAckWindowTest {
    @Test
    fun immediateAckDoesNotLeaveChunkPending() {
        val pending = ArrayDeque<PendingChunkAck>()
        // Small files can be acknowledged before the sender starts managing its window.
        repeat(7) { index ->
            val path = "file-$index.txt"
            val payload = """{"type":"file_chunk_ack","transferId":"test","relativePath":"$path","sequence":1}""".toByteArray()
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { it.writeInt(payload.size); it.write(payload) }
            val error = noteChunkSentAndManageAckWindow(
                DataInputStream(ByteArrayInputStream(bytes.toByteArray())),
                DataOutputStream(ByteArrayOutputStream()), Any(), "test", pending,
                PendingChunkAck(path, 100, 1), AtomicBoolean(false), AtomicBoolean(false), {}, {}, {}
            )
            assertNull(error)
            assertTrue("Fast ACK must retire file $index before finalization", pending.isEmpty())
        }
    }
}
