package com.example.viewer

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.components.CamState
import com.example.ui.components.InfoChip
import com.example.ui.components.RecordingTag
import com.example.ui.components.Section
import com.example.ui.components.StatusPill
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.InkNavy
import com.example.ui.theme.LiveTeal
import com.example.ui.theme.Mist
import com.example.ui.theme.Slate
import com.example.ui.theme.TabularNumbers
import com.example.ui.theme.VideoBlack
import com.example.ui.theme.VideoScrim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Live view of one camera: full-bleed video with overlays that hide on tap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    camera: CameraItem,
    repository: CameraRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var latestFrame by remember { mutableStateOf<Bitmap?>(null) }
    var isConnecting by remember { mutableStateOf(true) }
    var isDisconnected by remember { mutableStateOf(false) }
    var receivedFps by remember { mutableIntStateOf(0) }
    var isFullscreen by remember { mutableStateOf(false) }

    // Camera hardware status fetched from /status
    var cameraStatus by remember { mutableStateOf(CameraStatus()) }
    var isTorchOn by remember { mutableStateOf(false) }
    var currentLensFacing by remember { mutableStateOf("BACK") }

    // Zoom & Pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    var showInfoSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    // Bumped by "Try again" to reconnect right away
    var retryNonce by remember { mutableIntStateOf(0) }
    var routeFailures by remember { mutableStateOf<List<CameraRepository.RouteFailure>>(emptyList()) }
    // Overlays hide in fullscreen; a tap on the video shows them again
    var overlaysVisible by remember { mutableStateOf(true) }
    var showRecordingsSheet by remember { mutableStateOf(false) }

    BackHandler {
        if (!overlaysVisible) overlaysVisible = true else onBack()
    }

    // How we currently reach the camera (Home Wi-Fi, Tailscale or Internet IPv6)
    var activeRoute by remember { mutableStateOf<Route?>(null) }
    var activeBaseUrl by remember { mutableStateOf(camera.baseUrl) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var isAudioEnabled by remember { mutableStateOf(false) }

    // Live audio stream player (active only when user enables audio)
    // Reconnects after a dropped connection; stops and explains when the camera refuses.
    LaunchedEffect(isAudioEnabled, camera.id) {
        while (isAudioEnabled) {
            val endpoint = repository.resolveEndpoint(camera)
            if (endpoint == null) {
                delay(2000)
                continue
            }
            val problem = AudioPlayer(endpoint.streamClient())
                .play("${endpoint.baseUrl}/audio.wav", camera.token)
            if (problem != null) {
                Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
                isAudioEnabled = false
                break
            }
            delay(1000)
        }
    }

    // Stream frames using MjpegClient. Runs only while screen is active!
    // Each attempt picks the first route that answers (LAN/Tailscale first, then internet),
    // so walking out of the house switches to IPv6 on the next reconnect.
    LaunchedEffect(camera.id, retryNonce) {
        isConnecting = true
        isDisconnected = false
        while (true) {
            val endpoint = repository.resolveEndpoint(camera)
            connectError = if (endpoint == null) repository.lastError(camera) else null
            routeFailures = if (endpoint == null) repository.lastFailures(camera) else emptyList()
            if (endpoint != null) {
                activeRoute = endpoint.route
                activeBaseUrl = endpoint.baseUrl
                try {
                    MjpegClient(endpoint.streamClient())
                        .streamFrames("${endpoint.baseUrl}/stream.mjpg", camera.token) { fps ->
                            receivedFps = fps
                        }
                        .collect { frame ->
                            latestFrame = frame
                            isConnecting = false
                            isDisconnected = false
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // stream dropped; fall through and reconnect
                }
            }
            isDisconnected = true
            delay(2000) // Retry every 2s on disconnect
        }
    }

    // Periodically poll /status for battery, temp, and motion
    LaunchedEffect(camera.id) {
        while (true) {
            val status = repository.fetchStatus(camera)
            cameraStatus = status
            if (status.isOnline) {
                isTorchOn = status.torchOn
                currentLensFacing = status.lensFacing
            }
            delay(5000)
        }
    }

    val live = latestFrame != null && !isDisconnected
    val unreachable = isDisconnected && routeFailures.isNotEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VideoBlack)
    ) {
        // Video, pinch to zoom; a tap toggles the overlays
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { overlaysVisible = !overlaysVisible })
                },
            contentAlignment = Alignment.Center
        ) {
            latestFrame?.let { frame ->
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = "Live video from ${camera.name}",
                    contentScale = ContentScale.Fit,
                    alpha = if (live) 1f else 0.35f,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            }
        }

        // Middle of the screen: connecting / reconnecting / can't connect
        when {
            unreachable -> CantConnectCard(
                cameraName = camera.name,
                failures = routeFailures,
                onTryAgain = { retryNonce++ },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(20.dp)
            )
            latestFrame == null || isDisconnected -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    if (latestFrame == null) "Connecting to ${camera.name}..." else "Reconnecting...",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                // Reachable but no picture: say what the camera phone reports
                cameraStatus.cameraError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Camera phone: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Top overlay
        AnimatedVisibility(
            visible = overlaysVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VideoScrim)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            camera.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(
                                state = when {
                                    live -> CamState.LIVE
                                    unreachable -> CamState.OFF
                                    else -> CamState.CHECKING
                                },
                                text = when {
                                    live -> "Live"
                                    unreachable -> "Offline"
                                    else -> "Connecting"
                                }
                            )
                            if (live) InfoChip("$receivedFps fps", dark = true)
                            activeRoute?.let { r ->
                                InfoChip(
                                    r.label,
                                    icon = when (r) {
                                        Route.TAILSCALE -> Icons.Outlined.VpnLock
                                        Route.INTERNET -> Icons.Outlined.Public
                                        Route.LAN -> Icons.Outlined.Wifi
                                    },
                                    dark = true
                                )
                            }
                        }
                    }
                    IconButton(onClick = { showRecordingsSheet = true }) {
                        Icon(Icons.Outlined.VideoLibrary, contentDescription = "Recordings", tint = Color.White)
                    }
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Outlined.Tune, contentDescription = "Camera settings", tint = Color.White)
                    }
                    IconButton(onClick = { showInfoSheet = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = "Details", tint = Color.White)
                    }
                }
                // Facts from the camera phone
                if (cameraStatus.isOnline) {
                    Row(
                        modifier = Modifier.padding(start = 48.dp, top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (cameraStatus.recording) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.Black.copy(alpha = 0.45f))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) { RecordingTag() }
                        }
                        if (cameraStatus.lastMotion > 0) {
                            val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(cameraStatus.lastMotion))
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.Black.copy(alpha = 0.45f))
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(AlertAmber)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Motion $t", style = MaterialTheme.typography.labelMedium.merge(TabularNumbers), color = Color.White)
                            }
                        }
                        if (cameraStatus.battery > 0) {
                            InfoChip(
                                "${cameraStatus.battery}%" + if (cameraStatus.isCharging) " charging" else "",
                                dark = true
                            )
                        }
                        if (cameraStatus.isHeatThrottled) InfoChip("Hot, slowed down", dark = true)
                    }
                }
            }
        }

        // Bottom floating control bar
        AnimatedVisibility(
            visible = overlaysVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = VideoScrim,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilledIconButton(
                        onClick = {
                            latestFrame?.let { bmp ->
                                saveSnapshotToGallery(context, bmp, camera.name)
                            } ?: Toast.makeText(context, "No picture yet", Toast.LENGTH_SHORT).show()
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = LiveTeal),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = "Save picture")
                    }
                    ControlButton(
                        icon = if (isTorchOn) Icons.Filled.FlashlightOn else Icons.Outlined.FlashlightOff,
                        description = if (isTorchOn) "Turn torch off" else "Turn torch on",
                        active = isTorchOn,
                        onClick = {
                            val newTorch = !isTorchOn
                            coroutineScope.launch {
                                if (repository.sendTorchControl(camera, newTorch)) isTorchOn = newTorch
                                else Toast.makeText(context, "Couldn't reach the camera", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    ControlButton(
                        icon = if (isAudioEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
                        description = if (isAudioEnabled) "Mute sound" else "Play sound",
                        active = isAudioEnabled,
                        onClick = { isAudioEnabled = !isAudioEnabled }
                    )
                    ControlButton(
                        icon = Icons.Outlined.Cameraswitch,
                        description = "Switch lens",
                        onClick = {
                            val newFacing = if (currentLensFacing.equals("FRONT", ignoreCase = true)) "BACK" else "FRONT"
                            coroutineScope.launch {
                                if (repository.sendLensControl(camera, newFacing)) {
                                    currentLensFacing = newFacing
                                    Toast.makeText(
                                        context,
                                        if (newFacing == "FRONT") "Front camera" else "Back camera",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                    if (scale > 1f) {
                        TextButton(onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }) {
                            Text("1×", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    ControlButton(
                        icon = Icons.Outlined.Fullscreen,
                        description = "Hide controls",
                        onClick = { overlaysVisible = false }
                    )
                }
            }
        }
    }

    if (showInfoSheet) {
        ModalBottomSheet(
            onDismissRequest = { showInfoSheet = false },
            sheetState = rememberModalBottomSheetState(),
            containerColor = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("${camera.name} details", style = MaterialTheme.typography.headlineSmall)
                Section(title = "Connection") {
                    InfoLine("Route", when (activeRoute) {
                        Route.TAILSCALE -> "Tailscale (encrypted)"
                        Route.INTERNET -> "Internet, IPv6 (encrypted)"
                        Route.LAN -> "Home Wi-Fi"
                        null -> "Not connected"
                    })
                    InfoLine("Address", activeBaseUrl.substringAfter("://"))
                    InfoLine("Internet address", if (camera.hasRemote) camera.remoteHost else "Not set up", divider = false)
                }
                Section(title = "Video") {
                    InfoLine("Camera sends", "${cameraStatus.fps} fps")
                    InfoLine("This phone gets", "$receivedFps fps")
                    InfoLine("Lens", if (cameraStatus.lensFacing.equals("FRONT", true)) "Front" else "Back")
                    InfoLine("People watching", "${cameraStatus.viewers}", divider = false)
                }
                Section(title = "Camera phone") {
                    InfoLine("Battery", "${cameraStatus.battery}%" + if (cameraStatus.isCharging) ", charging" else "")
                    InfoLine("Temperature", "${cameraStatus.temperature.roundToInt()}°C" + if (cameraStatus.isHeatThrottled) ", slowed down to cool" else "")
                    InfoLine("Motion detection", if (cameraStatus.motionEnabled) "On" else "Off")
                    InfoLine("Recording", if (cameraStatus.recording) "On" else "Off", divider = false)
                }
            }
        }
    }

    if (showRecordingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showRecordingsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background
        ) {
            RecordingsSheet(camera = camera, repository = repository)
        }
    }

    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background
        ) {
            CameraSettingsSheet(
                camera = camera,
                repository = repository,
                onDone = { showSettingsSheet = false }
            )
        }
    }
}

/** A round control on the video bar. */
@Composable
private fun ControlButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (active) Color.White else Color.Transparent)
    ) {
        Icon(icon, contentDescription = description, tint = if (active) InkNavy else Color.White)
    }
}

/** Label on the left, value on the right, inside a Section. */
@Composable
private fun InfoLine(label: String, value: String, divider: Boolean = true) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Slate)
            Spacer(Modifier.width(16.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                color = InkNavy,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (divider) HorizontalDivider(color = Mist, modifier = Modifier.padding(start = 16.dp))
    }
}

/**
 * Shown when no route reaches the camera: one row per route with a plain-language reason and
 * what to check, plus "Try again".
 */
@Composable
fun CantConnectCard(
    cameraName: String,
    failures: List<CameraRepository.RouteFailure>,
    onTryAgain: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Can't reach $cameraName", style = MaterialTheme.typography.headlineSmall, color = InkNavy)
            Spacer(Modifier.height(4.dp))
            Text(
                "This phone tried every way it knows:",
                style = MaterialTheme.typography.bodyMedium,
                color = Slate
            )
            Spacer(Modifier.height(12.dp))
            failures.forEach { f ->
                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            when (f.route) {
                                Route.TAILSCALE -> Icons.Outlined.VpnLock
                                Route.INTERNET -> Icons.Outlined.Public
                                Route.LAN -> Icons.Outlined.Wifi
                            },
                            contentDescription = null,
                            tint = Slate,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.route.label, style = MaterialTheme.typography.titleSmall, color = InkNavy)
                        Text(friendlyReason(f), style = MaterialTheme.typography.bodySmall, color = Slate)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onTryAgain, shape = MaterialTheme.shapes.small) {
                    Text("Try again", style = MaterialTheme.typography.labelLarge)
                }
                TextButton(onClick = {
                    val i = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/kumarnitish378/HomeCam#troubleshooting")
                    )
                    try { context.startActivity(i) } catch (_: Exception) { }
                }) {
                    Text("How to fix", color = LiveTeal, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Turns a probe error into what the person can check, per route. */
private fun friendlyReason(f: CameraRepository.RouteFailure): String {
    val r = f.reason.lowercase()
    return when {
        "wrong token" in r -> "The access code changed. Scan the camera's code again."
        "certificate" in r -> "The camera app was reinstalled. Scan its code again."
        "refused" in r -> "The camera phone is there, but HomeCam isn't running. Start the camera."
        "timeout" in r || "no answer" in r || "no route" in r || "not found" in r -> when (f.route) {
            Route.LAN -> "No answer. Is this phone on the same Wi-Fi as the camera?"
            Route.TAILSCALE -> "No answer. Is Tailscale on, on both phones?"
            Route.INTERNET -> "No answer. The router may be blocking port ${f.address.substringAfterLast(':')}, or this network has no IPv6."
        }
        "update the camera app" in r -> "The camera phone needs the latest HomeCam."
        else -> f.reason
    }
}

/**
 * Saves live frame bitmap to device Gallery via MediaStore.
 */
fun saveSnapshotToGallery(context: android.content.Context, bitmap: Bitmap, cameraName: String) {
    val filename = "HomeCam_${cameraName.replace(" ", "_")}_${System.currentTimeMillis()}.jpg"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HomeCam")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

    if (uri != null) {
        try {
            val stream: OutputStream? = resolver.openOutputStream(uri)
            stream?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            Toast.makeText(context, "Picture saved to Gallery", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't save the picture: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(context, "Failed to create media store entry", Toast.LENGTH_SHORT).show()
    }
}
