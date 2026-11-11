package com.example.viewer

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.components.CamState
import com.example.ui.components.InfoChip
import com.example.ui.components.RecordingTag
import com.example.ui.components.StatusPill
import com.example.ui.theme.InkNavy
import com.example.ui.theme.LiveTeal
import com.example.ui.theme.Mist
import com.example.ui.theme.Pebble
import com.example.ui.theme.RecordRed
import com.example.ui.theme.Slate
import com.example.ui.theme.TabularNumbers
import com.example.ui.theme.VideoBlack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Viewer home: one tile per camera with a recent picture and its state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraListScreen(
    repository: CameraRepository,
    onSelectCamera: (CameraItem) -> Unit,
    onScanQr: () -> Unit,
    onSwitchToCamera: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val cameras by repository.cameras.collectAsState(initial = emptyList())

    val statusMap = remember { mutableStateMapOf<String, CameraStatus>() }
    val thumbnails = remember { mutableStateMapOf<String, Bitmap>() }

    var showManualAddDialog by remember { mutableStateOf(false) }
    var cameraToEdit by remember { mutableStateOf<CameraItem?>(null) }
    var cameraToDelete by remember { mutableStateOf<CameraItem?>(null) }

    // Status every 10 s; a fresh thumbnail every 30 s for cameras that are live
    LaunchedEffect(cameras) {
        var round = 0
        while (true) {
            for (camera in cameras) {
                val status = repository.fetchStatus(camera)
                statusMap[camera.id] = status
                if (status.isOnline && (round % 3 == 0 || thumbnails[camera.id] == null)) {
                    repository.fetchSnapshot(camera)?.let { thumbnails[camera.id] = it }
                }
            }
            round++
            delay(10_000)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("My cameras", style = MaterialTheme.typography.headlineSmall) },
                actions = {
                    IconButton(onClick = onSwitchToCamera) {
                        Icon(Icons.Outlined.PhoneAndroid, contentDescription = "Use this phone as a camera")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        if (cameras.isEmpty()) {
            EmptyCamerasView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onScanQr = onScanQr,
                onAddManual = { showManualAddDialog = true }
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(cameras, key = { it.id }) { camera ->
                    CameraTile(
                        camera = camera,
                        status = statusMap[camera.id] ?: CameraStatus(),
                        thumbnail = thumbnails[camera.id],
                        onClick = { onSelectCamera(camera) },
                        onEdit = { cameraToEdit = camera },
                        onDelete = { cameraToDelete = camera }
                    )
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = onScanQr,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                        ) {
                            Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Add camera", style = MaterialTheme.typography.labelLarge)
                        }
                        OutlinedButton(
                            onClick = { showManualAddDialog = true },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.height(52.dp)
                        ) {
                            Icon(Icons.Outlined.Keyboard, contentDescription = "Type camera details")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    if (showManualAddDialog) {
        ManualCameraDialog(
            initialName = "",
            initialHost = "",
            initialPort = 8080,
            initialToken = "",
            title = "Add camera by hand",
            onDismiss = { showManualAddDialog = false },
            onConfirm = { name, host, port, token ->
                coroutineScope.launch {
                    repository.addCamera(
                        CameraItem(
                            name = name.ifBlank { "HomeCam" },
                            host = host.trim(),
                            port = port,
                            token = token.trim()
                        )
                    )
                    showManualAddDialog = false
                    Toast.makeText(context, "Camera added", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    cameraToEdit?.let { camera ->
        var newName by remember { mutableStateOf(camera.name) }
        AlertDialog(
            onDismissRequest = { cameraToEdit = null },
            title = { Text("Rename camera") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    placeholder = { Text("Front gate") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    coroutineScope.launch {
                        repository.updateCameraName(camera.id, newName.ifBlank { camera.name })
                        cameraToEdit = null
                    }
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { cameraToEdit = null }) { Text("Cancel") }
            }
        )
    }

    cameraToDelete?.let { camera ->
        AlertDialog(
            onDismissRequest = { cameraToDelete = null },
            title = { Text("Remove ${camera.name}?") },
            text = { Text("It disappears from this phone. The camera keeps running; scan its code to add it again.") },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            repository.removeCamera(camera.id)
                            cameraToDelete = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RecordRed)
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { cameraToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

/** A camera: recent picture on top, name and state below. Long-press for rename/remove. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CameraTile(
    camera: CameraItem,
    status: CameraStatus,
    thumbnail: Bitmap?,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val state = when {
        !status.checked -> CamState.CHECKING
        status.isOnline && status.cameraError != null -> CamState.PROBLEM
        status.isOnline -> CamState.LIVE
        else -> CamState.OFF
    }
    val route = status.route ?: if (camera.isTailscale) Route.TAILSCALE else Route.LAN

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true })
    ) {
        Column {
            // Picture
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(if (thumbnail != null) VideoBlack else Mist),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = "Latest picture from ${camera.name}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        alpha = if (state == CamState.OFF) 0.4f else 1f
                    )
                } else {
                    Icon(
                        if (state == CamState.OFF) Icons.Outlined.VideocamOff else Icons.Outlined.Videocam,
                        contentDescription = null,
                        tint = Pebble,
                        modifier = Modifier.size(40.dp)
                    )
                }
                StatusPill(
                    state = state,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                )
                InfoChip(
                    text = route.label,
                    icon = when (route) {
                        Route.TAILSCALE -> Icons.Outlined.VpnLock
                        Route.INTERNET -> Icons.Outlined.Public
                        Route.LAN -> Icons.Outlined.Wifi
                    },
                    dark = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                )
            }

            // Name and state
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        camera.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = InkNavy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    when (state) {
                        CamState.LIVE, CamState.PROBLEM -> Row(verticalAlignment = Alignment.CenterVertically) {
                            if (status.recording) {
                                RecordingTag()
                                Text("  ·  ", style = MaterialTheme.typography.bodySmall, color = Slate)
                            }
                            Text(
                                text = buildString {
                                    append(if (status.viewers == 1) "1 watching" else "${status.viewers} watching")
                                    if (!status.motionEnabled) append("  ·  Motion off")
                                    else if (status.lastMotion > 0) append(
                                        "  ·  Motion " + SimpleDateFormat("HH:mm", Locale.getDefault())
                                            .format(Date(status.lastMotion))
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                                color = Slate,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        CamState.CHECKING -> Text("Checking...", style = MaterialTheme.typography.bodySmall, color = Slate)
                        CamState.OFF -> Text(
                            "Can't reach this camera. Checking again every 10 s.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate,
                            maxLines = 2
                        )
                    }
                    if (state == CamState.PROBLEM) {
                        Text(
                            status.cameraError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = com.example.ui.theme.AlertAmberDeep,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More for ${camera.name}", tint = Slate)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menuExpanded = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text("Remove", color = RecordRed) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = RecordRed) },
                            onClick = { menuExpanded = false; onDelete() }
                        )
                    }
                }
            }
        }
    }
}

/** No cameras yet: one clear next step. */
@Composable
fun EmptyCamerasView(
    modifier: Modifier = Modifier,
    onScanQr: () -> Unit,
    onAddManual: () -> Unit
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(50))
                .background(Mist),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Videocam, null, Modifier.size(40.dp), tint = Slate)
        }
        Spacer(Modifier.height(20.dp))
        Text("No cameras yet", style = MaterialTheme.typography.headlineSmall, color = InkNavy)
        Spacer(Modifier.height(8.dp))
        Text(
            "On the camera phone, open HomeCam, tap Pair a viewer phone, then scan the code here.",
            style = MaterialTheme.typography.bodyMedium,
            color = Slate,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onScanQr,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Scan camera code", style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = onAddManual) {
            Text("Type the details instead", color = LiveTeal)
        }
    }
}

@Composable
fun ManualCameraDialog(
    initialName: String,
    initialHost: String,
    initialPort: Int,
    initialToken: String,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Int, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var host by remember { mutableStateOf(initialHost) }
    var portText by remember { mutableStateOf(initialPort.toString()) }
    var token by remember { mutableStateOf(initialToken) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("Front gate") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Camera address") },
                    placeholder = { Text("192.168.31.201 or 100.x.x.x") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { c -> c.isDigit() } },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Access code") },
                    placeholder = { Text("On the camera: Pair a viewer phone") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, host, portText.toIntOrNull() ?: 8080, token) },
                enabled = host.isNotBlank() && token.isNotBlank()
            ) { Text("Add camera") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
