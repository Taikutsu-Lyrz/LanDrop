package dev.landrop.mobile

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidRemoteInviteTest {
    @Test
    fun compactInviteDecodesEveryPinnedField() {
        val certificate = ByteArray(420) { index -> (index % 251).toByte() }
        val token = "0123456789abcdef".repeat(4)
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(1)
                output.writeByte("169.150.197.173".length)
                output.writeBytes("169.150.197.173")
                output.writeShort(50502)
                output.writeByte("LEGION".length)
                output.writeBytes("LEGION")
                output.writeShort(certificate.size)
                output.write(certificate)
                output.writeByte(token.length)
                output.writeBytes(token)
            }
            bytes.toByteArray()
        }
        val invite = "landrop://r/" + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload)

        val parsed = parseRemoteInvite(invite)

        assertEquals("LEGION", parsed.deviceName)
        assertEquals("169.150.197.173", parsed.host)
        assertEquals(50502, parsed.port)
        assertEquals(token, parsed.accessToken)
        assertArrayEquals(certificate, parsed.certificateDer)
    }

    @Test
    fun jsonInviteAcceptsUppercaseFingerprint() {
        val certificate = ByteArray(64) { index -> index.toByte() }
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest(certificate)
            .joinToString("") { byte -> "%02x".format(byte) }
            .uppercase()
        val json = JSONObject()
            .put("version", 1)
            .put("deviceName", "Home PC")
            .put("host", "203.0.113.25")
            .put("port", 50502)
            .put(
                "certificateDer",
                Base64.getUrlEncoder().withoutPadding().encodeToString(certificate)
            )
            .put("certificateFingerprint", fingerprint)
            .put("accessToken", "abcdefghijklmnopqrstuvwxyz012345")
        val invite = "landrop://remote/" + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.toString().toByteArray(Charsets.UTF_8))

        val parsed = parseRemoteInvite(invite)
        assertEquals("Home PC", parsed.deviceName)
        assertEquals("203.0.113.25", parsed.host)
        assertEquals(50502, parsed.port)
        assertArrayEquals(certificate, parsed.certificateDer)
    }

    @Test
    fun compactInviteRejectsZeroPort() {
        val certificate = ByteArray(32) { 7 }
        val token = "abcdefghijklmnopqrstuvwxyz012345"
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(1)
                output.writeByte("203.0.113.25".length)
                output.writeBytes("203.0.113.25")
                output.writeShort(0)
                output.writeByte("PC".length)
                output.writeBytes("PC")
                output.writeShort(certificate.size)
                output.write(certificate)
                output.writeByte(token.length)
                output.writeBytes(token)
            }
            bytes.toByteArray()
        }
        val invite = "landrop://r/" + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload)
        assertThrows(IllegalArgumentException::class.java) {
            parseRemoteInvite(invite)
        }
    }
}
