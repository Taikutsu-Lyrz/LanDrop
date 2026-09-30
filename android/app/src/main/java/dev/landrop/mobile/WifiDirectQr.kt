package dev.landrop.mobile

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.security.SecureRandom

data class WifiDirectCredentials(
    val networkName: String,
    val passphrase: String
) {
    fun toQrPayload(): String {
        return Uri.Builder()
            .scheme("landrop-wfd")
            .authority("pair")
            .appendQueryParameter("v", "1")
            .appendQueryParameter("ssid", networkName)
            .appendQueryParameter("pass", passphrase)
            .build()
            .toString()
    }

    companion object {
        private const val NETWORK_PREFIX = "DIRECT-LD-"
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val random = SecureRandom()

        fun generate(): WifiDirectCredentials {
            val suffix = buildString {
                repeat(6) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
            }
            val password = buildString {
                repeat(16) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
            }
            return WifiDirectCredentials(
                networkName = NETWORK_PREFIX + suffix,
                passphrase = password
            )
        }

        fun fromQrPayload(raw: String): WifiDirectCredentials? {
            return fromLandropPayload(raw) ?: fromWifiQrPayload(raw)
        }

        private fun fromLandropPayload(raw: String): WifiDirectCredentials? {
            return runCatching {
                val uri = Uri.parse(raw.trim())
                if (uri.scheme != "landrop-wfd" || uri.host != "pair") return null
                if (uri.getQueryParameter("v") != "1") return null
                val ssid = uri.getQueryParameter("ssid")?.trim().orEmpty()
                val passphrase = uri.getQueryParameter("pass")?.trim().orEmpty()
                if (ssid.isBlank() || passphrase.length !in 8..63) return null
                WifiDirectCredentials(ssid, passphrase)
            }.getOrNull()
        }

        /**
         * Standard camera/Wi-Fi-settings QR emitted by the Windows host:
         * WIFI:T:WPA;S:<ssid>;P:<pass>;; — backslash escapes honoured.
         */
        private fun fromWifiQrPayload(raw: String): WifiDirectCredentials? {
            return runCatching {
                val text = raw.trim()
                if (!text.startsWith("WIFI:", ignoreCase = true)) return null
                val body = text.substring(5)
                val fields = splitWifiFields(body)
                val type = fields["T"]?.uppercase().orEmpty()
                // Windows always hosts WPA2; accept WPA-family types only.
                if (type.isNotEmpty() && type != "WPA" && type != "WPA2" && type != "SAE") return null
                val ssid = fields["S"].orEmpty()
                val passphrase = fields["P"].orEmpty()
                if (ssid.isBlank() || passphrase.length !in 8..63) return null
                WifiDirectCredentials(ssid, passphrase)
            }.getOrNull()
        }

        private fun splitWifiFields(body: String): Map<String, String> {
            val fields = mutableMapOf<String, String>()
            val key = StringBuilder()
            val value = StringBuilder()
            var readingKey = true
            var escaped = false
            var i = 0
            fun flush() {
                if (key.isNotEmpty()) fields[key.toString()] = value.toString()
                key.clear()
                value.clear()
                readingKey = true
            }
            while (i < body.length) {
                val c = body[i]
                if (escaped) {
                    (if (readingKey) key else value).append(c)
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (readingKey && c == ':') {
                    readingKey = false
                } else if (!readingKey && c == ';') {
                    if (i + 1 < body.length && body[i + 1] == ';') {
                        // Trailing ";;" terminator.
                        flush()
                        break
                    }
                    flush()
                } else {
                    (if (readingKey) key else value).append(c)
                }
                i++
            }
            return fields
        }
    }
}

@Composable
fun WifiDirectQrCode(
    credentials: WifiDirectCredentials,
    modifier: Modifier = Modifier
) {
    val payload = credentials.toQrPayload()
    val bitmap = remember(payload) { renderQrCode(payload, 720) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Wi-Fi Direct pairing QR code",
        modifier = modifier,
        contentScale = ContentScale.Fit
    )
}

private fun renderQrCode(payload: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    val black = android.graphics.Color.BLACK
    val white = android.graphics.Color.WHITE
    for (y in 0 until size) {
        val row = y * size
        for (x in 0 until size) {
            pixels[row + x] = if (matrix[x, y]) black else white
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
