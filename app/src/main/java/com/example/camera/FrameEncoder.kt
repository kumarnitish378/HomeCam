package com.example.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * Converts CameraX ImageProxy frames into upright Bitmaps and compresses them into JPEG bytes.
 * Caps frame rate according to maxFps to reduce CPU usage and heat.
 */
class FrameEncoder(
    var quality: Int = 70,
    var maxFps: Int = 12
) {
    private var lastMs = 0L
    private var frameCountInWindow = 0
    private var fpsWindowStartMs = 0L

    fun toUprightBitmap(image: ImageProxy): Bitmap? {
        val minGapMs = if (maxFps > 0) 1000L / maxFps else 50L
        val now = SystemClock.elapsedRealtime()
        if (now - lastMs < minGapMs) {
            return null
        }
        lastMs = now

        // Track FPS
        frameCountInWindow++
        if (fpsWindowStartMs == 0L) {
            fpsWindowStartMs = now
        } else if (now - fpsWindowStartMs >= 1000L) {
            val seconds = (now - fpsWindowStartMs) / 1000f
            FrameBus.currentFps.value = (frameCountInWindow / seconds).toInt()
            frameCountInWindow = 0
            fpsWindowStartMs = now
        }

        val src = image.toBitmap()
        val deg = image.imageInfo.rotationDegrees
        if (deg == 0) return src

        val matrix = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    fun toJpeg(bmp: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), stream)
        return stream.toByteArray()
    }
}
