package com.example.viewer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Plays live audio streamed from a HomeCam node over HTTP (/audio.wav).
 * Streams 16-bit 16kHz mono PCM directly to AudioTrack with minimal buffering.
 */
class AudioPlayer(private val client: OkHttpClient) {

    /**
     * Plays until cancelled or the stream ends. Returns null when the stream simply ended,
     * or a reason to show the user (e.g. the camera has no microphone permission).
     */
    suspend fun play(audioUrl: String, token: String): String? = withContext(Dispatchers.IO) {
        val minBufferSize = AudioTrack.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes((minBufferSize * 2).coerceAtLeast(4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val request = Request.Builder()
            .url(audioUrl)
            .header("Authorization", "Bearer $token")
            .build()

        var inputStream: InputStream? = null
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val reason = response.body?.string()?.trim().orEmpty()
                val code = response.code
                response.close()
                return@withContext when {
                    reason.isNotEmpty() && code == 503 -> reason
                    code == 404 -> "Camera phone needs the latest HomeCam for audio"
                    else -> "Audio failed: HTTP $code"
                }
            }

            val body = response.body ?: return@withContext "Audio failed: empty reply"
            inputStream = body.byteStream()

            track.play()

            // Skip 44-byte WAV header if present
            val header = ByteArray(44)
            var headerRead = 0
            while (headerRead < 44 && coroutineContext.isActive) {
                val n = inputStream.read(header, headerRead, 44 - headerRead)
                if (n < 0) break
                headerRead += n
            }

            // 16-bit samples are 2 bytes: a network read can end mid-sample, so only
            // write whole samples and carry an odd byte over to the next read.
            val buffer = ByteArray(2048)
            var carry = 0
            while (coroutineContext.isActive) {
                val read = inputStream.read(buffer, carry, buffer.size - carry)
                if (read < 0) break
                val total = carry + read
                val whole = total and 1.inv()
                if (whole > 0) track.write(buffer, 0, whole)
                carry = total - whole
                if (carry == 1) buffer[0] = buffer[whole]
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null // dropped connection: caller reconnects
        } finally {
            try {
                inputStream?.close()
                track.stop()
                track.release()
            } catch (_: Exception) {
            }
        }
    }
}
