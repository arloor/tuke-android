package com.arloor.tuke.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Background = Color(0xFFF8F9FF)
val Card = Color(0xFFFFFFFF)
val Border = Color(0xFFDDE3EB)
val TextPrimary = Color(0xFF1B1C1F)
val TextMuted = Color(0xFF44474F)
val TextSubtle = Color(0xFF6F7785)
val Primary = Color(0xFF0B57D0)
val PrimaryPressed = Color(0xFF0842A0)
val PrimaryContainer = Color(0xFFDBE2FF)
val Positive = Color(0xFFC5221F)
val Negative = Color(0xFF188038)
val Danger = Color(0xFFB3261E)
val DangerBg = Color(0xFFF9DEDC)
val DangerBorder = Color(0xFFF2B8B5)
val Success = Color(0xFF146C2E)
val SuccessBg = Color(0xFFDDF7DE)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Primary,
    tertiary = Primary,
    background = Background,
    surface = Card,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    outline = Border,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    background = Color(0xFF10131A),
    surface = Color(0xFF1A1D25),
    onSurface = Color(0xFFE3E7EF),
    onBackground = Color(0xFFE3E7EF),
)

@Composable
fun TukeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}