package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

/** Figtree (SIL Open Font License, see licenses/Figtree-OFL.txt), bundled in res/font. */
val Figtree = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
    Font(R.font.figtree_bold, FontWeight.Bold)
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Figtree,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp
)

/** Type scale: 12 / 14 / 16 / 18 / 22 / 28 / 34, sentence case everywhere. */
val Typography = Typography(
    displaySmall = style(34, 40, FontWeight.Bold, -0.5),
    headlineMedium = style(28, 34, FontWeight.Bold, -0.3),
    headlineSmall = style(22, 28, FontWeight.Bold, -0.2),
    titleLarge = style(20, 26, FontWeight.SemiBold),
    titleMedium = style(17, 24, FontWeight.SemiBold),
    titleSmall = style(15, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(13, 18, FontWeight.Normal),
    labelLarge = style(15, 20, FontWeight.SemiBold),
    labelMedium = style(13, 16, FontWeight.Medium),
    labelSmall = style(12, 16, FontWeight.Medium)
)

/** Numbers that change in place (fps, %, times) don't jitter with tabular figures. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")
