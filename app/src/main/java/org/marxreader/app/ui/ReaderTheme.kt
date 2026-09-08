package org.marxreader.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.activity.compose.LocalActivity
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.marxreader.app.data.ReaderTheme

val Wine = Color(0xFFBC3543)
val WineDark = Color(0xFF74212B)
val Gold = Color(0xFF59606D)
val Ink = Color(0xFF17191E)
val Paper = Color(0xFFF5F6F8)
val Sepia = Color(0xFFF4EBD8)

private val PaperScheme = lightColorScheme(
    primary = Wine,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFBE8EB),
    onPrimaryContainer = WineDark,
    secondary = Gold,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEBEDF1),
    onSecondaryContainer = Color(0xFF343944),
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEEF0F4),
    onSurfaceVariant = Color(0xFF616773),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0F1F5),
    surfaceContainerHigh = Color(0xFFE8EAF0),
    surfaceContainerHighest = Color(0xFFDEE1E8),
    outline = Color(0xFF818896),
    outlineVariant = Color(0xFFE0E3E9),
    error = Color(0xFFBA1A1A)
)

private val SepiaScheme = lightColorScheme(
    primary = Color(0xFF75232A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF4D5D2),
    onPrimaryContainer = Color(0xFF481217),
    secondary = Color(0xFF886323),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2DFB7),
    onSecondaryContainer = Color(0xFF2C2007),
    background = Sepia,
    onBackground = Color(0xFF332C24),
    surface = Sepia,
    onSurface = Color(0xFF332C24),
    surfaceVariant = Color(0xFFE9DCC4),
    onSurfaceVariant = Color(0xFF685D4E),
    surfaceContainerLowest = Color(0xFFFAF2E2),
    surfaceContainerLow = Color(0xFFF1E6D2),
    surfaceContainer = Color(0xFFECDFC9),
    surfaceContainerHigh = Color(0xFFE5D7C0),
    outline = Color(0xFF8A7B67),
    outlineVariant = Color(0xFFD3C3A8),
    error = Color(0xFFBA1A1A)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF9BA5),
    onPrimary = Color(0xFF571321),
    primaryContainer = Color(0xFF44232C),
    onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = Color(0xFFBAC2D3),
    onSecondary = Color(0xFF252C39),
    secondaryContainer = Color(0xFF2C323E),
    onSecondaryContainer = Color(0xFFE2E6F0),
    background = Color(0xFF101114),
    onBackground = Color(0xFFEEF0F5),
    surface = Color(0xFF1A1C22),
    onSurface = Color(0xFFEEF0F5),
    surfaceVariant = Color(0xFF292D36),
    onSurfaceVariant = Color(0xFFAFB5C3),
    surfaceContainerLowest = Color(0xFF0C0D10),
    surfaceContainerLow = Color(0xFF1A1C22),
    surfaceContainer = Color(0xFF21242C),
    surfaceContainerHigh = Color(0xFF2B2F39),
    surfaceContainerHighest = Color(0xFF343945),
    outline = Color(0xFF8992A4),
    outlineVariant = Color(0xFF303540),
    error = Color(0xFFFFB4AB)
)

private val ReaderTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 31.sp
    ),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp)
)

private val ReaderShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun MarxReaderTheme(theme: ReaderTheme, content: @Composable () -> Unit) {
    val dark = theme == ReaderTheme.DARK || (theme == ReaderTheme.SYSTEM && isSystemInDarkTheme())
    val colors = when {
        dark -> DarkScheme
        theme == ReaderTheme.SEPIA -> SepiaScheme
        else -> PaperScheme
    }
    val activity = LocalActivity.current
    SideEffect {
        activity?.window?.let { window ->
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = ReaderTypography,
        shapes = ReaderShapes,
        content = content
    )
}
