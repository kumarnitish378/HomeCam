package com.example.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.AlertAmberDeep
import com.example.ui.theme.AlertAmberTint
import com.example.ui.theme.InkNavy
import com.example.ui.theme.LiveTeal
import com.example.ui.theme.LiveTealTint
import com.example.ui.theme.Mist
import com.example.ui.theme.RecordRed
import com.example.ui.theme.RecordRedTint
import com.example.ui.theme.Slate
import com.example.ui.theme.TabularNumbers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val SEGMENT_MS = 5 * 60 * 1000L

/** Average bitrates matching the camera's RollingRecorder levels (bits per second). */
internal fun bitrateFor(quality: String): Int = when (quality) {
    "LOW" -> 600_000
    "HIGH" -> 2_500_000
    else -> 1_200_000
}

internal fun formatSize(bytes: Long): String =
    if (bytes >= 1024L * 1024 * 1024) String.format(Locale.US, "%.1f GB", bytes / (1024f * 1024 * 1024))
    else String.format(Locale.US, "%.0f MB", bytes / (1024f * 1024))

/**
 * Recorded clips as a timeline grouped by day. Clips with motion get an amber marker; the clip
 * being written now is shown first in red. Tap a clip to download and play it.
 */
@Composable
fun RecordingsSheet(camera: CameraItem, repository: CameraRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var reachable by remember { mutableStateOf(true) }
    var config by remember { mutableStateOf<RecordingConfig?>(null) }
    var recordings by remember { mutableStateOf<List<RecordingItem>>(emptyList()) }
    var motionTimes by remember { mutableStateOf<List<Long>>(emptyList()) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(camera.id) {
        config = repository.fetchRecordingConfig(camera)
        val list = repository.listRecordings(camera)
        reachable = list != null
        recordings = list.orEmpty()
        motionTimes = repository.fetchMotionTimes(camera)
        loaded = true
    }

    // Group by day, newest first (the camera already lists newest first)
    val groups = remember(recordings) {
        recordings.groupBy { dayLabel(it.startMillis) }.toList()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Recordings", style = MaterialTheme.typography.headlineSmall, color = InkNavy)
            config?.let { c ->
                val hours = c.limitMb.toLong() * 1024 * 1024 * 8f / bitrateFor(c.quality) / 3600f
                Text(
                    if (c.enabled) {
                        "${formatSize(c.usedBytes)} of ${c.limitMb / 1024} GB · keeps about " +
                            "${String.format(Locale.US, "%.0f", hours)} hours"
                    } else "Recording is off on this camera. Turn it on in camera settings.",
                    style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                    color = Slate
                )
            }
        }

        when {
            !loaded -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LiveTeal)
                }
            }
            !reachable -> item {
                Text(
                    "Couldn't get the list from ${camera.name}. Check it's live, or update HomeCam on the camera phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate
                )
            }
            recordings.isEmpty() -> item {
                Text(
                    "No recordings yet. The first clip appears 5 minutes after recording starts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate
                )
            }
            else -> groups.forEach { (day, clips) ->
                item(key = "h-$day") {
                    Text(
                        day,
                        style = MaterialTheme.typography.titleSmall,
                        color = Slate,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                    )
                }
                item(key = "g-$day") {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            clips.forEachIndexed { i, rec ->
                                val start = rec.startMillis
                                val hasMotion = start != null &&
                                    motionTimes.any { it >= start && it < start + SEGMENT_MS }
                                ClipRow(
                                    item = rec,
                                    hasMotion = hasMotion,
                                    isDownloading = downloading == rec.name,
                                    progress = progress,
                                    onClick = {
                                        if (rec.inProgress || downloading != null) return@ClipRow
                                        downloading = rec.name
                                        progress = 0f
                                        scope.launch {
                                            val file = recordingCacheFile(context, rec.name)
                                            val ok = file.exists() ||
                                                repository.downloadRecording(camera, rec, file) { progress = it }
                                            downloading = null
                                            if (ok) openVideo(context, file)
                                            else Toast.makeText(context, "Couldn't download this clip", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                                if (i < clips.lastIndex) {
                                    HorizontalDivider(color = Mist, modifier = Modifier.padding(start = 72.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ClipRow(
    item: RecordingItem,
    hasMotion: Boolean,
    isDownloading: Boolean,
    progress: Float,
    onClick: () -> Unit
) {
    val start = item.startMillis
    val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    val title = if (start != null) "${hm.format(Date(start))} – ${hm.format(Date(start + SEGMENT_MS))}" else item.name

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !item.inProgress, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Time block: red while recording, amber with motion, teal otherwise
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            item.inProgress -> RecordRedTint
                            hasMotion -> AlertAmberTint
                            else -> LiveTealTint
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (item.inProgress) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(RecordRed)
                    )
                } else {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        tint = if (hasMotion) AlertAmberDeep else LiveTeal
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.merge(TabularNumbers),
                    color = InkNavy
                )
                Text(
                    when {
                        item.inProgress -> "Recording now"
                        else -> "5 min · ${formatSize(item.size)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.inProgress) RecordRed else Slate
                )
            }
            if (hasMotion && !item.inProgress) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(AlertAmberTint)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(AlertAmber)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("Motion", style = MaterialTheme.typography.labelSmall, color = AlertAmberDeep)
                }
            }
        }
        if (isDownloading) {
            LinearProgressIndicator(
                progress = { progress },
                color = LiveTeal,
                trackColor = Mist,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
    }
}

/** "Today", "Yesterday", or "Mon 9 Nov" from a clip's start time. */
private fun dayLabel(millis: Long?): String {
    if (millis == null) return "Older"
    val clip = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance()
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(clip, today)) return "Today"
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (sameDay(clip, today)) return "Yesterday"
    return SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(millis))
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
