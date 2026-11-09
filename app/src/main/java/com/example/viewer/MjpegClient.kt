package com.example.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Streams MJPEG from HomeCam over HTTP using OkHttp and parses multipart JPEG parts into Bitmaps.
 */
class MjpegClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS) // No frame for 10s = dropped connection
        .retryOnConnectionFailure(true)
        .build()

    fun streamFrames(
        url: String,
        token: String,
        onFpsCalculated: ((Int) -> Unit)? = null
    ): Flow<Bitmap> = flow {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()

        var frameCount = 0
        var windowStart = SystemClock.elapsedRealtime()

        val call = client.newCall(request)
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP error ${response.code}: ${response.message}")
            }

            val body = response.body ?: throw IOException("Empty response body")
            val source = body.source()

            while (currentCoroutineContext().isActive && !source.exhausted()) {
                var contentLength = -1

                // 1. Read multipart headers until empty line
                while (currentCoroutineContext().isActive) {
                    val line = source.readUtf8LineStrict()
                    if (line.isEmpty()) {
                        if (contentLength >= 0) break else continue
                    }
                    if (line.startsWith("Content-Length", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: -1
                    }
                }

                // 2. Read exactly contentLength bytes or up to boundary
                val jpegBytes = if (contentLength > 0) {
                    source.readByteArray(contentLength.toLong())
                } else {
                    // Fallback boundary search if Content-Length missing
                    val buffer = Buffer()
                    var b: Byte
                    while (currentCoroutineContext().isActive && !source.exhausted()) {
                        b = source.readByte()
                        buffer.writeByte(b.toInt())
                        if (buffer.size >= 2 &&
                            buffer[buffer.size - 2] == 0xFF.toByte() &&
                            buffer[buffer.size - 1] == 0xD9.toByte()
                        ) {
                            break // End of JPEG image marker (EOI 0xFF 0xD9)
                        }
                    }
                    buffer.readByteArray()
                }

                // 3. Decode JPEG to Android Bitmap
                if (jpegBytes.isNotEmpty()) {
                    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    if (bitmap != null) {
                        frameCount++
                        val now = SystemClock.elapsedRealtime()
                        if (now - windowStart >= 1000L) {
                            val elapsedSec = (now - windowStart) / 1000f
                            val fps = (frameCount / elapsedSec).toInt()
                            onFpsCalculated?.invoke(fps)
                            frameCount = 0
                            windowStart = now
                        }
                        emit(bitmap)
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
