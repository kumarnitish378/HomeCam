package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Detects motion between frames by downscaling to 64x48 grayscale and comparing pixel changes.
 * Raises motion events if changes exceed threshold with a 30s cooldown.
 */
class MotionDetector(
    var threshold: Float = 0.02f,
    private val onMotionDetected: ((Bitmap, File) -> Unit)? = null
) {
    private var prevGrey: IntArray? = null
    private var frameCounter = 0
    private var lastEventMs = 0L

    fun process(frame: Bitmap, context: Context?) {
        // Process every 5th frame to minimize CPU usage
        if (++frameCounter % 5 != 0) return

        val small = Bitmap.createScaledBitmap(frame, 64, 48, true)
        val pixels = IntArray(64 * 48)
        small.getPixels(pixels, 0, 64, 0, 0, 64, 48)

        // Convert RGB to standard luminance grayscale: 0.30 R + 0.59 G + 0.11 B
        val grey = IntArray(pixels.size) { i ->
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            (r * 30 + g * 59 + b * 11) / 100
        }

        val old = prevGrey
        prevGrey = grey
        if (old == null) return

        val changed = grey.indices.count { idx ->
            abs(grey[idx] - old[idx]) > 25
        }

        val changeRatio = changed.toFloat() / grey.size
        val now = SystemClock.elapsedRealtime()

        if (changeRatio > threshold && (now - lastEventMs > 30_000L)) {
            lastEventMs = now
            val timestamp = System.currentTimeMillis()
            FrameBus.lastMotionTimestamp.value = timestamp

            context?.let { ctx ->
                try {
                    val snapshotDir = File(ctx.filesDir, "motion_snapshots").apply { mkdirs() }
                    val timeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
                    val file = File(snapshotDir, "motion_$timeFormat.jpg")
                    FileOutputStream(file).use { out ->
                        frame.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }

                    val record = MotionRecord(
                        id = UUID.randomUUID().toString(),
                        timestamp = timestamp,
                        filePath = file.absolutePath,
                        fileSize = file.length()
                    )

                    val updatedList = (listOf(record) + FrameBus.recentMotionSnapshots.value).take(50)
                    FrameBus.recentMotionSnapshots.value = updatedList

                    onMotionDetected?.invoke(frame, file)
                } catch (_: Exception) {
                    // Ignore storage errors
                }
            }
        }
    }
}
