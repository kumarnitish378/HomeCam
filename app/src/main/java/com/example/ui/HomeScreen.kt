package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.camera.FrameBus
import com.example.ui.components.CamState
import com.example.ui.components.StatusRing
import com.example.ui.theme.InkNavy
import com.example.ui.theme.LiveTeal
import com.example.ui.theme.LiveTealTint
import com.example.ui.theme.Pebble
import com.example.ui.theme.Slate
import com.example.ui.theme.TabularNumbers
import com.example.update.AppUpdater

/**
 * First screen: shows whether this phone's camera is streaming (the big lens ring) and offers
 * the two roles. Tapping the ring opens Camera mode.
 */
@Composable
fun ModePickerScreen(
    onCheckUpdates: () -> Unit,
    onOpenCamera: () -> Unit,
    onSelectMode: (String) -> Unit
) {
    val streaming by FrameBus.isStreaming.collectAsState()
    val viewers by FrameBus.activeViewers.collectAsState()
    val recording by FrameBus.recordingActive.collectAsState()
    val cameraError by FrameBus.cameraError.collectAsState()

    val state = when {
        streaming && cameraError != null -> CamState.PROBLEM
        streaming -> CamState.LIVE
        else -> CamState.OFF
    }
    val headline = when (state) {
        CamState.LIVE -> "This phone is streaming"
        CamState.PROBLEM -> "Camera needs attention"
        else -> "This phone's camera is off"
    }
    val detail = when (state) {
        CamState.LIVE -> buildString {
            append(if (viewers == 1) "1 watching" else "$viewers watching")
            if (recording) append(" · recording")
        }
        CamState.PROBLEM -> cameraError.orEmpty()
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.homecam_icon_1794215361656), // raster: adaptive icons can't be painted
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.width(10.dp))
            Text("HomeCam", style = MaterialTheme.typography.headlineMedium, color = InkNavy)
        }

        Spacer(Modifier.height(32.dp))

        StatusRing(
            state = state,
            recording = streaming && recording,
            description = headline + (detail?.let { ". $it" } ?: ""),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(enabled = streaming, onClick = onOpenCamera)
        ) {
            Text(
                headline,
                style = MaterialTheme.typography.titleMedium,
                color = if (state == CamState.OFF) Slate else InkNavy,
                textAlign = TextAlign.Center
            )
            if (detail != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                    color = Slate,
                    textAlign = TextAlign.Center,
                    maxLines = 3
                )
            }
        }

        Spacer(Modifier.height(36.dp))

        RoleCard(
            icon = Icons.Outlined.PhoneAndroid,
            title = if (streaming) "Open camera" else "Use this phone as a camera",
            subtitle = "Old phone",
            onClick = { onSelectMode("CAMERA") }
        )
        Spacer(Modifier.height(12.dp))
        RoleCard(
            icon = Icons.Outlined.Groups,
            title = "Watch my cameras",
            subtitle = "Daily phone",
            onClick = { onSelectMode("VIEWER") }
        )

        Spacer(Modifier.height(28.dp))

        Text(
            "Version ${AppUpdater.currentVersion} · Check for updates",
            style = MaterialTheme.typography.labelMedium,
            color = Slate,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onCheckUpdates)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun RoleCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(50))
                    .background(LiveTealTint),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, Modifier.size(26.dp), tint = LiveTeal)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = InkNavy)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Slate)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Pebble)
        }
    }
}
