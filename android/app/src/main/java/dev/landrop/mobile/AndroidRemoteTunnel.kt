package dev.landrop.mobile

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val REMOTE_INVITE_PREFIX = "landrop://remote/"
private const val COMPACT_REMOTE_INVITE_PREFIX = "landrop://r/"
private const val REMOTE_PROTOCOL_VERSION = 1
private const val MAX_REMOTE_HANDSHAKE = 8 * 1024
private const val REMOTE_INVITE_KEY_ALIAS = "landrop_remote_invite"
private const val REMOTE_INVITE_PREF = "remote_invite_encrypted"

data class AndroidRemoteInvite(
    val deviceName: String,
    val host: String,
    val port: Int,
    val certificateDer: ByteArray,
    val certificateFingerprint: String,
    val accessToken: String
)

class AndroidRemoteTunnel : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private var listener: ServerSocket? = null
    private var invite: AndroidRemoteInvite? = null
    private var socketFactory: SSLSocketFactory? = null

    @Synchronized
    fun start(encodedInvite: String): Int {
        closeListener()
        val parsedInvite = parseRemoteInvite(encodedInvite)
        val factory = pinnedSocketFactory(parsedInvite.certificateDer)
        runCatching {
            openAuthorizedSocket(parsedInvite, factory).use { }
        }.getOrElse { error ->
            throw IllegalStateException(remoteConnectionError(parsedInvite, error), error)
        }
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        invite = parsedInvite
        socketFactory = factory
        listener = server
        running.set(true)
        executor.execute { acceptLoop(server) }
        AppLog.add(
            "Secure remote tunnel ready for ${parsedInvite.deviceName} at " +
                "127.0.0.1:${server.localPort}"
        )
        return server.localPort
    }

    fun currentDeviceName(): String? = invite?.deviceName

    @Synchronized
    fun stop() {
        closeListener()
    }

    override fun close() {
        running.set(false)
        closeListener()
        executor.shutdownNow()
    }

    @Synchronized
    private fun closeListener() {
        running.set(false)
        runCatching { listener?.close() }
        listener = null
        invite = null
        socketFactory = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get() && !server.isClosed) {
            val localSocket = runCatching { server.accept() }.getOrElse { error ->
                if (running.get()) {
                    AppLog.add("Remote tunnel listener stopped: ${error.message}")
                }
                return
            }
            executor.execute {
                runCatching { relayConnection(localSocket) }
                    .onFailure { error ->
                        AppLog.add("Secure remote connection failed: ${error.message}")
                        runCatching { localSocket.close() }
                    }
            }
        }
    }

    private fun relayConnection(localSocket: Socket) {
        val currentInvite = invite ?: error("Remote invite is unavailable.")
        val factory = socketFactory ?: error("Remote TLS is unavailable.")
        val tlsSocket = openAuthorizedSocket(currentInvite, factory)
        tlsSocket.soTimeout = 0

        val closed = AtomicBoolean(false)
        fun closeBoth() {
            if (closed.compareAndSet(false, true)) {
                runCatching { localSocket.close() }
                runCatching { tlsSocket.close() }
            }
        }

        executor.execute {
            runCatching {
                localSocket.getInputStream().copyTo(tlsSocket.outputStream, 256 * 1024)
                tlsSocket.outputStream.flush()
            }
            closeBoth()
        }
        executor.execute {
            runCatching {
                tlsSocket.inputStream.copyTo(localSocket.getOutputStream(), 256 * 1024)
                localSocket.getOutputStream().flush()
            }
            closeBoth()
        }
    }

    private fun openAuthorizedSocket(
        currentInvite: AndroidRemoteInvite,
        factory: SSLSocketFactory
    ): SSLSocket {
        val remoteSocket = Socket()
        try {
            remoteSocket.connect(
                InetSocketAddress(currentInvite.host, currentInvite.port),
                12_000
            )
            val tlsSocket = factory.createSocket(
                remoteSocket,
                "landrop.remote",
                currentInvite.port,
                true
            ) as SSLSocket
            tlsSocket.soTimeout = 15_000
            tlsSocket.enabledProtocols = tlsSocket.supportedProtocols
                .filter { it == "TLSv1.3" }
                .toTypedArray()
                .takeIf { it.isNotEmpty() }
                ?: tlsSocket.enabledProtocols
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tlsSocket.sslParameters = tlsSocket.sslParameters.apply {
                    applicationProtocols = arrayOf("landrop/2")
                }
            }
            tlsSocket.startHandshake()

            val tlsInput = DataInputStream(tlsSocket.inputStream)
            val tlsOutput = DataOutputStream(tlsSocket.outputStream)
            writeRemoteMessage(
                tlsOutput,
                JSONObject()
                    .put("version", REMOTE_PROTOCOL_VERSION)
                    .put("accessToken", currentInvite.accessToken)
            )
            val response = readRemoteMessage(tlsInput)
            if (!response.optBoolean("accepted", false)) {
                error(response.optString("message", "Remote invitation was rejected."))
            }
            tlsSocket.soTimeout = 0
            return tlsSocket
        } catch (error: Throwable) {
            runCatching { remoteSocket.close() }
            throw error
        }
    }
}

fun parseRemoteInvite(encodedInvite: String): AndroidRemoteInvite {
    val trimmed = encodedInvite.trim()
    if (trimmed.startsWith(COMPACT_REMOTE_INVITE_PREFIX)) {
        return parseCompactRemoteInvite(trimmed.removePrefix(COMPACT_REMOTE_INVITE_PREFIX))
    }
    val encoded = trimmed.removePrefix(REMOTE_INVITE_PREFIX)
    require(encoded != trimmed) { "This is not a LanDrop remote invite." }
    val payload = runCatching {
        Base64.getUrlDecoder().decode(encoded)
    }.getOrElse {
        throw IllegalArgumentException("Remote invite encoding is invalid.")
    }
    val json = runCatching {
        JSONObject(payload.toString(Charsets.UTF_8))
    }.getOrElse {
        throw IllegalArgumentException("Remote invite is invalid.")
    }
    require(json.optInt("version") == REMOTE_PROTOCOL_VERSION) {
        "Remote invite version is not supported."
    }
    val host = json.optString("host").trim()
    val port = json.optInt("port")
    val token = json.optString("accessToken")
    val certificateDer = runCatching {
        Base64.getUrlDecoder().decode(json.optString("certificateDer"))
    }.getOrElse {
        throw IllegalArgumentException("Remote invite certificate is invalid.")
    }
    val expectedFingerprint = json.optString("certificateFingerprint").lowercase()
    val actualFingerprint = MessageDigest.getInstance("SHA-256")
        .digest(certificateDer)
        .joinToString("") { byte -> "%02x".format(byte) }
    require(MessageDigest.isEqual(
        expectedFingerprint.toByteArray(Charsets.US_ASCII),
        actualFingerprint.toByteArray(Charsets.US_ASCII)
    )) {
        "Remote invite certificate fingerprint does not match."
    }
    require(host.isNotBlank() && port in 1..65535 && token.length >= 32) {
        "Remote invite is incomplete."
    }
    return AndroidRemoteInvite(
        deviceName = json.optString("deviceName", "Remote computer").take(48),
        host = host,
        port = port,
        certificateDer = certificateDer,
        certificateFingerprint = actualFingerprint,
        accessToken = token
    )
}

private fun parseCompactRemoteInvite(encoded: String): AndroidRemoteInvite {
    val payload = runCatching {
        Base64.getUrlDecoder().decode(encoded)
    }.getOrElse {
        throw IllegalArgumentException("Remote invite encoding is invalid.")
    }
    val input = DataInputStream(ByteArrayInputStream(payload))
    val version = runCatching { input.readUnsignedByte() }.getOrElse {
        throw IllegalArgumentException("Remote invite is truncated.")
    }
    require(version == REMOTE_PROTOCOL_VERSION) {
        "Remote invite version is not supported."
    }
    fun readBytes(length: Int): ByteArray {
        require(length >= 0 && length <= input.available()) { "Remote invite is truncated." }
        return ByteArray(length).also(input::readFully)
    }
    fun readString(length: Int): String =
        readBytes(length).toString(Charsets.UTF_8)

    val host = readString(input.readUnsignedByte()).trim()
    val port = input.readUnsignedShort()
    val deviceName = readString(input.readUnsignedByte()).ifBlank { "Remote computer" }
    val certificateDer = readBytes(input.readUnsignedShort())
    val token = readString(input.readUnsignedByte())
    require(input.available() == 0) { "Remote invite has unexpected trailing data." }
    require(host.isNotBlank() && port in 1..65535 && token.length >= 32) {
        "Remote invite is incomplete."
    }
    val fingerprint = MessageDigest.getInstance("SHA-256")
        .digest(certificateDer)
        .joinToString("") { byte -> "%02x".format(byte) }
    return AndroidRemoteInvite(
        deviceName = deviceName.take(48),
        host = host,
        port = port,
        certificateDer = certificateDer,
        certificateFingerprint = fingerprint,
        accessToken = token
    )
}

private fun remoteConnectionError(invite: AndroidRemoteInvite, error: Throwable): String {
    val root = generateSequence(error) { it.cause }.last()
    val detail = root.message.orEmpty()
    val noRoute = detail.contains("EHOSTUNREACH", ignoreCase = true) ||
        detail.contains("ENETUNREACH", ignoreCase = true) ||
        detail.contains("No route to host", ignoreCase = true) ||
        detail.contains("Network is unreachable", ignoreCase = true)
    return when {
        root is SocketTimeoutException ->
            "Timed out reaching ${invite.host}:${invite.port}. If this is a VPN/overlay invite, connect both devices to the same private network. Otherwise forward TCP ${invite.port} on the receiving PC's router (and any outer modem/router), then create a fresh QR invite."
        root is ConnectException && noRoute ->
            "No route to ${invite.host}:${invite.port}. For a VPN/overlay invite, connect both devices to the same private network and check the address. Otherwise this is usually double NAT/CGNAT: forward TCP ${invite.port} on the outer modem/router to the home router, or put that home router in bridge/DMZ mode, then create a new invite."
        root is ConnectException ->
            "Connection to ${invite.host}:${invite.port} was refused. Check that LanDrop is open and Remote access is enabled. For direct internet invites, also verify TCP ${invite.port} is forwarded; for VPN/overlay invites, verify both devices joined the same private network."
        noRoute ->
            "No route to ${invite.host}:${invite.port}. For a VPN/overlay invite, connect both devices to the same private network and check the address. Otherwise this is usually double NAT/CGNAT: forward TCP ${invite.port} on the outer modem/router to the home router, or put that home router in bridge/DMZ mode, then create a new invite."
        else -> detail.takeIf { it.isNotBlank() }
            ?: "Secure remote connection failed before authorization."
    }
}

private fun pinnedSocketFactory(certificateDer: ByteArray): SSLSocketFactory {
    val certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(certificateDer.inputStream()) as X509Certificate
    val trustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(certificate)

        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
            throw CertificateException("Client certificates are not accepted.")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
            val received = chain?.firstOrNull()
                ?: throw CertificateException("Remote computer did not provide a certificate.")
            if (!MessageDigest.isEqual(received.encoded, certificate.encoded)) {
                throw CertificateException("Remote computer identity does not match the invite.")
            }
            received.checkValidity()
        }
    }
    return SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
    }.socketFactory
}

private fun writeRemoteMessage(output: DataOutputStream, message: JSONObject) {
    val payload = message.toString().toByteArray(Charsets.UTF_8)
    require(payload.size <= MAX_REMOTE_HANDSHAKE) { "Remote handshake is too large." }
    output.writeInt(payload.size)
    output.write(payload)
    output.flush()
}

private fun readRemoteMessage(input: DataInputStream): JSONObject {
    val length = input.readInt()
    require(length in 0..MAX_REMOTE_HANDSHAKE) { "Remote handshake is too large." }
    val payload = ByteArray(length)
    input.readFully(payload)
    return JSONObject(payload.toString(Charsets.UTF_8))
}

object AndroidRemoteInviteStore {
    fun save(prefs: SharedPreferences, invite: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(invite.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(1 + cipher.iv.size + encrypted.size)
        payload[0] = cipher.iv.size.toByte()
        cipher.iv.copyInto(payload, destinationOffset = 1)
        encrypted.copyInto(payload, destinationOffset = 1 + cipher.iv.size)
        prefs.edit()
            .putString(REMOTE_INVITE_PREF, Base64.getEncoder().encodeToString(payload))
            .apply()
    }

    fun load(prefs: SharedPreferences): String? {
        val encoded = prefs.getString(REMOTE_INVITE_PREF, null) ?: return null
        return runCatching {
            val payload = Base64.getDecoder().decode(encoded)
            val ivLength = payload.first().toInt() and 0xff
            require(ivLength in 12..16 && payload.size > 1 + ivLength)
            val iv = payload.copyOfRange(1, 1 + ivLength)
            val encrypted = payload.copyOfRange(1 + ivLength, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrElse { error ->
            AppLog.add("Stored remote invite could not be opened: ${error.message}")
            clear(prefs)
            null
        }
    }

    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(REMOTE_INVITE_PREF).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(REMOTE_INVITE_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    REMOTE_INVITE_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }
}
