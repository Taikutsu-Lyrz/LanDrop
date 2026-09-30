package dev.landrop.mobile

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.File
import org.json.JSONObject

/**
 * Debug-only headless remote E2E entrypoint for emulator/device runners.
 * Reads /data/local/tmp/landrop-remote-invite.txt, opens AndroidRemoteTunnel,
 * probes through RustBridge, and writes a result file.
 */
class RemoteE2EDebugActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outputCandidates = listOf(
            File(cacheDir, "landrop-remote-e2e-result.txt"),
            File(filesDir, "landrop-remote-e2e-result.txt"),
            getExternalFilesDir(null)?.let { File(it, "landrop-remote-e2e-result.txt") },
            File("/sdcard/Download/landrop-remote-e2e-result.txt")
        ).filterNotNull()
        outputCandidates.forEach { it.delete() }
        Thread {
            val status = runCatching {
                val invite = File("/data/local/tmp/landrop-remote-invite.txt").readText().trim()
                require(invite.isNotEmpty()) { "missing invite" }
                AndroidRemoteTunnel().use { tunnel ->
                    val localPort = tunnel.start(invite)
                    val probeJson = RustBridge.probePeer("127.0.0.1", localPort)
                    val probe = JSONObject(probeJson)
                    require(probe.optBoolean("ok", false)) { "probe failed: $probeJson" }
                    require(probe.getString("name") == "Host PC") {
                        "unexpected device name: ${probe.optString("name")}"
                    }
                }
                "PASS"
            }.getOrElse { error ->
                "FAIL ${error.message}"
            }
            outputCandidates.forEach { file ->
                runCatching { file.writeText(status + "\n") }
            }
            Handler(Looper.getMainLooper()).post { finish() }
        }.start()
    }
}
