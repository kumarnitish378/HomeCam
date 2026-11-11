package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.AlertAmberDeep
import com.example.ui.theme.AlertAmberTint
import com.example.ui.theme.InkNavy
import com.example.ui.theme.LiveTeal
import com.example.ui.theme.LiveTealDeep
import com.example.ui.theme.LiveTealTint
import com.example.ui.theme.Mist
import com.example.ui.theme.Pebble
import com.example.ui.theme.RecordRed
import com.example.ui.theme.Slate

/** What a camera is doing; drives the ring and pills everywhere. */
enum class CamState { OFF, CHECKING, LIVE, PROBLEM }

/**
 * The signature element: a camera-lens ring. Teal when live, grey when off, amber on a
 * problem; a red arc around it while recording. [content] sits in the lens.
 */
@Composable
fun StatusRing(
    state: CamState,
    recording: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 232.dp,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val ringColor by animateColorAsState(
        when (state) {
            CamState.LIVE -> LiveTeal
            CamState.PROBLEM -> AlertAmber
            else -> Pebble
        },
        label = "ring"
    )
    // A slow breathing pulse only while live: the one moving thing on the screen.
    val transition = rememberInfiniteTransition(label = "pulse")
    val breathing by transition.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "pulseAlpha"
    )
    val pulse = if (state == CamState.LIVE) breathing else 0.85f

    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val outer = this.size.minDimension
            val stroke = outer * 0.045f
            val inset = stroke * 1.6f
            // Outer bezel
            drawCircle(color = Mist, radius = outer / 2f)
            drawCircle(color = Color.White, radius = outer / 2f - stroke * 0.6f)
            // Status ring, drawn as 4 segments like a lens barrel
            val arcSize = Size(outer - inset * 2, outer - inset * 2)
            for (i in 0 until 4) {
                drawArc(
                    color = ringColor.copy(alpha = if (state == CamState.LIVE) pulse else 0.85f),
                    startAngle = -80f + i * 90f,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            // Recording arc, outside the ring
            if (recording) {
                val r = stroke * 0.55f
                drawArc(
                    color = RecordRed,
                    startAngle = -90f,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(r, r),
                    size = Size(outer - r * 2, outer - r * 2),
                    style = Stroke(width = stroke * 0.55f, cap = StrokeCap.Round)
                )
            }
            // Lens glass
            drawCircle(
                color = if (state == CamState.LIVE) LiveTealTint else Color(0xFFEEF1F4),
                radius = outer / 2f - inset - stroke * 1.4f
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(size * 0.2f),
            content = content
        )
    }
}

/** Small rounded label for state: "Live", "Offline", "Checking", "Problem". */
@Composable
fun StatusPill(state: CamState, modifier: Modifier = Modifier, text: String? = null) {
    val (bg, fg, label) = when (state) {
        CamState.LIVE -> Triple(LiveTealTint, LiveTealDeep, "Live")
        CamState.PROBLEM -> Triple(AlertAmberTint, AlertAmberDeep, "Problem")
        CamState.CHECKING -> Triple(Mist, Slate, "Checking")
        CamState.OFF -> Triple(Mist, Slate, "Offline")
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (state == CamState.LIVE) LiveTeal else fg)
        )
        Spacer(Modifier.width(6.dp))
        Text(text ?: label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/** Red dot + "Recording" (or any text) — red is used for recording only. */
@Composable
fun RecordingTag(text: String = "Recording", modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(RecordRed)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = RecordRed)
    }
}

/** Neutral chip for routes and facts: "Home Wi-Fi", "Tailscale", "12 fps". */
@Composable
fun InfoChip(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, dark: Boolean = false) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (dark) Color.Black.copy(alpha = 0.45f) else MaterialTheme.colorScheme.background)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(14.dp), tint = if (dark) Color.White else Slate)
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (dark) Color.White else InkNavy
        )
    }
}

/** White grouped surface for list rows (one per section, not one per row). */
@Composable
fun Section(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = Slate,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = content)
        }
    }
}

/** One row inside a [Section]: icon, title, optional subtitle, trailing content or chevron. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = InkNavy,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Column {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, Modifier.size(20.dp), tint = iconTint)
                }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = InkNavy)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Slate)
                }
            }
            when {
                trailing != null -> trailing()
                onClick != null -> Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Pebble
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                color = Mist,
                modifier = Modifier.padding(start = if (icon != null) 66.dp else 16.dp)
            )
        }
    }
}

/** One fact in a row of facts: big value, small label. */
@Composable
fun Fact(value: String, label: String, modifier: Modifier = Modifier, valueColor: Color = InkNavy) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.merge(com.example.ui.theme.TabularNumbers),
            color = valueColor,
            textAlign = TextAlign.Center
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = Slate, textAlign = TextAlign.Center)
    }
}

/** Amber notice for warnings, with an optional action. */
@Composable
fun Notice(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = AlertAmberTint,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, AlertAmber.copy(alpha = 0.5f), MaterialTheme.shapes.large)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = AlertAmberDeep)
            Spacer(Modifier.size(2.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = InkNavy)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.size(10.dp))
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = AlertAmberDeep,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onAction)
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}
