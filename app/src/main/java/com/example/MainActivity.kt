package com.example

import android.Manifest
import com.example.update.AppUpdater
import com.example.ui.ModePickerScreen
import com.example.camera.FrameBus
import com.example.update.UpdateHost
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.camera.CameraScreen
import com.example.core.SettingsRepository
import com.example.pairing.QrScannerScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewer.CameraItem
import com.example.viewer.CameraListScreen
import com.example.viewer.CameraRepository
import com.example.viewer.LiveScreen
import com.example.viewer.ManualCameraDialog
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var cameraRepository: CameraRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always light: keep dark status/navigation bar icons even in system dark mode
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            )
        )

        settingsRepository = SettingsRepository(applicationContext)
        cameraRepository = CameraRepository(settingsRepository)

        setContent {
            MyApplicationTheme {
                MainAppContent(
                    settingsRepo = settingsRepository,
                    cameraRepo = cameraRepository
                )
            }
        }
    }
}

enum class ViewerSubScreen {
    LIST,
    QR_SCANNER,
    LIVE
}

@Composable
fun MainAppContent(
    settingsRepo: SettingsRepository,
    cameraRepo: CameraRepository
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val savedMode by settingsRepo.appMode.collectAsState(initial = "NONE")

    // In-session active mode override so user can easily switch between modes
    var activeMode by remember(savedMode) { mutableStateOf(savedMode) }

    // Viewer sub-navigation state
    var viewerSubScreen by remember { mutableStateOf(ViewerSubScreen.LIST) }
    var selectedCameraForLive by remember { mutableStateOf<CameraItem?>(null) }
    var showManualAddDialogInScanner by remember { mutableStateOf(false) }

    // Bumped by the "Check for updates" button; UpdateHost also checks once on start.
    var updateCheckRequest by remember { mutableIntStateOf(0) }
    UpdateHost(manualCheckRequest = updateCheckRequest)

    // Permissions launcher
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        if (!cameraGranted) {
            Toast.makeText(context, "Camera permission is required for streaming & QR scanning", Toast.LENGTH_LONG).show()
        }
    }

    fun requestCameraPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionsLauncher.launch(permissionsToRequest.toTypedArray())
    }

    AnimatedContent(targetState = activeMode, label = "ModeTransition") { mode ->
        when (mode) {
            "CAMERA" -> {
                BackHandler {
                    activeMode = "NONE"
                }
                CameraScreen(
                    settingsRepo = settingsRepo,
                    onSwitchToViewer = {
                        activeMode = "VIEWER"
                        coroutineScope.launch { settingsRepo.setAppMode("VIEWER") }
                    }
                )
            }

            "VIEWER" -> {
                when (viewerSubScreen) {
                    ViewerSubScreen.LIST -> {
                        BackHandler {
                            activeMode = "NONE"
                        }
                        CameraListScreen(
                            repository = cameraRepo,
                            onSelectCamera = { camera ->
                                selectedCameraForLive = camera
                                viewerSubScreen = ViewerSubScreen.LIVE
                            },
                            onScanQr = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    viewerSubScreen = ViewerSubScreen.QR_SCANNER
                                } else {
                                    requestCameraPermissions()
                                    viewerSubScreen = ViewerSubScreen.QR_SCANNER
                                }
                            },
                            onSwitchToCamera = {
                                activeMode = "CAMERA"
                                coroutineScope.launch { settingsRepo.setAppMode("CAMERA") }
                            }
                        )
                    }

                    ViewerSubScreen.QR_SCANNER -> {
                        BackHandler {
                            viewerSubScreen = ViewerSubScreen.LIST
                        }
                        QrScannerScreen(
                            onQrScanned = { pairing ->
                                coroutineScope.launch {
                                    val newCamera = CameraItem(
                                        name = pairing.name,
                                        host = pairing.host,
                                        port = pairing.port,
                                        token = pairing.token,
                                        remoteHost = pairing.remoteHost,
                                        remotePort = pairing.remotePort,
                                        certFp = pairing.certFp,
                                        altHosts = pairing.altHosts
                                    )
                                    cameraRepo.addCamera(newCamera)
                                    selectedCameraForLive = newCamera
                                    viewerSubScreen = ViewerSubScreen.LIVE
                                    Toast.makeText(context, "Paired with ${pairing.name}!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onBack = {
                                viewerSubScreen = ViewerSubScreen.LIST
                            },
                            onEnterManually = {
                                showManualAddDialogInScanner = true
                            }
                        )

                        if (showManualAddDialogInScanner) {
                            ManualCameraDialog(
                                initialName = "",
                                initialHost = "",
                                initialPort = 8080,
                                initialToken = "",
                                title = "Enter Camera Details",
                                onDismiss = { showManualAddDialogInScanner = false },
                                onConfirm = { name, host, port, token ->
                                    coroutineScope.launch {
                                        val newCamera = CameraItem(
                                            name = name.ifBlank { "HomeCam" },
                                            host = host.trim(),
                                            port = port,
                                            token = token.trim()
                                        )
                                        cameraRepo.addCamera(newCamera)
                                        showManualAddDialogInScanner = false
                                        selectedCameraForLive = newCamera
                                        viewerSubScreen = ViewerSubScreen.LIVE
                                        Toast.makeText(context, "Camera added!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }

                    ViewerSubScreen.LIVE -> {
                        selectedCameraForLive?.let { camera ->
                            LiveScreen(
                                camera = camera,
                                repository = cameraRepo,
                                onBack = {
                                    viewerSubScreen = ViewerSubScreen.LIST
                                }
                            )
                        } ?: run {
                            viewerSubScreen = ViewerSubScreen.LIST
                        }
                    }
                }
            }

            else -> {
                // Initial Mode Selection Screen
                ModePickerScreen(
                    onCheckUpdates = { updateCheckRequest++ },
                    onOpenCamera = {
                        activeMode = "CAMERA"
                        coroutineScope.launch { settingsRepo.setAppMode("CAMERA") }
                    },
                    onSelectMode = { selected ->
                        if (selected == "CAMERA") {
                            requestCameraPermissions()
                        }
                        coroutineScope.launch {
                            settingsRepo.setAppMode(selected)
                            activeMode = selected
                        }
                    }
                )
            }
        }
    }
}
