package com.example.camera

import android.content.Context
import com.example.core.AuthToken
import com.example.core.NetUtils
import com.example.core.SettingsRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Embedded Ktor HTTP server serving the live MJPEG stream, snapshots, and camera telemetry.
 * Listens strictly on IPv4 (0.0.0.0) with mandatory token authentication.
 */
class MjpegServer(
    private val context: Context,
    private val settingsRepo: SettingsRepository?,
    private val port: Int,
    private val token: String,
    private val cameraName: String,
    private val tailscaleOnly: Boolean = false
) {
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return

        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                // Live MJPEG streaming endpoint
                get("/stream.mjpg") {
                    if (!authorized(call)) {
                        call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                        return@get
                    }

                    FrameBus.activeViewers.value++
                    try {
                        val multipartType = ContentType("multipart", "x-mixed-replace")
                            .withParameter("boundary", "frame")

                        call.respondBytesWriter(contentType = multipartType) {
                            try {
                                FrameBus.latest.filterNotNull().conflate().collect { jpeg ->
                                    val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
                                    writeStringUtf8(header)
                                    writeFully(jpeg, 0, jpeg.size)
                                    writeStringUtf8("\r\n")
                                    flush()
                                }
                            } catch (_: Exception) {
                                // Connection closed by client
                            }
                        }
                    } finally {
                        FrameBus.activeViewers.value = (FrameBus.activeViewers.value - 1).coerceAtLeast(0)
                    }
                }

                // Live Audio Streaming endpoint (16kHz 16-bit PCM WAV)
                get("/audio.wav") {
                    if (!authorized(call)) {
                        call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                        return@get
                    }

                    // Say why instead of streaming endless silence
                    AudioStreamer.problem(context)?.let { reason ->
                        call.respondText(reason, ContentType.Text.Plain, HttpStatusCode.ServiceUnavailable)
                        return@get
                    }

                    call.respondBytesWriter(contentType = ContentType("audio", "wav")) {
                        AudioStreamer.streamTo(this, context)
                    }
                }

                // Single snapshot endpoint
                get("/snapshot.jpg") {
                    if (!authorized(call)) {
                        call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                        return@get
                    }

                    // Frames are only encoded on demand: ask for a fresh one and wait up to 3 s.
                    val asked = android.os.SystemClock.elapsedRealtime()
                    FrameBus.encodeUntil.value = asked + 3000
                    var waited = 0
                    while (FrameBus.latestAt.value < asked && waited < 3000) {
                        delay(50)
                        waited += 50
                    }
                    val jpeg = FrameBus.latest.value
                    if (jpeg == null || FrameBus.latestAt.value < asked) {
                        call.respond(HttpStatusCode.ServiceUnavailable, "No frame available")
                    } else {
                        call.respondBytes(jpeg, ContentType.Image.JPEG)
                    }
                }

                // Camera status JSON endpoint
                get("/status") {
                    if (!authorized(call)) {
                        call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                        return@get
                    }

                    val statusJson = buildString {
                        append("{")
                        append("\"name\":\"${escapeJson(cameraName)}\",")
                        append("\"fps\":${FrameBus.currentFps.value},")
                        append("\"viewers\":${FrameBus.activeViewers.value},")
                        append("\"lastMotion\":${FrameBus.lastMotionTimestamp.value},")
                        append("\"battery\":${FrameBus.batteryPercent.value},")
                        append("\"temperature\":${FrameBus.batteryTemp.value},")
                        append("\"isCharging\":${FrameBus.isCharging.value},")
                        append("\"isHeatThrottled\":${FrameBus.isHeatThrottled.value},")
                        append("\"torchOn\":${FrameBus.torchEnabled.value},")
                        append("\"motionEnabled\":${FrameBus.motionEnabled.value},")
                        append("\"audioProblem\":\"${escapeJson(AudioStreamer.problem(context) ?: FrameBus.audioError.value.orEmpty())}\",")
                        append("\"recording\":${FrameBus.recordingActive.value},")
                        append("\"recordingUsedBytes\":${FrameBus.recordingUsedBytes.value},")
                        append("\"recordingError\":\"${escapeJson(FrameBus.recordingError.value.orEmpty())}\",")
                        append("\"remoteEnabled\":${FrameBus.remoteEnabled.value},")
                        append("\"remoteHost\":\"${escapeJson(FrameBus.remoteHost.value.orEmpty())}\",")
                        append("\"remotePort\":${FrameBus.remotePort.value},")
                        append("\"certFp\":\"${FrameBus.certFingerprint.value.orEmpty()}\",")
                        append("\"lanIp\":\"${NetUtils.findLanIp().orEmpty()}\",")
                        append("\"tailscaleIp\":\"${NetUtils.findTailscaleIp().orEmpty()}\",")
                        append("\"lensFacing\":\"${FrameBus.lensFacing.value}\"")
                        append("}")
                    }
                    call.respondText(statusJson, ContentType.Application.Json)
                }

                // Control: Torch
                post("/control/torch") {
                    if (!authorized(call)) {
                        call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                        return@post
                    }

                    val onParam = call.request.queryParameters["on"]
                    val enableTorch = onParam == "1" || onParam.equals("true", ignoreCase = true)
                    FrameBus.torchEnabled.value = enableTorch
                    call.respond(HttpStatusCode.NoContent)
                }

                // Control: Switch Lens
                post("/control/lens") {
                    if (!authorized(call)) {
                        call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                        return@post
                    }

                    val facingParam = call.request.queryParameters["facing"]?.uppercase() ?: "BACK"
                    FrameBus.controlCommands.tryEmit("LENS_$facingParam")
                    call.respond(HttpStatusCode.NoContent)
                }

                // Motion detection settings: read
                get("/config/motion") {
                    if (!authorized(call)) {
                        call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                        return@get
                    }
                    val repo = settingsRepo
                    if (repo == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable, "Settings unavailable")
                        return@get
                    }
                    call.respondText(motionConfigJson(repo), ContentType.Application.Json)
                }

                // Motion detection settings: change any of enabled / sensitivity / cooldown.
                // Example: POST /config/motion?enabled=1&sensitivity=0.03&cooldown=60
                // The service observes the settings store, so changes apply within a frame.
                post("/config/motion") {
                    if (!authorized(call)) {
                        call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                        return@post
                    }
                    val repo = settingsRepo
                    if (repo == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable, "Settings unavailable")
                        return@post
                    }
                    val q = call.request.queryParameters
                    val enabled = q["enabled"]?.let { it == "1" || it.equals("true", ignoreCase = true) }
                    val sensitivity = q["sensitivity"]?.toFloatOrNull()
                    val cooldown = q["cooldown"]?.toIntOrNull()
                    if ((q["sensitivity"] != null && (sensitivity == null || sensitivity !in 0.005f..0.20f)) ||
                        (q["cooldown"] != null && (cooldown == null || cooldown !in 5..600))
                    ) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            "sensitivity must be 0.005-0.20, cooldown must be 5-600 seconds"
                        )
                        return@post
                    }
                    repo.updateCameraConfig(
                        motionEnabled = enabled,
                        motionSensitivity = sensitivity,
                        motionCooldownSec = cooldown
                    )
                    call.respondText(motionConfigJson(repo), ContentType.Application.Json)
                }

                // Loop recording settings: read
                get("/config/recording") {
                    if (!authorized(call)) {
                        call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                        return@get
                    }
                    val repo = settingsRepo
                    if (repo == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable, "Settings unavailable")
                        return@get
                    }
                    call.respondText(recordingConfigJson(repo), ContentType.Application.Json)
                }

                // Loop recording settings: change any of enabled / limitMb / quality.
                // Example: POST /config/recording?enabled=1&limitMb=2048&quality=MEDIUM
                post("/config/recording") {
                    if (!authorized(call)) {
                        call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                        return@post
                    }
                    val repo = settingsRepo
                    if (repo == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable, "Settings unavailable")
                        return@post
                    }
                    val q = call.request.queryParameters
                    val enabled = q["enabled"]?.let { it == "1" || it.equals("true", ignoreCase = true) }
                    val limitMb = q["limitMb"]?.toIntOrNull()
                    val quality = q["quality"]?.uppercase()
                    if ((q["limitMb"] != null && (limitMb == null || limitMb !in 256..65536)) ||
                        (quality != null && quality !in setOf("LOW", "MEDIUM", "HIGH"))
                    ) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            "limitMb must be 256-65536, quality must be LOW, MEDIUM or HIGH"
                        )
                        return@post
                    }
                    repo.updateCameraConfig(
                        recordingEnabled = enabled,
                        recordingLimitMb = limitMb,
                        recordingQuality = quality
                    )
                    call.respondText(recordingConfigJson(repo), ContentType.Application.Json)
                }

                // Recorded segments, newest first. The one still being written is marked.
                get("/recordings") {
                    if (!authorized(call)) {
                        call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                        return@get
                    }
                    val current = FrameBus.currentRecordingFile.value
                    val json = buildString {
                        append("[")
                        RollingRecorder.listSegments(context).asReversed().forEachIndexed { i, f ->
                            if (i > 0) append(",")
                            append("{\"name\":\"${f.name}\",\"size\":${f.length()},")
                            append("\"inProgress\":${f.name == current}}")
                        }
                        append("]")
                    }
                    call.respondText(json, ContentType.Application.Json)
                }

                // Download one recorded segment (MP4)
                get("/recordings/{name}") {
                    if (!authorized(call)) {
                        call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                        return@get
                    }
                    val name = call.parameters["name"].orEmpty()
                    if (!RollingRecorder.isRecordingName(name)) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid name")
                        return@get
                    }
                    val file = java.io.File(RollingRecorder.directory(context), name)
                    if (!file.isFile) {
                        call.respond(HttpStatusCode.NotFound, "Not found")
                        return@get
                    }
                    call.respondFile(file)
                }

                // Motion snapshots listing
                get("/motion") {
                    if (!authorized(call)) {
                        call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                        return@get
                    }

                    val snapshots = FrameBus.recentMotionSnapshots.value
                    val json = buildString {
                        append("[")
                        snapshots.forEachIndexed { index, record ->
                            if (index > 0) append(",")
                            val file = File(record.filePath)
                            append("{")
                            append("\"id\":\"${record.id}\",")
                            append("\"timestamp\":${record.timestamp},")
                            append("\"fileName\":\"${file.name}\",")
                            append("\"fileSize\":${record.fileSize}")
                            append("}")
                        }
                        append("]")
                    }
                    call.respondText(json, ContentType.Application.Json)
                }

                // Retrieve specific motion snapshot
                get("/motion/{filename}") {
                    if (!authorized(call)) {
                        call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                        return@get
                    }

                    val filename = call.parameters["filename"]
                    if (filename.isNullOrBlank() || filename.contains("..") || filename.contains("/")) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid filename")
                        return@get
                    }

                    val file = File(File(context.filesDir, "motion_snapshots"), filename)
                    if (file.exists() && file.isFile) {
                        call.respondBytes(file.readBytes(), ContentType.Image.JPEG)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "File not found")
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        try {
            server?.stop(500, 1000)
            server = null
        } catch (_: Exception) {
        }
    }

    private fun authorized(call: ApplicationCall): Boolean {
        val remoteAddress = try {
            call.request.origin.remoteAddress
        } catch (_: Exception) {
            "127.0.0.1"
        }

        // 1. IP allow-list check
        if (!NetUtils.isAllowedSource(remoteAddress, tailscaleOnly)) {
            return false
        }

        // 2. Brute-force rate limiting
        if (AuthToken.isIpBlocked(remoteAddress)) {
            return false
        }

        // 3. Extract token from header or URL parameter
        val header = call.request.headers["Authorization"]
        val tokenFromHeader = header?.removePrefix("Bearer ")?.trim()
        val tokenFromQuery = call.request.queryParameters["t"]?.trim()
        val given = tokenFromHeader ?: tokenFromQuery

        val isValid = AuthToken.verifyToken(given, token)
        if (isValid) {
            AuthToken.recordAuthSuccess(remoteAddress)
            return true
        } else {
            AuthToken.recordAuthFailure(remoteAddress)
            return false
        }
    }

    private suspend fun motionConfigJson(repo: SettingsRepository): String {
        val c = repo.cameraConfig.first()
        return "{\"enabled\":${c.motionEnabled},\"sensitivity\":${c.motionSensitivity}," +
            "\"cooldownSec\":${c.motionCooldownSec}}"
    }

    private suspend fun recordingConfigJson(repo: SettingsRepository): String {
        val c = repo.cameraConfig.first()
        return "{\"enabled\":${c.recordingEnabled},\"limitMb\":${c.recordingLimitMb}," +
            "\"quality\":\"${c.recordingQuality}\",\"usedBytes\":${FrameBus.recordingUsedBytes.value}," +
            "\"recording\":${FrameBus.recordingActive.value}}"
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
