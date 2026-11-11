package com.example.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Captures live microphone audio in 16-bit mono PCM at 16kHz (~32 KB/sec)
 * and streams it on demand to connected viewers.
 *
 * Audio recording is only started when at least one viewer is listening,
 * ensuring zero battery and CPU impact when audio is muted or not being monitored.
 */
object AudioStreamer {
    const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private val activeListeners = AtomicInteger(0)
    private val isRecording = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    val audioChunks = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 32)

    /** Why audio can't be served right now, or null if it can. */
    fun problem(context: Context): String? = when {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED ->
            "Microphone permission not given on the camera phone"
        !FrameBus.serviceHasMic.value ->
            "Restart the camera on the camera phone to turn on the microphone"
        else -> null
    }

    @Synchronized
    fun startRecordingIfPermitted(context: Context) {
        if (isRecording.get()) return
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            FrameBus.audioError.value = "Microphone permission not given on the camera phone"
            return
        }

        try {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufSize = (minBuf * 2).coerceAtLeast(4096)

            @SuppressLint("MissingPermission")
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                FrameBus.audioError.value = "Microphone could not be opened (is another app using it?)"
                return
            }

            audioRecord = record
            record.startRecording()
            isRecording.set(true)
            FrameBus.audioError.value = null

            recordThread = Thread({
                val buffer = ByteArray(1024)
                while (isRecording.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val chunk = buffer.copyOf(read)
                        audioChunks.tryEmit(chunk)
                    } else if (read < 0) {
                        break
                    }
                }
            }, "HomeCam-AudioRecord").apply {
                priority = Thread.NORM_PRIORITY
                start()
            }
        } catch (e: Exception) {
            FrameBus.audioError.value = "Microphone error: ${e.message}"
            stopRecording()
        }
    }

    @Synchronized
    fun stopRecording() {
        isRecording.set(false)
        try {
            recordThread?.interrupt()
            recordThread = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (_: Exception) {
        }
    }

    /**
     * Streams continuous WAV audio over a Ktor ByteWriteChannel.
     */
    suspend fun streamTo(channel: ByteWriteChannel, context: Context) = withContext(Dispatchers.IO) {
        val count = activeListeners.incrementAndGet()
        if (count == 1) {
            startRecordingIfPermitted(context)
        }

        try {
            // Write 44-byte WAV header for streaming (ChunkSize = 0x7fffffff)
            val header = createWavHeader(SAMPLE_RATE, 1, 16)
            channel.writeFully(header, 0, header.size)
            channel.flush()

            audioChunks.collect { chunk ->
                channel.writeFully(chunk, 0, chunk.size)
                channel.flush()
            }
        } catch (_: Exception) {
            // Connection closed by client
        } finally {
            val remaining = activeListeners.decrementAndGet()
            if (remaining <= 0) {
                activeListeners.set(0)
                stopRecording()
            }
        }
    }

    private fun createWavHeader(sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val header = ByteArray(44)

        // RIFF header
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        // File size (streaming placeholder: 0x7FFFFFFF - 8)
        val riffSize = 0x7ffffff7
        header[4] = (riffSize and 0xff).toByte()
        header[5] = ((riffSize shr 8) and 0xff).toByte()
        header[6] = ((riffSize shr 16) and 0xff).toByte()
        header[7] = ((riffSize shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()

        // fmt subchunk
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // Subchunk1Size
        header[20] = 1; header[21] = 0 // AudioFormat = 1 (PCM)
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = blockAlign.toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0

        // data subchunk
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        val dataSize = 0x7ffffff7 - 36
        header[40] = (dataSize and 0xff).toByte()
        header[41] = ((dataSize shr 8) and 0xff).toByte()
        header[42] = ((dataSize shr 16) and 0xff).toByte()
        header[43] = ((dataSize shr 24) and 0xff).toByte()

        return header
    }
}
