package com.example.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LIMIT_CHOICES_MB = listOf(1024, 2048, 4096, 8192, 16384)
private val QUALITY_CHOICES = listOf("LOW" to "Low", "MEDIUM" to "Medium", "HIGH" to "High")

/** Average bitrates matching the camera's RollingRecorder levels (bits per second). */
private fun bitrateFor(quality: String): Int = when (quality) {
    "LOW" -> 600_000
    "HIGH" -> 2_500_000
    else -> 1_200_000
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1024L * 1024 * 1024) String.format(Locale.US, "%.1f GB", bytes / (1024f * 1024 * 1024))
    else String.format(Locale.US, "%.0f MB", bytes / (1024f * 1024))

/**
 * Loop-recording settings for a camera plus its list of recorded segments.
 * Tapping a segment downloads it (over whichever route is active) and opens it in a video player.
 */
@Composable
fun RecordingsSheet(camera: CameraItem, repository: CameraRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf<RecordingConfig?>(null) }
    var recordings by remember { mutableStateOf<List<RecordingItem>>(emptyList()) }
    var enabled by remember { mutableStateOf(true) }
    var limitMb by remember { mutableStateOf(2048) }
    var quality by remember { mutableStateOf("MEDIUM") }
    var saving by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }

    suspend fun reload() {
        val c = repository.fetchRecordingConfig(camera)
        config = c
        if (c != null) {
            enabled = c.enabled
            limitMb = c.limitMb
            quality = c.quality
        }
        recordings = repository.listRecordings(camera).orEmpty()
        loaded = true
    }

    LaunchedEffect(camera.id) { reload() }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Recording", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        if (!loaded) {
            item { CircularProgressIndicator() }
        } else if (config == null) {
            item {
                Text(
                    "Couldn't read recording settings from ${camera.name}. Update the camera phone to " +
                        "the latest HomeCam version, and check it is online.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Record continuously", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Saved on the camera phone. Oldest video is deleted when the limit is reached.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }

            item {
                Text("Storage limit", style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    LIMIT_CHOICES_MB.forEach { mb ->
                        FilterChip(
                            selected = limitMb == mb,
                            onClick = { limitMb = mb },
                            label = { Text("${mb / 1024} GB") },
                            enabled = enabled
                        )
                    }
                }
            }

            item {
                Text("Quality", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QUALITY_CHOICES.forEach { (key, label) ->
                        FilterChip(
                            selected = quality == key,
                            onClick = { quality = key },
                            label = { Text(label) },
                            enabled = enabled
                        )
                    }
                }
                val hours = limitMb.toLong() * 1024 * 1024 * 8f / bitrateFor(quality) / 3600f
                Text(
                    "Keeps about ${String.format(Locale.US, "%.1f", hours)} hours of video. " +
                        "Used now: ${formatSize(config?.usedBytes ?: 0L)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Button(
                    onClick = {
                        saving = true
                        scope.launch {
                            val result = repository.updateRecordingConfig(
                                camera,
                                RecordingConfig(enabled = enabled, limitMb = limitMb, quality = quality)
                            )
                            saving = false
                            if (result != null) {
                                config = result
                                Toast.makeText(context, "Recording settings saved on ${camera.name}", Toast.LENGTH_SHORT).show()
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

            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Recordings (${recordings.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (recordings.isEmpty()) {
                    Text("No recordings yet.", style = MaterialTheme.typography.bodySmall)
                }
            }

            items(recordings, key = { it.name }) { rec ->
                RecordingRow(
                    item = rec,
                    isDownloading = downloading == rec.name,
                    progress = progress,
                    onClick = {
                        if (rec.inProgress || downloading != null) return@RecordingRow
                        downloading = rec.name
                        progress = 0f
                        scope.launch {
                            val file = recordingCacheFile(context, rec.name)
                            val ok = file.exists() || repository.downloadRecording(camera, rec, file) { progress = it }
                            downloading = null
                            if (ok) openVideo(context, file)
                            else Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun RecordingRow(
    item: RecordingItem,
    isDownloading: Boolean,
    progress: Float,
    onClick: () -> Unit
) {
    val start = item.startMillis
    val title = if (start != null) {
        val end = start + 5 * 60 * 1000L
        SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(start)) +
            " – " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(end))
    } else item.name

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !item.inProgress, onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (item.inProgress) Icons.Default.FiberManualRecord else Icons.Default.PlayCircle,
                contentDescription = null,
                tint = if (item.inProgress) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (item.inProgress) "Recording now" else formatSize(item.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (isDownloading) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }
    }
}

/** Downloaded segments live in cache/recordings/ (shared with players via FileProvider). */
private fun recordingCacheFile(context: Context, name: String): File {
    val dir = File(context.cacheDir, "recordings").apply { mkdirs() }
    // Keep the cache small: drop downloads older than a day
    val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    dir.listFiles()?.filter { it.lastModified() < dayAgo }?.forEach { it.delete() }
    return File(dir, name)
}

private fun openVideo(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No video player app found", Toast.LENGTH_SHORT).show()
    }
}
