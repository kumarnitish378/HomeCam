package com.example.camera

import android.content.Context
import com.example.core.AuthToken
import com.example.core.NetUtils
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
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import java.io.File

/**
 * Embedded Ktor HTTP server serving the live MJPEG stream, snapshots, and camera telemetry.
 * Listens strictly on IPv4 (0.0.0.0) with mandatory token authentication.
 */
class MjpegServer(
    private val context: Context,
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

                // Single snapshot endpoint
                get("/snapshot.jpg") {
                    if (!authorized(call)) {
                        call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                        return@get
                    }

                    val jpeg = FrameBus.latest.value
                    if (jpeg == null) {
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

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
