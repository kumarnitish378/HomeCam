package com.example.camera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.core.NetUtils
import com.example.core.TlsIdentity
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.horizontalScroll
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Public
import com.example.core.SettingsRepository
import com.example.pairing.PairingInfo
import com.example.pairing.QrCodeGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    val pairingInfo = remember(config.cameraName, selectedHostForQr, config.port, token, remoteHostForQr, certFp, config.remotePort) {
        PairingInfo(
            name = config.cameraName,
            host = selectedHostForQr,
            port = config.port,
            token = token,
            remoteHost = remoteHostForQr,
            remotePort = config.remotePort,
            certFp = certFp
        )
    }

    val qrBitmap = remember(pairingInfo) {
        if (pairingInfo.host.isNotEmpty() && pairingInfo.token.isNotEmpty()) {
            QrCodeGenerator.generateQrBitmap(pairingInfo.toUriString(), 420)
        } else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isStreaming) Color(0xFF10B981) else Color(0xFF94A3B8))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("HomeCam (Caster)")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = onSwitchToViewer) {
                        Icon(Icons.Default.Devices, contentDescription = "Switch to Viewer Mode")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Streaming Status Hero Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isStreaming) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val statusDotColor by animateColorAsState(
                        targetValue = if (isStreaming) Color(0xFF10B981) else Color(0xFF64748B),
                        label = "statusDotColor"
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(statusDotColor)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isStreaming) "STREAMING ACTIVE" else "CAMERA STOPPED",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isStreaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (isStreaming) {
                            "Running in foreground service. Keep on charger; safe with screen off."
                        } else {
                            "Tap start to begin broadcasting MJPEG stream over network."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Big Start / Stop Button
                    if (isStreaming) {
                        Button(
                            onClick = {
                                val stopIntent = Intent(context, CameraStreamService::class.java).apply {
                                    action = CameraStreamService.ACTION_STOP
                                }
                                context.startService(stopIntent)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Stop Camera Service", style = MaterialTheme.typography.titleMedium)
                        }
                    } else {
                        Button(
                            onClick = {
                                val startIntent = Intent(context, CameraStreamService::class.java).apply {
                                    action = CameraStreamService.ACTION_START
                                    putExtra(CameraStreamService.EXTRA_TOKEN, token)
                                    putExtra(CameraStreamService.EXTRA_PORT, config.port)
                                    putExtra(CameraStreamService.EXTRA_CAMERA_NAME, config.cameraName)
                                    putExtra(CameraStreamService.EXTRA_TAILSCALE_ONLY, config.tailscaleOnly)
                                }
                                ContextCompat.startForegroundService(context, startIntent)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Start Camera Service", style = MaterialTheme.typography.titleMedium)
                        }
                    }

                    if (cameraError != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = cameraError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Live Telemetry Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryChip(
                    icon = Icons.Default.Visibility,
                    label = "Viewers",
                    value = "$activeViewers connected",
                    modifier = Modifier.weight(1f)
                )
                TelemetryChip(
                    icon = Icons.Default.NetworkCheck,
                    label = "Frame Rate",
                    value = "$currentFps fps",
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryChip(
                    icon = if (isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryAlert,
                    label = "Battery",
                    value = "$batteryPercent% ${if (isCharging) "⚡" else ""}",
                    modifier = Modifier.weight(1f)
                )
                TelemetryChip(
                    icon = Icons.Default.Thermostat,
                    label = "Temperature",
                    value = String.format(Locale.US, "%.1f°C", batteryTemp),
                    alert = isHeatThrottled,
                    alertText = if (isHeatThrottled) "Heat Throttled (480p/5fps)" else null,
                    modifier = Modifier.weight(1f)
                )
            }

            // Last Motion Banner
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Motion Detection", style = MaterialTheme.typography.labelMedium)
                        val lastMotionText = if (lastMotion > 0L) {
                            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastMotion))
                            "Last event at $timeStr"
                        } else {
                            "Monitoring active (cooldown 30s)"
                        }
                        Text(
                            text = lastMotionText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // QR Code Pairing Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = CardDefaults.outlinedCardBorder(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.QrCode, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Pair Viewer Phone",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        IconButton(onClick = {
                            coroutineScope.launch {
                                token = settingsRepo.regenerateToken()
                                Toast.makeText(context, "New token generated. Viewers unpaired.", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Regenerate Token")
                        }
                    }

                    Text(
                        text = "Scan this QR code with HomeCam in Viewer mode on your other phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // QR Image
                    if (qrBitmap != null) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            shadowElevation = 2.dp,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Pairing QR Code",
                                modifier = Modifier
                                    .size(210.dp)
                                    .padding(12.dp)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(210.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Generating QR...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Network IP Selector Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (tailscaleIp != null) {
                            FilterChipCustom(
                                label = "Tailscale: $tailscaleIp",
                                isSelected = selectedHostForQr == tailscaleIp,
                                icon = Icons.Default.VpnLock,
                                onClick = { selectedHostForQr = tailscaleIp!! }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        if (lanIp != null) {
                            FilterChipCustom(
                                label = "LAN: $lanIp",
                                isSelected = selectedHostForQr == lanIp,
                                icon = Icons.Default.Wifi,
                                onClick = { selectedHostForQr = lanIp!! }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Copy URI button
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("HomeCam Pairing URI", pairingInfo.toUriString())
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Pairing link copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy Pairing Link")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Show / Hide Token
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isTokenVisible = !isTokenVisible }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            if (isTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isTokenVisible) token else "Show Token (Hidden for Security)",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = if (isTokenVisible) FontFamily.Monospace else FontFamily.Default,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Loop recording status
            if (config.recordingEnabled) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isStreaming && recordingError != null) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.FiberManualRecord,
                                contentDescription = null,
                                tint = if (recordingActive) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (recordingActive) "Recording to this phone" else "Loop recording",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = when {
                                !isStreaming -> "Starts with the camera service."
                                recordingError != null -> recordingError!!
                                else -> "Used ${"%.2f".format(Locale.US, recordingUsed / (1024f * 1024 * 1024))} GB " +
                                    "of ${"%.1f".format(Locale.US, config.recordingLimitMb / 1024f)} GB. " +
                                    "Oldest video is deleted automatically. Live video is only sent while a viewer is watching."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isStreaming && recordingError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Internet (IPv6) access status
            if (config.remoteEnabled) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (remoteError != null) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Internet access (IPv6, no Tailscale)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (remoteHostForQr.isNotBlank()) {
                                "https://${NetUtils.hostForUrl(remoteHostForQr)}:${config.remotePort}"
                            } else {
                                "No public IPv6 address found on Wi-Fi yet."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = when {
                                !isStreaming -> "Starts with the camera service."
                                remoteError != null -> remoteError!!
                                else -> "Included in the pairing QR. Allow inbound TCP ${config.remotePort} " +
                                    "for this phone in the router's IPv6 firewall."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isStreaming && remoteError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Battery Optimization Alert Banner
            AnimatedVisibility(visible = showBatteryOptimizationBanner) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "24/7 Background Running",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Disable battery optimization so Android does not kill the camera service when the screen turns off.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { showBatteryOptimizationBanner = false }) {
                                Text("Dismiss")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            FilledTonalButton(onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(intent)
                                }
                                showBatteryOptimizationBanner = false
                            }) {
                                Text("Allow Unrestricted")
                            }
                        }
                    }
                }
            }

            // Quick Hardware Lens Flip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Active Lens: ${currentLensFacing.uppercase()}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                OutlinedButton(
                    onClick = {
                        val newFacing = if (currentLensFacing.equals("FRONT", ignoreCase = true)) "BACK" else "FRONT"
                        coroutineScope.launch {
                            settingsRepo.updateCameraConfig(lensFacing = newFacing)
                            FrameBus.controlCommands.tryEmit("LENS_$newFacing")
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Switch Lens")
                }
            }
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

@Composable
fun FilterChipCustom(
    label: String,
    isSelected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun TelemetryChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    alert: Boolean = false,
    alertText: String? = null,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (alert) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        ),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            if (alertText != null) {
                Text(
                    text = alertText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
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
    var tailscaleOnly by remember { mutableStateOf(config.tailscaleOnly) }
    var remoteEnabled by remember { mutableStateOf(config.remoteEnabled) }
    var duckDomain by remember { mutableStateOf(config.duckDomain) }
    var duckToken by remember { mutableStateOf(config.duckToken) }
    var recordingEnabled by remember { mutableStateOf(config.recordingEnabled) }
    var recordingLimitMb by remember { mutableIntStateOf(config.recordingLimitMb) }
    var recordingQuality by remember { mutableStateOf(config.recordingQuality) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Camera Settings") },
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
                    label = { Text("Camera Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("HTTP Port") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                HorizontalDivider()

                Text("Max Frame Rate: $fps fps", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = fps.toFloat(),
                    onValueChange = { fps = it.toInt() },
                    valueRange = 5f..24f,
                    steps = 18
                )

                Text("JPEG Quality: $quality%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = quality.toFloat(),
                    onValueChange = { quality = it.toInt() },
                    valueRange = 30f..95f,
                    steps = 12
                )

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
                        Text("Tailscale Only", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Drops LAN access for security on public/shared Wi-Fi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
