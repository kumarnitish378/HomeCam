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

    suspend fun play(audioUrl: String, token: String) = withContext(Dispatchers.IO) {
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
                response.close()
                return@withContext
            }

            val body = response.body ?: return@withContext
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

            val buffer = ByteArray(2048)
            while (coroutineContext.isActive) {
                val read = inputStream.read(buffer, 0, buffer.size)
                if (read < 0) break
                if (read > 0) {
                    track.write(buffer, 0, read)
                }
            }
        } catch (_: Exception) {
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
