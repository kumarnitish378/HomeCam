package com.example.pairing

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URLDecoder
import java.net.URLEncoder

data class PairingInfo(
    val name: String,
    val host: String,
    val port: Int,
    val token: String,
    /** Internet (IPv6/HTTPS) address: DuckDNS name or IPv6 literal; blank when off */
    val remoteHost: String = "",
    val remotePort: Int = 8443,
    /** SHA-256 of the camera's HTTPS certificate, pinned by the viewer */
    val certFp: String = ""
) {
    fun toUriString(): String {
        val encodedName = URLEncoder.encode(name, "UTF-8")
        val base = "homecam://pair?name=$encodedName&host=$host&port=$port&t=$token"
        return if (remoteHost.isNotBlank() && certFp.isNotBlank()) {
            base + "&rh=" + URLEncoder.encode(remoteHost, "UTF-8") + "&rp=$remotePort&fp=$certFp"
        } else base
    }

    companion object {
        fun fromUriString(raw: String): PairingInfo? {
            try {
                val trimmed = raw.trim()
                if (trimmed.startsWith("homecam://pair")) {
                    val queryString = trimmed.substringAfter('?', "")
                    val params = parseQuery(queryString)
                    val name = params["name"] ?: "HomeCam"
                    val host = params["host"] ?: return null
                    val port = params["port"]?.toIntOrNull() ?: 8080
                    val token = params["t"] ?: return null
                    return PairingInfo(
                        name = name,
                        host = host,
                        port = port,
                        token = token,
                        remoteHost = params["rh"].orEmpty(),
                        remotePort = params["rp"]?.toIntOrNull() ?: 8443,
                        certFp = params["fp"].orEmpty().lowercase()
                    )
                }

                // Also support http(s) URL fallback like http://100.x.x.x:8080/stream.mjpg?t=...
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    val noScheme = trimmed.substringAfter("://")
                    val hostAndPort = noScheme.substringBefore('/').substringBefore('?')
                    val host = if (hostAndPort.contains(':')) hostAndPort.substringBefore(':') else hostAndPort
                    val port = if (hostAndPort.contains(':')) hostAndPort.substringAfter(':').toIntOrNull() ?: 8080 else 8080
                    val queryString = trimmed.substringAfter('?', "")
                    val params = parseQuery(queryString)
                    val token = params["t"].orEmpty()
                    val name = "Camera ($host)"
                    return PairingInfo(name, host, port, token)
                }
            } catch (_: Exception) {
            }
            return null
        }

        private fun parseQuery(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            return query.split('&').mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                    val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                    k to v
                } else null
            }.toMap()
        }
    }
}

object QrCodeGenerator {
    /**
     * Generates a QR Code Bitmap for the given text using ZXing.
     */
    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)

            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }

            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (_: Exception) {
            null
        }
    }
}
