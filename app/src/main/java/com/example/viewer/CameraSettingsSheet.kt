package com.example.viewer

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.ui.components.Section
import com.example.ui.theme.InkNavy
import com.example.ui.theme.LiveTeal
import com.example.ui.theme.Mist
import com.example.ui.theme.Slate
import com.example.ui.theme.TabularNumbers
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private val LIMIT_CHOICES_MB = listOf(1024, 2048, 4096, 8192, 16384)
private val QUALITY_CHOICES = listOf("LOW" to "Low", "MEDIUM" to "Medium", "HIGH" to "High")

/**
 * Settings for one camera, changed from the viewer phone: motion detection and recording.
 * Reads both from the camera, then saves both with one button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSettingsSheet(
    camera: CameraItem,
    repository: CameraRepository,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var motionLoaded by remember { mutableStateOf(false) }
    var recordingLoaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // Motion
    var motionOn by remember { mutableStateOf(true) }
    var triggerPercent by remember { mutableFloatStateOf(2f) }   // 0.5 .. 10 %
    var cooldownSec by remember { mutableFloatStateOf(30f) }     // 5 .. 300 s

    // Recording
    var recordingOn by remember { mutableStateOf(true) }
    var limitMb by remember { mutableIntStateOf(2048) }
    var quality by remember { mutableStateOf("MEDIUM") }
    var usedBytes by remember { mutableStateOf(0L) }

    LaunchedEffect(camera.id) {
        repository.fetchMotionConfig(camera)?.let {
            motionOn = it.enabled
            triggerPercent = (it.sensitivity * 100f).coerceIn(0.5f, 10f)
            cooldownSec = it.cooldownSec.toFloat().coerceIn(5f, 300f)
            motionLoaded = true
        }
        repository.fetchRecordingConfig(camera)?.let {
            recordingOn = it.enabled
            limitMb = it.limitMb
            quality = it.quality
            usedBytes = it.usedBytes
            recordingLoaded = true
        }
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Camera settings", style = MaterialTheme.typography.headlineSmall, color = InkNavy)
        Text(camera.name, style = MaterialTheme.typography.bodyMedium, color = Slate)

        if (!loaded) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LiveTeal)
            }
            return@Column
        }
        if (!motionLoaded && !recordingLoaded) {
            Text(
                "Couldn't read settings from ${camera.name}. Check it's live, or update HomeCam on the camera phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = Slate
            )
            return@Column
        }

        if (motionLoaded) {
            Section(title = "Motion detection") {
                SwitchLine(
                    title = "Detect motion",
                    subtitle = "Save a picture and alert on the camera phone",
                    checked = motionOn,
                    onChange = { motionOn = it }
                )
                HorizontalDivider(color = Mist, modifier = Modifier.padding(start = 16.dp))
                SliderLine(
                    title = "Trigger when ${String.format(Locale.US, "%.1f", triggerPercent)}% of the image changes",
                    hint = "Lower is more sensitive. 2% suits most rooms.",
                    value = triggerPercent,
                    range = 0.5f..10f,
                    enabled = motionOn,
                    onChange = { triggerPercent = (it * 2).roundToInt() / 2f }
                )
                HorizontalDivider(color = Mist, modifier = Modifier.padding(start = 16.dp))
                SliderLine(
                    title = "Wait ${cooldownSec.roundToInt()} s between alerts",
                    hint = null,
                    value = cooldownSec,
                    range = 5f..300f,
                    enabled = motionOn,
                    onChange = { cooldownSec = ((it / 5f).roundToInt() * 5).toFloat() }
                )
            }
        }

        if (recordingLoaded) {
            Section(title = "Recording") {
                SwitchLine(
                    title = "Record all the time",
                    subtitle = "Saved on the camera phone; the oldest video is deleted first",
                    checked = recordingOn,
                    onChange = { recordingOn = it }
                )
                HorizontalDivider(color = Mist, modifier = Modifier.padding(start = 16.dp))
                Column(Modifier.padding(16.dp)) {
                    Text("Storage", style = MaterialTheme.typography.titleSmall, color = InkNavy)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        LIMIT_CHOICES_MB.forEach { mb ->
                            FilterChip(
                                selected = limitMb == mb,
                                onClick = { limitMb = mb },
                                label = { Text("${mb / 1024} GB") },
                                enabled = recordingOn
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Quality", style = MaterialTheme.typography.titleSmall, color = InkNavy)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        QUALITY_CHOICES.forEachIndexed { i, (key, label) ->
                            SegmentedButton(
                                selected = quality == key,
                                onClick = { quality = key },
                                enabled = recordingOn,
                                shape = SegmentedButtonDefaults.itemShape(i, QUALITY_CHOICES.size)
                            ) { Text(label) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val hours = limitMb.toLong() * 1024 * 1024 * 8f / bitrateFor(quality) / 3600f
                    Text(
                        "About ${String.format(Locale.US, "%.0f", hours)} hours of video · ${formatSize(usedBytes)} used now",
                        style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                        color = Slate
                    )
                }
            }
        }

        Button(
            onClick = {
                saving = true
                scope.launch {
                    val motionOk = !motionLoaded || repository.updateMotionConfig(
                        camera,
                        MotionConfig(
                            enabled = motionOn,
                            sensitivity = triggerPercent / 100f,
                            cooldownSec = cooldownSec.roundToInt()
                        )
                    ) != null
                    val recordingOk = !recordingLoaded || repository.updateRecordingConfig(
                        camera,
                        RecordingConfig(enabled = recordingOn, limitMb = limitMb, quality = quality)
                    ) != null
                    saving = false
                    if (motionOk && recordingOk) {
                        Toast.makeText(context, "Saved to ${camera.name}", Toast.LENGTH_SHORT).show()
                        onDone()
                    } else {
                        Toast.makeText(context, "Couldn't reach ${camera.name}. Nothing was saved there.", Toast.LENGTH_LONG).show()
                    }
                }
            },
            enabled = !saving,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(if (saving) "Saving..." else "Save to camera", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SwitchLine(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = InkNavy)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Slate)
        }
        Spacer(Modifier.padding(start = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderLine(
    title: String,
    hint: String?,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.merge(TabularNumbers),
            color = if (enabled) InkNavy else Slate
        )
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Slate)
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
    }
}
