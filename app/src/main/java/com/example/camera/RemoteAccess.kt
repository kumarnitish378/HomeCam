package com.example.camera

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * HTTPS front door for internet (IPv6) access without Tailscale.
 *
 * Listens on [port] on all interfaces (IPv6 included), completes TLS with the camera's
 * keystore certificate, then pipes the decrypted bytes to the normal HTTP server on
 * 127.0.0.1:[localPort]. The HTTP server still demands the access token on every request,
 * so the internet sees: TLS (pinned by the viewer) + token.
 */
class RemoteTlsProxy(
    private val sslContext: SSLContext,
    private val port: Int,
    private val localPort: Int
) {
    companion object {
        private const val TAG = "RemoteTlsProxy"
        private const val MAX_CONNECTIONS = 16
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
    }

    @Volatile private var server: SSLServerSocket? = null
    private val slots = Semaphore(MAX_CONNECTIONS)

    /** Starts listening; throws if the port can't be opened. */
    fun start() {
        val ss = sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
        ss.reuseAddress = true
        ss.enabledProtocols = ss.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        try {
            ss.bind(InetSocketAddress(InetAddress.getByName("::"), port))
        } catch (_: Exception) {
            ss.bind(InetSocketAddress(port)) // no IPv6 wildcard on this device
        }
        server = ss
        Thread({ acceptLoop(ss) }, "homecam-tls-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) { }
        server = null
    }

    private fun acceptLoop(ss: SSLServerSocket) {
        while (!ss.isClosed) {
            val client = try { ss.accept() as SSLSocket } catch (_: Exception) { break }
            if (!slots.tryAcquire()) {
                closeQuietly(client) // too many connections
                continue
            }
            Thread({
                try { handle(client) } finally { slots.release() }
            }, "homecam-tls-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: SSLSocket) {
        var backend: Socket? = null
        try {
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            client.startHandshake()
            client.soTimeout = 0
            backend = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress("127.0.0.1", localPort), 3000)
            }
            val b = backend
            val upstream = Thread({ pipe(client.inputStream, b.outputStream, client, b) }, "homecam-tls-up")
            upstream.isDaemon = true
            upstream.start()
            pipe(b.inputStream, client.outputStream, client, b)
            upstream.join(2000)
        } catch (e: Exception) {
            Log.d(TAG, "connection ended: ${e.message}")
        } finally {
            closeQuietly(client)
            backend?.let { closeQuietly(it) }
        }
    }

    /** Copies until either side closes, then closes both so the other pipe stops too. */
    private fun pipe(input: InputStream, output: OutputStream, a: Closeable, b: Closeable) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            closeQuietly(a)
            closeQuietly(b)
        }
    }

    private fun closeQuietly(c: Closeable) {
        try { c.close() } catch (_: Exception) { }
    }
}

/**
 * Keeps a DuckDNS name (e.g. "nitishcam" -> nitishcam.duckdns.org) pointing at the phone's
 * current IPv6, so viewers still find the camera after the router gets a new IPv6 prefix.
 */
object DuckDnsUpdater {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun fullName(domain: String): String =
        domain.trim().removeSuffix(".duckdns.org").lowercase() + ".duckdns.org"

    /** Returns true when DuckDNS answered "OK". Blocking; call off the main thread. */
    fun update(domain: String, token: String, ipv6: String): Boolean {
        val sub = domain.trim().removeSuffix(".duckdns.org").lowercase()
        if (sub.isBlank() || token.isBlank()) return false
        val url = "https://www.duckdns.org/update".toHttpUrl().newBuilder()
            .addQueryParameter("domains", sub)
            .addQueryParameter("token", token.trim())
            .addQueryParameter("ipv6", ipv6)
            .build()
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                resp.isSuccessful && resp.body?.string()?.trim()?.startsWith("OK") == true
            }
        } catch (_: Exception) {
            false
        }
    }
}
