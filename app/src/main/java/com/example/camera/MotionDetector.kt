package com.example.camera

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Detects motion by sampling a 64x48 grid of brightness values straight from the frame's
 * Y (luma) plane and comparing it with the previous sample. No Bitmaps are created.
 *
 * All settings can change at runtime (from the camera screen or remotely via /config/motion).
 */
class MotionDetector(
    /** Fraction of grid points that must change to count as motion (0.02 = 2%). */
    @Volatile var threshold: Float = 0.02f,
    @Volatile var enabled: Boolean = true,
    @Volatile var cooldownMs: Long = 30_000L,
    private val onMotionDetected: ((File) -> Unit)? = null
) {
    companion object {
        private const val GRID_W = 64
        private const val GRID_H = 48
        private const val PIXEL_DELTA = 25
        private const val MAX_SNAPSHOT_FILES = 200
    }

    private var prevGrey: IntArray? = null
    private var frameCounter = 0
    private var lastEventMs = 0L

    fun process(frame: FrameEncoder.EncodedFrame, context: Context?) {
        if (!enabled) {
            prevGrey = null // start fresh when re-enabled
            return
        }
        // Check every 5th encoded frame to save CPU
        if (++frameCounter % 5 != 0) return

        val grey = sampleLuma(frame)
        val old = prevGrey
        prevGrey = grey
        if (old == null) return

        var changed = 0
        for (i in grey.indices) {
            if (abs(grey[i] - old[i]) > PIXEL_DELTA) changed++
        }
        val changeRatio = changed.toFloat() / grey.size
        val now = SystemClock.elapsedRealtime()

        if (changeRatio > threshold && now - lastEventMs > cooldownMs) {
            lastEventMs = now
            val timestamp = System.currentTimeMillis()
            FrameBus.lastMotionTimestamp.value = timestamp
            context?.let { saveSnapshot(it, frame.jpeg, timestamp) }
        }
    }

    /** Reads a GRID_W x GRID_H grid of Y values; Y is the first width*height bytes of NV21. */
    private fun sampleLuma(frame: FrameEncoder.EncodedFrame): IntArray {
        val out = IntArray(GRID_W * GRID_H)
        var i = 0
        for (gy in 0 until GRID_H) {
            val y = (gy * frame.height) / GRID_H
            val rowStart = y * frame.width
            for (gx in 0 until GRID_W) {
                val x = (gx * frame.width) / GRID_W
                out[i++] = frame.nv21[rowStart + x].toInt() and 0xFF
            }
        }
        return out
    }

    private fun saveSnapshot(context: Context, jpeg: ByteArray, timestamp: Long) {
        try {
            val dir = File(context.filesDir, "motion_snapshots").apply { mkdirs() }
            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
            val file = File(dir, "motion_$name.jpg")
            file.writeBytes(jpeg)

            val record = MotionRecord(
                id = UUID.randomUUID().toString(),
                timestamp = timestamp,
                filePath = file.absolutePath,
                fileSize = file.length()
            )
            FrameBus.recentMotionSnapshots.value =
                (listOf(record) + FrameBus.recentMotionSnapshots.value).take(50)

            pruneOldSnapshots(dir)
            onMotionDetected?.invoke(file)
        } catch (_: Exception) {
            // Ignore storage errors
        }
    }

    /** Keeps only the newest MAX_SNAPSHOT_FILES snapshots so storage cannot fill up. */
    private fun pruneOldSnapshots(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: return
        if (files.size <= MAX_SNAPSHOT_FILES) return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_SNAPSHOT_FILES)
            .forEach { it.delete() }
    }
}
