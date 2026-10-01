package dev.landrop.mobile

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferSourceTest {
    @Test
    fun successfulNullableAckResultDoesNotReportSourceOpenFailure() {
        var closed = false
        val source = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun close() { closed = true; super.close() }
        }
        val result: String? = useTransferSource(source, "photo.jpg") {
            assertEquals(3, it.readBytes().size)
            // flushPendingChunkAcks returns null when every chunk was received.
            null
        }
        assertNull(result)
        assertTrue("The successful transfer must release its source", closed)
    }

    @Test
    fun missingSourceFailsBeforeSending() {
        var sent = false
        val failure = runCatching {
            useTransferSource(null, "photo.jpg") { sent = true }
        }.exceptionOrNull()
        assertEquals("Could not open photo.jpg.", failure?.message)
        assertTrue(!sent)
    }
}
