package dev.landrop.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real Android emulator/device.
 *
 * Expects an invite file pushed to the device at:
 *   /data/local/tmp/landrop-remote-invite.txt
 * pointing at the host PC reachable from the emulator (usually 10.0.2.2:50502).
 */
@RunWith(AndroidJUnit4::class)
class AndroidRemoteEmulatorE2ETest {
    @Test
    fun remoteInviteTunnelProbesHostAcrossNetwork() {
        val invitePath = File("/data/local/tmp/landrop-remote-invite.txt")
        assertTrue(
            "Invite missing. Push it with: adb push invite.txt /data/local/tmp/landrop-remote-invite.txt",
            invitePath.isFile && invitePath.length() > 0
        )
        val invite = invitePath.readText().trim()
        assertTrue(invite.startsWith("landrop://r/") || invite.startsWith("landrop://remote/"))

        val parsed = parseRemoteInvite(invite)
        assertEquals("Host PC", parsed.deviceName)
        assertTrue(parsed.port in 1..65535)

        AndroidRemoteTunnel().use { tunnel ->
            val localPort = tunnel.start(invite)
            assertTrue("local tunnel port should be ephemeral", localPort in 1..65535)

            val probeJson = RustBridge.probePeer("127.0.0.1", localPort)
            val probe = JSONObject(probeJson)
            assertTrue(
                "probe failed: $probeJson",
                probe.optBoolean("ok", false)
            )
            assertEquals("Host PC", probe.getString("name"))
            assertEquals("available", probe.optString("availability", "available"))
        }

        // Keep a breadcrumb for the host-side runner.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "remote-e2e-pass.txt").writeText("ok\n")
    }
}
