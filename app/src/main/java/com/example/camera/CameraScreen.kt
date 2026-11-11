package com.example.camera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.core.NetUtils
import com.example.core.SettingsRepository
import com.example.core.TlsIdentity
import com.example.pairing.PairingInfo
import com.example.pairing.QrCodeGenerator
import com.example.ui.components.CamState
import com.example.ui.components.Fact
import com.example.ui.components.ListRow
import com.example.ui.components.Notice
import com.example.ui.components.RecordingTag
import com.example.ui.components.Section
import com.example.ui.components.StatusRing
import com.example.ui.theme.AlertAmberDeep
import com.example.ui.theme.InkNavy
import com.example.ui.theme.LiveTeal
import com.example.ui.theme.RecordRed
import com.example.ui.theme.Slate
import com.example.ui.theme.TabularNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    settingsRepo: SettingsRepository,
    onSwitchToViewer: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val isStreaming by FrameBus.isStreaming.collectAsState()
    val activeViewers by FrameBus.activeViewers.collectAsState()
    val currentFps by FrameBus.currentFps.collectAsState()
    val batteryPercent by FrameBus.batteryPercent.collectAsState()
    val batteryTemp by FrameBus.batteryTemp.collectAsState()
    val isCharging by FrameBus.isCharging.collectAsState()
    val isHeatThrottled by FrameBus.isHeatThrottled.collectAsState()
    val lastMotion by FrameBus.lastMotionTimestamp.collectAsState()
    val cameraError by FrameBus.cameraError.collectAsState()
    val currentLensFacing by FrameBus.lensFacing.collectAsState()
    val remoteError by FrameBus.remoteError.collectAsState()
    val recordingActive by FrameBus.recordingActive.collectAsState()
    val recordingUsed by FrameBus.recordingUsedBytes.collectAsState()
    val recordingError by FrameBus.recordingError.collectAsState()

    val config by settingsRepo.cameraConfig.collectAsState(initial = SettingsRepository.CameraConfig())
    var token by remember { mutableStateOf("") }
    val serviceHasMic by FrameBus.serviceHasMic.collectAsState()
    val audioError by FrameBus.audioError.collectAsState()

    fun micGranted() = ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.RECORD_AUDIO
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var micPermission by remember { mutableStateOf(micGranted()) }

    fun startCameraService() {
        val startIntent = Intent(context, CameraStreamService::class.java).apply {
            action = CameraStreamService.ACTION_START
            putExtra(CameraStreamService.EXTRA_TOKEN, token)
            putExtra(CameraStreamService.EXTRA_PORT, config.port)
            putExtra(CameraStreamService.EXTRA_CAMERA_NAME, config.cameraName)
            putExtra(CameraStreamService.EXTRA_TAILSCALE_ONLY, config.tailscaleOnly)
        }
        ContextCompat.startForegroundService(context, startIntent)
    }

    // What to do once the mic permission dialog closes: start, or restart so the
    // service runs with the microphone foreground type.
    var afterMicPrompt by remember { mutableStateOf<String?>(null) }
    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        micPermission = granted
        when (afterMicPrompt) {
            "start" -> startCameraService()
            "restart" -> if (granted) {
                context.startService(Intent(context, CameraStreamService::class.java).apply {
                    action = CameraStreamService.ACTION_STOP
                })
                coroutineScope.launch {
                    // wait for the old service to release camera and port
                    var waited = 0
                    while (FrameBus.isStreaming.value && waited < 5000) {
                        delay(100); waited += 100
                    }
                    delay(500)
                    startCameraService()
                }
            }
        }
        afterMicPrompt = null
        if (!granted) {
            Toast.makeText(context, "Without microphone permission, viewers can't hear audio", Toast.LENGTH_LONG).show()
        }
    }

    var isTokenVisible by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showBatteryOptimizationBanner by remember { mutableStateOf(false) }

    // Network IPs
    var tailscaleIp by remember { mutableStateOf<String?>(null) }
    var lanIp by remember { mutableStateOf<String?>(null) }
    var selectedHostForQr by remember { mutableStateOf<String>("") }

    // Fetch token & IPs
    LaunchedEffect(Unit) {
        token = settingsRepo.getOrCreateToken()
        tailscaleIp = NetUtils.findTailscaleIp()
        lanIp = NetUtils.findLanIp()
        selectedHostForQr = tailscaleIp ?: lanIp ?: "127.0.0.1"

        // Check battery optimizations
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            showBatteryOptimizationBanner = true
        }
    }

    // Periodically refresh IP addresses
    LaunchedEffect(Unit) {
        while (true) {
            delay(15000)
            tailscaleIp = NetUtils.findTailscaleIp()
            lanIp = NetUtils.findLanIp()
            if (selectedHostForQr.isEmpty() || selectedHostForQr == "127.0.0.1") {
                selectedHostForQr = tailscaleIp ?: lanIp ?: "127.0.0.1"
            }
        }
    }

    // Internet (IPv6) access: address + certificate fingerprint for the QR code
    var remoteHostForQr by remember { mutableStateOf("") }
    var certFp by remember { mutableStateOf("") }
    LaunchedEffect(config.remoteEnabled, config.duckDomain) {
        while (config.remoteEnabled) {
            certFp = withContext(Dispatchers.IO) {
                runCatching { TlsIdentity.fingerprint() }.getOrDefault("")
            }
            val ipv6 = withContext(Dispatchers.IO) { NetUtils.findGlobalIpv6(context) }
            remoteHostForQr = if (config.duckDomain.isNotBlank()) {
                DuckDnsUpdater.fullName(config.duckDomain)
            } else ipv6.orEmpty()
            delay(30_000)
        }
        remoteHostForQr = ""
    }

    val pairingInfo = remember(config.cameraName, selectedHostForQr, config.port, token, remoteHostForQr, certFp, config.remotePort, tailscaleIp, lanIp) {
        PairingInfo(
            name = config.cameraName,
            host = selectedHostForQr,
            port = config.port,
            token = token,
            remoteHost = remoteHostForQr,
            remotePort = config.remotePort,
            certFp = certFp,
            // Viewer tries every address, so pairing works whichever one is reachable
            altHosts = listOfNotNull(tailscaleIp, lanIp).filter { it != selectedHostForQr }
        )
    }

    val qrBitmap = remember(pairingInfo) {
        if (pairingInfo.host.isNotEmpty() && pairingInfo.token.isNotEmpty()) {
            QrCodeGenerator.generateQrBitmap(pairingInfo.toUriString(), 420)
        } else null
    }

    // Pair-a-viewer opens as its own screen
    var showPairScreen by remember { mutableStateOf(false) }
    BackHandler(enabled = showPairScreen) { showPairScreen = false }

    fun regenerateTokenAndRestart() {
        coroutineScope.launch {
            token = settingsRepo.regenerateToken()
            // The running server still checks the old token until it restarts
            if (FrameBus.isStreaming.value) {
                context.startService(Intent(context, CameraStreamService::class.java).apply {
                    action = CameraStreamService.ACTION_STOP
                })
                var waited = 0
                while (FrameBus.isStreaming.value && waited < 5000) {
                    delay(100); waited += 100
                }
                delay(500)
                startCameraService()
            }
            Toast.makeText(context, "New code made. Viewers need to scan it again.", Toast.LENGTH_LONG).show()
        }
    }

    if (showPairScreen) {
        PairViewerScreen(
            qrBitmap = qrBitmap,
            pairingLink = pairingInfo.toUriString(),
            token = token,
            lanIp = lanIp,
            tailscaleIp = tailscaleIp,
            selectedHost = selectedHostForQr,
            onSelectHost = { selectedHostForQr = it },
            onNewCode = { regenerateTokenAndRestart() },
            onBack = { showPairScreen = false }
        )
        return
    }

    val ringState = when {
        !isStreaming -> CamState.OFF
        cameraError != null -> CamState.PROBLEM
        else -> CamState.LIVE
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(config.cameraName, style = MaterialTheme.typography.titleLarge)
                        Text("Camera", style = MaterialTheme.typography.bodySmall, color = Slate)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = onSwitchToViewer) {
                        Icon(Icons.Outlined.Devices, contentDescription = "Watch my cameras")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusRing(
                state = ringState,
                recording = isStreaming && recordingActive,
                size = 220.dp,
                description = if (isStreaming) "Camera live, $activeViewers watching" else "Camera off"
            ) {
                Text(
                    text = when (ringState) {
                        CamState.LIVE -> "Live"
                        CamState.PROBLEM -> "Live, with a problem"
                        else -> "Camera off"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (ringState == CamState.OFF) Slate else InkNavy,
                    textAlign = TextAlign.Center
                )
                if (isStreaming) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            append(if (activeViewers == 1) "1 watching" else "$activeViewers watching")
                            if (activeViewers > 0) append(" · $currentFps fps")
                        },
                        style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                        color = Slate,
                        textAlign = TextAlign.Center
                    )
                    if (recordingActive) {
                        Spacer(Modifier.height(6.dp))
                        RecordingTag()
                    }
                }
            }

            // One big action
            if (isStreaming) {
                OutlinedButton(
                    onClick = {
                        context.startService(Intent(context, CameraStreamService::class.java).apply {
                            action = CameraStreamService.ACTION_STOP
                        })
                    },
                    shape = MaterialTheme.shapes.small,
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, InkNavy),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = InkNavy),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop camera", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Button(
                    onClick = {
                        if (micGranted()) {
                            startCameraService()
                        } else {
                            afterMicPrompt = "start"
                            micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start camera", style = MaterialTheme.typography.labelLarge)
                }
            }

            // Facts
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 14.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Fact(
                        value = "$batteryPercent%",
                        label = if (isCharging) "Battery, charging" else "Battery",
                        modifier = Modifier.weight(1f),
                        valueColor = if (!isCharging && batteryPercent < 20) RecordRed else InkNavy
                    )
                    Fact(
                        value = "${batteryTemp.roundToInt()}°C",
                        label = if (isHeatThrottled) "Hot, slowed down" else "Temperature",
                        modifier = Modifier.weight(1f),
                        valueColor = if (isHeatThrottled) AlertAmberDeep else InkNavy
                    )
                    Fact(
                        value = when {
                            !config.motionEnabled -> "Off"
                            lastMotion > 0 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastMotion))
                            else -> "On"
                        },
                        label = if (config.motionEnabled && lastMotion > 0) "Last motion" else "Motion",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Problems, only when there is one
            if (isStreaming && cameraError != null) {
                Notice(title = "Camera problem", body = cameraError!!)
            }
            if (isStreaming && (!micPermission || !serviceHasMic || audioError != null)) {
                Notice(
                    title = "Live audio is off",
                    body = when {
                        !micPermission -> "Allow the microphone so viewers can hear this camera."
                        !serviceHasMic -> "The microphone was allowed after the camera started. Restart to turn audio on."
                        else -> audioError.orEmpty()
                    },
                    actionLabel = when {
                        !micPermission -> "Allow microphone and restart"
                        !serviceHasMic -> "Restart camera"
                        else -> null
                    },
                    onAction = {
                        afterMicPrompt = "restart"
                        micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                )
            }
            if (showBatteryOptimizationBanner) {
                Notice(
                    title = "Let HomeCam run in the background",
                    body = "Without this, Android may stop the camera when the screen turns off.",
                    actionLabel = "Allow",
                    onAction = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            })
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                        showBatteryOptimizationBanner = false
                    }
                )
            }

            // Everything else as one list
            Section {
                ListRow(
                    title = "Pair a viewer phone",
                    subtitle = "Show the code to scan",
                    icon = Icons.Outlined.QrCode2,
                    onClick = { showPairScreen = true }
                )
                ListRow(
                    title = "Recording",
                    subtitle = when {
                        !config.recordingEnabled -> "Off"
                        isStreaming && recordingError != null -> recordingError
                        else -> "${"%.1f".format(Locale.US, recordingUsed / (1024f * 1024 * 1024))} of " +
                            "${config.recordingLimitMb / 1024} GB used · oldest video deleted first"
                    },
                    icon = Icons.Outlined.FiberManualRecord,
                    iconTint = if (recordingActive) RecordRed else InkNavy,
                    onClick = { showSettingsDialog = true }
                )
                ListRow(
                    title = "Internet access (IPv6)",
                    subtitle = when {
                        !config.remoteEnabled -> "Off · watch from outside without Tailscale"
                        isStreaming && remoteError != null -> remoteError
                        remoteHostForQr.isNotBlank() -> "https://${NetUtils.hostForUrl(remoteHostForQr)}:${config.remotePort}"
                        else -> "Looking for a public IPv6 address..."
                    },
                    icon = Icons.Outlined.Public,
                    iconTint = if (config.remoteEnabled && remoteError != null && isStreaming) AlertAmberDeep else InkNavy,
                    onClick = { showSettingsDialog = true }
                )
                ListRow(
                    title = "Lens",
                    subtitle = if (currentLensFacing.equals("FRONT", true)) "Front camera" else "Back camera",
                    icon = Icons.Outlined.Cameraswitch,
                    onClick = {
                        val newFacing = if (currentLensFacing.equals("FRONT", ignoreCase = true)) "BACK" else "FRONT"
                        coroutineScope.launch {
                            settingsRepo.updateCameraConfig(lensFacing = newFacing)
                            FrameBus.controlCommands.tryEmit("LENS_$newFacing")
                        }
                    },
                    trailing = { Text("Switch", style = MaterialTheme.typography.labelLarge, color = LiveTeal) }
                )
                ListRow(
                    title = "Settings",
                    subtitle = "Name, quality, motion, network",
                    icon = Icons.Outlined.Settings,
                    showDivider = false,
                    onClick = { showSettingsDialog = true }
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    // Camera Settings Dialog
    if (showSettingsDialog) {
        CameraSettingsDialog(
            config = config,
            onDismiss = { showSettingsDialog = false },
            onSave = { updatedConfig ->
                coroutineScope.launch {
                    settingsRepo.updateCameraConfig(
                        cameraName = updatedConfig.cameraName,
                        port = updatedConfig.port,
                        width = updatedConfig.resolutionWidth,
                        height = updatedConfig.resolutionHeight,
                        fps = updatedConfig.maxFps,
                        quality = updatedConfig.jpegQuality,
                        lensFacing = updatedConfig.lensFacing,
                        motionSensitivity = updatedConfig.motionSensitivity,
                        motionEnabled = updatedConfig.motionEnabled,
                        tailscaleOnly = updatedConfig.tailscaleOnly,
                        remoteEnabled = updatedConfig.remoteEnabled,
                        duckDomain = updatedConfig.duckDomain,
                        duckToken = updatedConfig.duckToken,
                        recordingEnabled = updatedConfig.recordingEnabled,
                        recordingLimitMb = updatedConfig.recordingLimitMb,
                        recordingQuality = updatedConfig.recordingQuality
                    )
                    showSettingsDialog = false
                    Toast.makeText(context, "Settings saved. If streaming, restart to apply network changes.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

/** Full screen with the pairing QR, chosen address, copy link and "new code". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairViewerScreen(
    qrBitmap: android.graphics.Bitmap?,
    pairingLink: String,
    token: String,
    lanIp: String?,
    tailscaleIp: String?,
    selectedHost: String,
    onSelectHost: (String) -> Unit,
    onNewCode: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var isTokenVisible by remember { mutableStateOf(false) }
    var confirmNewCode by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Pair a viewer", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Open HomeCam on the phone you watch from, tap Add camera, and scan this.",
                style = MaterialTheme.typography.bodyLarge,
                color = InkNavy,
                textAlign = TextAlign.Center
            )

            Surface(
                shape = MaterialTheme.shapes.large,
                color = androidx.compose.ui.graphics.Color.White,
                shadowElevation = 1.dp
            ) {
                Box(Modifier.padding(20.dp), contentAlignment = Alignment.Center) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Pairing code",
                            modifier = Modifier.size(240.dp)
                        )
                    } else {
                        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                            Text("Making the code...", style = MaterialTheme.typography.bodyMedium, color = Slate)
                        }
                    }
                }
            }

            // Which address goes first in the code. The code carries all of them anyway.
            val choices = listOfNotNull(
                lanIp?.let { "Home Wi-Fi" to it },
                tailscaleIp?.let { "Tailscale" to it }
            )
            if (choices.size > 1) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    choices.forEachIndexed { i, (label, ip) ->
                        SegmentedButton(
                            selected = selectedHost == ip,
                            onClick = { onSelectHost(ip) },
                            shape = SegmentedButtonDefaults.itemShape(i, choices.size)
                        ) { Text(label) }
                    }
                }
            }
            Text(
                "The code works on home Wi-Fi, Tailscale and internet access, whichever reaches the camera.",
                style = MaterialTheme.typography.bodySmall,
                color = Slate,
                textAlign = TextAlign.Center
            )

            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("HomeCam pairing link", pairingLink))
                    Toast.makeText(context, "Pairing link copied", Toast.LENGTH_SHORT).show()
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Copy pairing link", style = MaterialTheme.typography.labelLarge)
            }

            Section {
                ListRow(
                    title = if (isTokenVisible) token else "Access code hidden",
                    subtitle = if (isTokenVisible) "Anyone with this code can watch" else "Tap to show",
                    icon = if (isTokenVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Key,
                    onClick = { isTokenVisible = !isTokenVisible },
                    trailing = {}
                )
                ListRow(
                    title = "New code",
                    subtitle = "Disconnects all viewers",
                    icon = Icons.Outlined.Refresh,
                    iconTint = RecordRed,
                    showDivider = false,
                    onClick = { confirmNewCode = true }
                )
            }
        }
    }

    if (confirmNewCode) {
        AlertDialog(
            onDismissRequest = { confirmNewCode = false },
            title = { Text("Make a new code?") },
            text = { Text("Every phone paired with this camera will stop working until it scans the new code.") },
            confirmButton = {
                TextButton(onClick = { confirmNewCode = false; onNewCode() }) {
                    Text("Make new code", color = RecordRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmNewCode = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun CameraSettingsDialog(
    config: SettingsRepository.CameraConfig,
    onDismiss: () -> Unit,
    onSave: (SettingsRepository.CameraConfig) -> Unit
) {
    var name by remember { mutableStateOf(config.cameraName) }
    var portText by remember { mutableStateOf(config.port.toString()) }
    var fps by remember { mutableIntStateOf(config.maxFps) }
    var quality by remember { mutableIntStateOf(config.jpegQuality) }
    var sensitivity by remember { mutableStateOf(config.motionSensitivity) }
    var motionEnabled by remember { mutableStateOf(config.motionEnabled) }
    var tailscaleOnly by remember { mutableStateOf(config.tailscaleOnly) }
    var remoteEnabled by remember { mutableStateOf(config.remoteEnabled) }
    var duckDomain by remember { mutableStateOf(config.duckDomain) }
    var duckToken by remember { mutableStateOf(config.duckToken) }
    var recordingEnabled by remember { mutableStateOf(config.recordingEnabled) }
    var recordingLimitMb by remember { mutableIntStateOf(config.recordingLimitMb) }
    var recordingQuality by remember { mutableStateOf(config.recordingQuality) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Camera settings") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Camera name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                HorizontalDivider()

                Text("Live video: up to $fps fps", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = fps.toFloat(),
                    onValueChange = { fps = it.toInt() },
                    valueRange = 5f..24f,
                    steps = 18
                )

                Text("Live picture quality: $quality%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = quality.toFloat(),
                    onValueChange = { quality = it.toInt() },
                    valueRange = 30f..95f,
                    steps = 12
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Motion detection", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Switch(checked = motionEnabled, onCheckedChange = { motionEnabled = it })
                }

                Text(
                    "Motion Sensitivity: ${String.format(Locale.US, "%.1f", sensitivity * 100)}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = sensitivity,
                    onValueChange = { sensitivity = it },
                    valueRange = 0.005f..0.08f
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Tailscale only", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Ignore viewers on the same Wi-Fi. Use on Wi-Fi you don't trust.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = tailscaleOnly,
                        onCheckedChange = { tailscaleOnly = it }
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Loop recording", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Records continuously on this phone; deletes the oldest video at the limit.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = recordingEnabled, onCheckedChange = { recordingEnabled = it })
                }

                if (recordingEnabled) {
                    Text("Storage limit: ${recordingLimitMb / 1024} GB", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = (recordingLimitMb / 1024).toFloat(),
                        onValueChange = { recordingLimitMb = it.roundToInt().coerceIn(1, 32) * 1024 },
                        valueRange = 1f..32f,
                        steps = 30
                    )
                    Text("Recording quality", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        listOf("LOW" to "Low", "MEDIUM" to "Medium", "HIGH" to "High").forEach { (key, label) ->
                            FilterChip(
                                selected = recordingQuality == key,
                                onClick = { recordingQuality = key },
                                label = { Text(label) }
                            )
                        }
                    }
                    val level = RollingRecorder.Level.from(recordingQuality)
                    Text(
                        "About ${"%.1f".format(Locale.US, level.hoursFor(recordingLimitMb.toLong() * 1024 * 1024))} hours of video",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Internet access (IPv6)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Watch from anywhere without Tailscale. Encrypted HTTPS on port ${config.remotePort}; " +
                                "the viewer's network also needs IPv6.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = remoteEnabled,
                        onCheckedChange = { remoteEnabled = it }
                    )
                }

                if (remoteEnabled) {
                    Text(
                        "Optional: a free DuckDNS name keeps working when your router's IPv6 address changes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = duckDomain,
                        onValueChange = { duckDomain = it.trim() },
                        label = { Text("DuckDNS name (e.g. nitishcam)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = duckToken,
                        onValueChange = { duckToken = it.trim() },
                        label = { Text("DuckDNS token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedPort = portText.toIntOrNull() ?: 8080
                onSave(
                    config.copy(
                        cameraName = name.ifBlank { "HomeCam 1" },
                        port = parsedPort,
                        maxFps = fps,
                        jpegQuality = quality,
                        motionSensitivity = sensitivity,
                        motionEnabled = motionEnabled,
                        tailscaleOnly = tailscaleOnly,
                        remoteEnabled = remoteEnabled,
                        duckDomain = duckDomain.removeSuffix(".duckdns.org"),
                        duckToken = duckToken,
                        recordingEnabled = recordingEnabled,
                        recordingLimitMb = recordingLimitMb,
                        recordingQuality = recordingQuality
                    )
                )
            }) {
                Text("Save settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
