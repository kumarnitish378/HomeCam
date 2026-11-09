package com.example.camera

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

data class MotionRecord(
    val id: String,
    val timestamp: Long,
    val filePath: String,
    val fileSize: Long
)

/**
 * Global bus holding the latest encoded JPEG frame and streaming telemetry.
 * Each connected client consumes frames with conflate() on its own coroutine.
 */
object FrameBus {
    /** Latest encoded JPEG frame */
    val latest = MutableStateFlow<ByteArray?>(null)

    /** Number of active streaming connections */
    val activeViewers = MutableStateFlow(0)

    /** Current frames per second measured at encoder */
    val currentFps = MutableStateFlow(0)

    /** Service streaming active state */
    val isStreaming = MutableStateFlow(false)

    /** Whether motion detection is currently on */
    val motionEnabled = MutableStateFlow(true)

    /** Timestamp of last detected motion event */
    val lastMotionTimestamp = MutableStateFlow(0L)

    /** Battery level in percentage */
    val batteryPercent = MutableStateFlow(100)

    /** Battery temperature in degrees Celsius */
    val batteryTemp = MutableStateFlow(25.0f)

    /** Whether phone is on charger */
    val isCharging = MutableStateFlow(false)

    /** True if camera dropped resolution & fps due to temperature > 42°C */
    val isHeatThrottled = MutableStateFlow(false)

    /** Current torch state requested */
    val torchEnabled = MutableStateFlow(false)

    /** Current camera facing: "BACK" or "FRONT" */
    val lensFacing = MutableStateFlow("BACK")

    /** Control command channel: "TOGGLE_TORCH", "SWITCH_LENS", etc. */
    val controlCommands = MutableSharedFlow<String>(extraBufferCapacity = 10)

    /** Recent motion snapshots */
    val recentMotionSnapshots = MutableStateFlow<List<MotionRecord>>(emptyList())

    /** Internet (IPv6 + HTTPS) access state, published by the service */
    val remoteEnabled = MutableStateFlow(false)
    val remoteHost = MutableStateFlow<String?>(null)   // DuckDNS name or current global IPv6
    val remotePort = MutableStateFlow(8443)
    val certFingerprint = MutableStateFlow<String?>(null)
    val remoteError = MutableStateFlow<String?>(null)

    /** Loop recording state */
    val recordingActive = MutableStateFlow(false)
    val recordingUsedBytes = MutableStateFlow(0L)
    val currentRecordingFile = MutableStateFlow<String?>(null)
    val recordingError = MutableStateFlow<String?>(null)

    /**
     * On-demand streaming: JPEG frames are only produced while a viewer is connected, or until
     * this time (SystemClock.elapsedRealtime) when someone asked for a single snapshot.
     */
    val encodeUntil = MutableStateFlow(0L)
    /** elapsedRealtime of the frame in [latest] */
    val latestAt = MutableStateFlow(0L)

    /** Last camera error message if any */
    val cameraError = MutableStateFlow<String?>(null)
}
