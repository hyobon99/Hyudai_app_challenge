package com.roadtalk.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object RoadColors {
    val Background = Color(0xFF0B0F17)
    val Surface = Color(0xFF151B26)
    val SurfaceHigh = Color(0xFF1E2633)
    val Accent = Color(0xFF3D8BFF)
    val AccentDim = Color(0xFF1B3A66)
    val Mine = Color(0xFF2453A6)
    val Theirs = Color(0xFF2A3342)
    val Ok = Color(0xFF3CCB7F)
    val Warn = Color(0xFFFFB443)
    val Error = Color(0xFFFF5D5D)
    val TextPrimary = Color(0xFFF2F5FA)
    val TextSecondary = Color(0xFF9AA6B8)
}

@Composable
fun RoadTalkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RoadColors.Accent,
            background = RoadColors.Background,
            surface = RoadColors.Surface,
            onPrimary = Color.White,
            onBackground = RoadColors.TextPrimary,
            onSurface = RoadColors.TextPrimary,
        ),
        content = content,
    )
}
