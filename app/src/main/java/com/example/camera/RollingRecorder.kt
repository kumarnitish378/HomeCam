package com.example.camera

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Continuous "CCTV loop" recording.
 *
 * Records H.264 video (hardware encoder, no audio) into MP4 segments of [SEGMENT_MS] each.
 * Before every new segment it deletes the oldest segments so the folder stays under
 * [limitBytes] and the phone keeps at least [MIN_FREE_BYTES] free.
 *
 * The camera owner binds [createUseCase]'s VideoCapture together with ImageAnalysis, then calls
 * [attach]; before unbinding (lens switch, heat throttling) it calls [detach].
 */
class RollingRecorder(private val context: Context) {

    enum class Level(val bitrate: Int, val quality: Quality) {
        LOW(600_000, Quality.SD),        // ~480p, ~0.27 GB/hour
        MEDIUM(1_200_000, Quality.HD),   // 720p,  ~0.54 GB/hour
        HIGH(2_500_000, Quality.HD);     // 720p,  ~1.1 GB/hour

        /** Approximate hours of footage that fit in [bytes]. */
        fun hoursFor(bytes: Long): Float = bytes * 8f / bitrate / 3600f

        companion object {
            fun from(name: String): Level = entries.firstOrNull { it.name.equals(name, true) } ?: MEDIUM
        }
    }

    companion object {
        private const val TAG = "RollingRecorder"
        const val SEGMENT_MS = 5 * 60 * 1000L
        private const val MIN_FREE_BYTES = 500L * 1024 * 1024
        private val NAME_REGEX = Regex("""rec_\d{8}_\d{6}\.mp4""")

        fun isRecordingName(name: String) = NAME_REGEX.matches(name)

        fun directory(context: Context): File =
            File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "HomeCam")
                .apply { mkdirs() }

        /** Finished and in-progress segments, oldest first (names sort by time). */
        fun listSegments(context: Context): List<File> =
            directory(context).listFiles { f -> f.isFile && isRecordingName(f.name) }
                ?.sortedBy { it.name } ?: emptyList()
    }

    @Volatile var limitBytes: Long = 2L * 1024 * 1024 * 1024
    @Volatile var level: Level = Level.MEDIUM

    private val mainHandler = Handler(Looper.getMainLooper())
    private var videoCapture: VideoCapture<Recorder>? = null
    private var active: Recording? = null
    /** Bumped on every attach/detach so callbacks from an old binding are ignored. */
    private var generation = 0

    /** A VideoCapture use case for the current [level]; bind it, then call [attach]. */
    fun createUseCase(): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(level.quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
            .setTargetVideoEncodingBitRate(level.bitrate)
            .build()
        return VideoCapture.withOutput(recorder)
    }

    /** Starts the recording loop on a bound use case. Main thread. */
    fun attach(capture: VideoCapture<Recorder>) {
        generation++
        videoCapture = capture
        startSegment(generation)
    }

    /** Stops recording (finishes the current segment). Main thread. */
    fun detach() {
        generation++
        active?.stop()
        active = null
        videoCapture = null
        FrameBus.recordingActive.value = false
        FrameBus.currentRecordingFile.value = null
    }

    private fun startSegment(gen: Int) {
        if (gen != generation) return
        val capture = videoCapture ?: return
        prune()
        val name = "rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val file = File(directory(context), name)
        val options = FileOutputOptions.Builder(file)
            .setDurationLimitMillis(SEGMENT_MS)
            .build()
        try {
            active = capture.output
                .prepareRecording(context, options)
                .start(ContextCompat.getMainExecutor(context)) { event -> onEvent(gen, file, event) }
            FrameBus.recordingActive.value = true
            FrameBus.currentRecordingFile.value = name
            FrameBus.recordingError.value = null
        } catch (e: Exception) {
            Log.w(TAG, "start failed", e)
            FrameBus.recordingError.value = "Recording failed to start: ${e.message}"
            FrameBus.recordingActive.value = false
            mainHandler.postDelayed({ startSegment(gen) }, 10_000)
        }
    }

    private fun onEvent(gen: Int, file: File, event: VideoRecordEvent) {
        if (event !is VideoRecordEvent.Finalize) return
        val error = event.error
        val normalEnd = error == VideoRecordEvent.Finalize.ERROR_NONE ||
            error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
            error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
        if (error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) file.delete()
        if (!normalEnd && error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
            FrameBus.recordingError.value = "Recording error $error: ${event.cause?.message ?: "unknown"}"
        }
        updateUsage()
        if (gen != generation) return // we were detached; don't continue this loop
        // Next segment right away after a normal end; back off after an error.
        val wait = if (normalEnd) 0L else 5_000L
        mainHandler.postDelayed({ startSegment(gen) }, wait)
    }

    /** Deletes oldest segments to stay under the limit (with room for the next one). */
    private fun prune() {
        val dir = directory(context)
        val segments = listSegments(context).toMutableList()
        val nextSegmentBytes = level.bitrate / 8L * (SEGMENT_MS / 1000)
        var total = segments.sumOf { it.length() }
        while (segments.isNotEmpty() &&
            (total + nextSegmentBytes > limitBytes || dir.usableSpace < MIN_FREE_BYTES + nextSegmentBytes)
        ) {
            val oldest = segments.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
        if (dir.usableSpace < MIN_FREE_BYTES) {
            FrameBus.recordingError.value = "Phone storage almost full; recording may stop."
        }
        updateUsage()
    }

    private fun updateUsage() {
        FrameBus.recordingUsedBytes.value = listSegments(context).sumOf { it.length() }
    }
}
