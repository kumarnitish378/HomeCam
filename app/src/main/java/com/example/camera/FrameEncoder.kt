package com.example.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * Encodes CameraX YUV_420_888 frames straight to JPEG.
 *
 * The old path (RGBA -> Bitmap -> rotated Bitmap copy -> Bitmap.compress) allocated two
 * full-size bitmaps per frame and ran mostly in Java, which limited weak phones to ~3 fps.
 * This path copies the frame once into an NV21 buffer (reused between frames) and hands it to
 * YuvImage.compressToJpeg, which is native. Rotation is done by CameraX itself
 * (ImageAnalysis.setOutputImageRotationEnabled), so frames arrive upright.
 *
 * Caps the frame rate at maxFps to limit CPU use and heat.
 */
class FrameEncoder(
    var quality: Int = 70,
    var maxFps: Int = 12
) {
    /** One encoded frame plus the data motion detection needs from it. */
    class EncodedFrame(
        val jpeg: ByteArray,
        val nv21: ByteArray,
        val width: Int,
        val height: Int
    )

    private var lastMs = 0L
    private var frameCountInWindow = 0
    private var fpsWindowStartMs = 0L

    private var nv21: ByteArray = ByteArray(0)
    private val jpegOut = ByteArrayOutputStream(256 * 1024)

    /**
     * Returns the encoded frame, or null when this frame is skipped by the fps cap.
     * The returned [EncodedFrame.nv21] buffer is reused on the next call; read it before then.
     */
    fun encode(image: ImageProxy): EncodedFrame? {
        val minGapMs = if (maxFps > 0) 1000L / maxFps else 50L
        val now = SystemClock.elapsedRealtime()
        if (now - lastMs < minGapMs) return null
        lastMs = now

        val width = image.width
        val height = image.height
        val size = width * height * 3 / 2
        if (nv21.size != size) nv21 = ByteArray(size)
        yuv420ToNv21(image, nv21)

        jpegOut.reset()
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), quality.coerceIn(10, 100), jpegOut)

        trackFps(now)
        return EncodedFrame(jpegOut.toByteArray(), nv21, width, height)
    }

    private fun trackFps(now: Long) {
        frameCountInWindow++
        if (fpsWindowStartMs == 0L) {
            fpsWindowStartMs = now
        } else if (now - fpsWindowStartMs >= 1000L) {
            val seconds = (now - fpsWindowStartMs) / 1000f
            FrameBus.currentFps.value = Math.round(frameCountInWindow / seconds)
            frameCountInWindow = 0
            fpsWindowStartMs = now
        }
    }

    /** Copies a YUV_420_888 ImageProxy into [out] as NV21 (Y plane, then interleaved V/U). */
    private fun yuv420ToNv21(image: ImageProxy, out: ByteArray) {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // Y plane: copy row by row when rows are padded, in one go otherwise.
        val yBuffer = yPlane.buffer.duplicate()
        val yRowStride = yPlane.rowStride
        if (yRowStride == width) {
            yBuffer.position(0)
            yBuffer.get(out, 0, width * height)
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(out, row * width, width)
            }
        }

        // Chroma: interleave V and U at quarter resolution.
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        var pos = width * height
        for (row in 0 until chromaHeight) {
            val rowStart = row * uvRowStride
            for (col in 0 until chromaWidth) {
                val idx = rowStart + col * uvPixelStride
                out[pos++] = vBuffer.get(idx)
                out[pos++] = uBuffer.get(idx)
            }
        }
    }
}
