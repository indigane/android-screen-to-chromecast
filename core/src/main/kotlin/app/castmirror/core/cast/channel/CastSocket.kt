package app.castmirror.core.cast.channel

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The byte-stream abstraction [CastChannel] runs over.
 *
 * Production uses [TlsCastSocket]; tests use an in-memory implementation over piped streams, so
 * the whole channel layer is exercisable without a network.
 */
interface CastSocket : AutoCloseable {
    /** Stream of bytes arriving from the peer. */
    val input: InputStream

    /** Stream of bytes going to the peer. */
    val output: OutputStream

    /** Human-readable description used in log messages. */
    val description: String
}

/**
 * A [CastSocket] backed by an already-connected [Socket].
 *
 * @property socket the connected (and, for real receivers, TLS-wrapped) socket.
 */
class SocketCastSocket(private val socket: Socket) : CastSocket {
    override val input: InputStream = socket.getInputStream()
    override val output: OutputStream = socket.getOutputStream()
    override val description: String get() = socket.remoteSocketAddress?.toString() ?: "unconnected"
    override fun close() {
        try { socket.close() } catch (_: IOException) { }
    }
}

/**
 * TLS connection helpers for the Cast control channel.
 *
 * Cast receivers present a device certificate chained to Google's private "Cast Root CA", which
 * no default trust store contains, and whose CN is a device serial rather than the IP being
 * dialled. Standard validation therefore always fails, and every third-party sender — including
 * the reference implementations surveyed in the spec — disables it.
 *
 * Scope this [SSLContext] to Cast sockets only. Never install it as the JVM/Android default.
 */
object TlsCastSocket {

    /** Default Cast control-channel port. Always prefer the port from the mDNS SRV record. */
    const val DEFAULT_PORT = 8009

    /**
     * Cipher suites to force on very old receivers (Chromecast 1st gen) if the default
     * negotiation fails.
     *
     * SPEC-UNVERIFIED: the exact TLS version/cipher requirements of 2013-era Chromecasts are not
     * documented in any primary source (spec §10.2 item 10 / §11 item 10). This list is the
     * spec's best guess.
     */
    val LEGACY_CIPHER_SUITES = listOf("TLS_RSA_WITH_AES_128_CBC_SHA")

    /** An [X509TrustManager] that accepts every certificate chain. */
    private val TRUST_ALL: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Builds a permissive [SSLContext] for Cast sockets.
     *
     * @param protocol TLS protocol to request; `"TLS"` lets the platform pick the best available.
     */
    fun permissiveContext(protocol: String = "TLS"): SSLContext =
        SSLContext.getInstance(protocol).apply {
            init(null, arrayOf<TrustManager>(TRUST_ALL), SecureRandom())
        }

    /**
     * Connects to a Cast receiver and completes the TLS handshake.
     *
     * Hostname verification is deliberately *not* performed (the certificate CN is a device
     * serial). No `deviceauth` challenge is sent: the receiver never challenges the sender, so
     * the exchange buys a mirroring sender nothing (spec §2.5).
     *
     * @param host receiver IP or hostname from the mDNS A/SRV record.
     * @param port receiver port from the SRV record; 8009 in practice.
     * @param connectTimeoutMs TCP connect timeout.
     * @param readTimeoutMs `SO_TIMEOUT` for the reader; 0 disables it.
     * @param legacyCiphers when true, additionally enable [LEGACY_CIPHER_SUITES] and TLSv1.
     */
    fun connect(
        host: String,
        port: Int = DEFAULT_PORT,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 0,
        legacyCiphers: Boolean = false,
    ): CastSocket {
        val factory = permissiveContext().socketFactory
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
        val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
        if (legacyCiphers) {
            val supported = ssl.supportedCipherSuites.toSet()
            val wanted = (ssl.enabledCipherSuites.toList() + LEGACY_CIPHER_SUITES)
                .filter { it in supported }
                .distinct()
            if (wanted.isNotEmpty()) ssl.enabledCipherSuites = wanted.toTypedArray()
            val protos = ssl.supportedProtocols.filter { it.startsWith("TLS") }
            if (protos.isNotEmpty()) ssl.enabledProtocols = protos.toTypedArray()
        }
        ssl.soTimeout = readTimeoutMs
        ssl.useClientMode = true
        ssl.startHandshake()
        return SocketCastSocket(ssl)
    }
}
