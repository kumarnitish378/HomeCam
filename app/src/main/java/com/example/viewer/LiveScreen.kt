package com.example.viewer

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
    var showMotionSheet by remember { mutableStateOf(false) }
    var showRecordingsSheet by remember { mutableStateOf(false) }

    BackHandler {
        if (isFullscreen) {
            isFullscreen = false
        } else {
            onBack()
        }
    }

    // How we currently reach the camera (Home Wi-Fi, Tailscale or Internet IPv6)
    var activeRoute by remember { mutableStateOf<Route?>(null) }
    var activeBaseUrl by remember { mutableStateOf(camera.baseUrl) }
    var connectError by remember { mutableStateOf<String?>(null) }

    // Stream frames using MjpegClient. Runs only while screen is active!
    // Each attempt picks the first route that answers (LAN/Tailscale first, then internet),
    // so walking out of the house switches to IPv6 on the next reconnect.
    LaunchedEffect(camera.id) {
        isConnecting = true
        isDisconnected = false
        while (true) {
            val endpoint = repository.resolveEndpoint(camera)
            connectError = if (endpoint == null) repository.lastError(camera) else null
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

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (!isDisconnected && !isConnecting) Color(0xFF10B981) else Color(0xFFEF4444))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(camera.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                text = "${activeRoute?.label ?: "Connecting"} · ${activeBaseUrl.substringAfter("://")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showRecordingsSheet = true }) {
                            Icon(Icons.Default.VideoLibrary, contentDescription = "Recordings")
                        }
                        IconButton(onClick = { showMotionSheet = true }) {
                            Icon(Icons.Default.Tune, contentDescription = "Motion detection settings")
                        }
                        IconButton(onClick = { showInfoSheet = true }) {
                            Icon(Icons.Default.Info, contentDescription = "Camera Details")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White
                    )
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullscreen) androidx.compose.foundation.layout.PaddingValues(0.dp) else padding)
                .background(Color.Black)
        ) {
            // Live Video Canvas with Pinch-to-Zoom
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
                    },
                contentAlignment = Alignment.Center
            ) {
                if (latestFrame != null) {
                    Image(
                        bitmap = latestFrame!!.asImageBitmap(),
                        contentDescription = "Live video feed",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY
                            )
                    )
                } else if (isConnecting) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Connecting to ${camera.name}...",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Disconnected / Reconnecting Overlay
            if (isDisconnected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF1E293B).copy(alpha = 0.92f),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    "Reconnecting...",
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                connectError?.let { reason ->
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        reason,
                                        color = Color.White.copy(alpha = 0.8f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Top Telemetry Badges HUD
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // FPS Badge
                HudBadge(text = "$receivedFps FPS")

                // Network Badge
                HudBadge(
                    text = activeRoute?.label ?: "…",
                    icon = when (activeRoute) {
                        Route.TAILSCALE -> Icons.Default.VpnLock
                        Route.INTERNET -> Icons.Default.Public
                        else -> Icons.Default.Wifi
                    }
                )

                // Battery Badge
                if (cameraStatus.battery > 0) {
                    HudBadge(
                        text = "${cameraStatus.battery}% ${if (cameraStatus.isCharging) "⚡" else ""}"
                    )
                }

                // Heat warning badge if camera phone is throttled
                if (cameraStatus.isHeatThrottled) {
                    HudBadge(
                        text = "Heat Protection (480p/5fps)",
                        icon = Icons.Default.Warning,
                        color = Color(0xFFEF4444)
                    )
                }
            }

            // Motion Alert Chip
            if (cameraStatus.lastMotion > 0) {
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(cameraStatus.lastMotion))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0F172A).copy(alpha = 0.75f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Motion: $timeStr",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            // Bottom Action Controls Bar
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF0F172A).copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp, start = 16.dp, end = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Snapshot button
                    FilledIconButton(
                        onClick = {
                            latestFrame?.let { bmp ->
                                saveSnapshotToGallery(context, bmp, camera.name)
                            } ?: Toast.makeText(context, "No frame available yet", Toast.LENGTH_SHORT).show()
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Camera, contentDescription = "Take Snapshot")
                    }

                    // Torch Toggle
                    IconButton(
                        onClick = {
                            val newTorch = !isTorchOn
                            coroutineScope.launch {
                                val success = repository.sendTorchControl(camera, newTorch)
                                if (success) {
                                    isTorchOn = newTorch
                                    Toast.makeText(context, if (newTorch) "Torch ON" else "Torch OFF", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Icon(
                            if (isTorchOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                            contentDescription = "Toggle Torch",
                            tint = if (isTorchOn) Color(0xFFFBBF24) else Color.White
                        )
                    }

                    // Switch Lens (Front / Back)
                    IconButton(
                        onClick = {
                            val newFacing = if (currentLensFacing.equals("FRONT", ignoreCase = true)) "BACK" else "FRONT"
                            coroutineScope.launch {
                                val success = repository.sendLensControl(camera, newFacing)
                                if (success) {
                                    currentLensFacing = newFacing
                                    Toast.makeText(context, "Switched to $newFacing camera", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Cameraswitch,
                            contentDescription = "Switch Camera Lens",
                            tint = Color.White
                        )
                    }

                    // Reset Zoom if zoomed in
                    if (scale > 1f) {
                        IconButton(onClick = {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        }) {
                            Text("1x", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }

                    // Fullscreen Toggle
                    IconButton(onClick = { isFullscreen = !isFullscreen }) {
                        Icon(
                            if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = "Toggle Fullscreen",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }

    // Camera Details & Diagnostics Bottom Sheet
    if (showInfoSheet) {
        ModalBottomSheet(
            onDismissRequest = { showInfoSheet = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "${camera.name} Telemetry",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                InfoRow("Stream URL", "$activeBaseUrl/stream.mjpg")
                InfoRow("Network Route", when (activeRoute) {
                    Route.TAILSCALE -> "Tailscale (encrypted)"
                    Route.INTERNET -> "Internet IPv6 (HTTPS, pinned)"
                    Route.LAN -> "Home Wi-Fi"
                    null -> "Not connected"
                })
                InfoRow("Internet (IPv6) Address", if (camera.hasRemote) camera.remoteHost else "Not set up")
                InfoRow("Connected Viewers", "${cameraStatus.viewers} devices")
                InfoRow("Caster Camera FPS", "${cameraStatus.fps} fps")
                InfoRow("Received FPS", "$receivedFps fps")
                InfoRow("Battery Level", "${cameraStatus.battery}% ${if (cameraStatus.isCharging) "(Charging)" else ""}")
                InfoRow("Battery Temperature", "${cameraStatus.temperature}°C")
                InfoRow("Heat Protection Throttling", if (cameraStatus.isHeatThrottled) "ACTIVE (Cooling: 480p/5fps)" else "Normal")
                InfoRow("Active Lens", cameraStatus.lensFacing)
                InfoRow("Motion Detection", if (cameraStatus.motionEnabled) "On" else "Off")

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showRecordingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showRecordingsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            RecordingsSheet(camera = camera, repository = repository)
        }
    }

    if (showMotionSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMotionSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            MotionSettingsSheet(
                camera = camera,
                repository = repository,
                onDone = { showMotionSheet = false }
            )
        }
    }
}

/**
 * Reads the camera's motion settings, lets the user change them, and sends them back.
 * Sensitivity is shown as "% of the image that must change", where lower = more sensitive.
 */
@Composable
fun MotionSettingsSheet(
    camera: CameraItem,
    repository: CameraRepository,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }
    var triggerPercent by remember { mutableFloatStateOf(2f) }   // 0.5 .. 10 %
    var cooldownSec by remember { mutableFloatStateOf(30f) }     // 5 .. 300 s

    LaunchedEffect(camera.id) {
        val config = repository.fetchMotionConfig(camera)
        if (config != null) {
            enabled = config.enabled
            triggerPercent = (config.sensitivity * 100f).coerceIn(0.5f, 10f)
            cooldownSec = config.cooldownSec.toFloat().coerceIn(5f, 300f)
        } else {
            loadFailed = true
        }
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Motion detection", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        if (!loaded) {
            CircularProgressIndicator()
        } else if (loadFailed) {
            Text(
                "Couldn't read settings from ${camera.name}. Update the camera phone to the latest " +
                    "HomeCam version, and check it is online.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Detect motion", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Save a snapshot and alert on the camera phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            Column {
                Text(
                    "Trigger when ${String.format(Locale.US, "%.1f", triggerPercent)}% of the image changes",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Lower = more sensitive (more alerts). Default 2%.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = triggerPercent,
                    onValueChange = { triggerPercent = (it * 2).roundToInt() / 2f }, // 0.5 % steps
                    valueRange = 0.5f..10f,
                    enabled = enabled
                )
            }

            Column {
                Text(
                    "Wait ${cooldownSec.roundToInt()} s between alerts",
                    style = MaterialTheme.typography.titleMedium
                )
                Slider(
                    value = cooldownSec,
                    onValueChange = { cooldownSec = ((it / 5f).roundToInt() * 5).toFloat() }, // 5 s steps
                    valueRange = 5f..300f,
                    enabled = enabled
                )
            }

            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        val result = repository.updateMotionConfig(
                            camera,
                            MotionConfig(
                                enabled = enabled,
                                sensitivity = triggerPercent / 100f,
                                cooldownSec = cooldownSec.roundToInt()
                            )
                        )
                        saving = false
                        if (result != null) {
                            Toast.makeText(context, "Motion settings saved on ${camera.name}", Toast.LENGTH_SHORT).show()
                            onDone()
                        } else {
                            Toast.makeText(context, "Couldn't reach ${camera.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (saving) "Saving..." else "Save to camera")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun HudBadge(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    color: Color = Color(0xFF0F172A).copy(alpha = 0.75f)
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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
            Toast.makeText(context, "Snapshot saved to Gallery", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to save snapshot: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(context, "Failed to create media store entry", Toast.LENGTH_SHORT).show()
    }
}
