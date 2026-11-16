package dev.kestrel.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Plover palette: shore teal and dry sand on a near-black night shore. Dark only.
val Teal = Color(0xFF3DD9C5)
val Sand = Color(0xFFF2D49B)
val DeepSea = Color(0xFF0E4A52)
/** Live badges and the "recording" dot keep a warm red so they read as urgent. */
val LiveRed = Color(0xFFE5484D)
/** Text and icons drawn on top of video or thumbnails. */
val OnVideo = Color.White
val VideoScrim = Color(0xCC000000)

private val Night = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF00201C),
    primaryContainer = DeepSea,
    onPrimaryContainer = Color(0xFFB8F5EC),
    secondary = Sand,
    onSecondary = Color(0xFF2A1F00),
    secondaryContainer = Color(0xFF303030),
    onSecondaryContainer = Color(0xFFF1F1F1),
    tertiary = Sand,
    onTertiary = Color(0xFF2A1F00),
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFF1F1F1),
    surface = Color(0xFF0F0F0F),
    onSurface = Color(0xFFF1F1F1),
    surfaceVariant = Color(0xFF272727),
    onSurfaceVariant = Color(0xFFAAAAAA),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF161616),
    surfaceContainer = Color(0xFF212121),
    surfaceContainerHigh = Color(0xFF272727),
    surfaceContainerHighest = Color(0xFF303030),
    surfaceBright = Color(0xFF3A3A3A),
    outline = Color(0xFF4A4A4A),
    outlineVariant = Color(0xFF303030),
    inverseSurface = Color(0xFFF1F1F1),
    inverseOnSurface = Color(0xFF0F0F0F),
    inversePrimary = Color(0xFF006B5F),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0000),
    scrim = Color(0xFF000000),
)

private val Type = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

private val Shape = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun KestrelTheme(content: @Composable () -> Unit) {
    // Always dark: light status and navigation bar icons.
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(colorScheme = Night, typography = Type, shapes = Shape, content = content)
}
