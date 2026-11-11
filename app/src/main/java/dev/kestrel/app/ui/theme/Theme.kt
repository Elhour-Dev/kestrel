package dev.kestrel.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Kestrel palette: rust orange (the bird's back) on warm slate.
val Rust = Color(0xFFD2632F)
private val RustDark = Color(0xFFFF9A62)

private val Light = lightColorScheme(
    primary = Color(0xFFB04E1F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCB),
    onPrimaryContainer = Color(0xFF3A0F00),
    secondary = Color(0xFF4F5B66),
    secondaryContainer = Color(0xFFDDE4EC),
    background = Color(0xFFFCF8F6),
    surface = Color(0xFFFCF8F6),
    surfaceContainer = Color(0xFFF3EDEA),
    surfaceContainerHigh = Color(0xFFEDE7E4),
    surfaceVariant = Color(0xFFF1E6E1),
    onSurfaceVariant = Color(0xFF5A4F4A),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = RustDark,
    onPrimary = Color(0xFF4A1700),
    primaryContainer = Color(0xFF6E2C0B),
    onPrimaryContainer = Color(0xFFFFDBCB),
    secondary = Color(0xFFB9C4CF),
    secondaryContainer = Color(0xFF38434D),
    background = Color(0xFF121110),
    surface = Color(0xFF121110),
    surfaceContainer = Color(0xFF1D1B1A),
    surfaceContainerHigh = Color(0xFF272423),
    surfaceVariant = Color(0xFF2B2624),
    onSurfaceVariant = Color(0xFFD1C4BE),
    error = Color(0xFFFFB4AB),
)

private val Type = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
)

@Composable
fun KestrelTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
}
