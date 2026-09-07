package org.marxreader.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.marxreader.app.data.ReaderTheme

val Wine = Color(0xFF792F38)
val WineDark = Color(0xFF4D2026)
val Gold = Color(0xFF9B6B22)
val Ink = Color(0xFF292522)
val Paper = Color(0xFFFAF7F0)
val Sepia = Color(0xFFF4EBD8)

private val PaperScheme = lightColorScheme(
    primary = Wine,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF0DDE0),
    onPrimaryContainer = WineDark,
    secondary = Gold,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E5CC),
    onSecondaryContainer = Color(0xFF332000),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF3EDE4),
    onSurfaceVariant = Color(0xFF6A625A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2EDE3),
    surfaceContainer = Color(0xFFEDE6DA),
    surfaceContainerHigh = Color(0xFFF1EAE0),
    outline = Color(0xFF8D8278),
    outlineVariant = Color(0xFFDED5C8),
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
    primary = Color(0xFFFFB1BA),
    onPrimary = Color(0xFF580914),
    primaryContainer = Color(0xFF711E28),
    onPrimaryContainer = Color(0xFFF0DDE0),
    secondary = Color(0xFFE6C17B),
    onSecondary = Color(0xFF402D00),
    secondaryContainer = Color(0xFF594000),
    onSecondaryContainer = Color(0xFFFFDEA1),
    background = Color(0xFF171412),
    onBackground = Color(0xFFECE2DA),
    surface = Color(0xFF171412),
    onSurface = Color(0xFFECE2DA),
    surfaceVariant = Color(0xFF332D29),
    onSurfaceVariant = Color(0xFFCFC3BA),
    surfaceContainerLowest = Color(0xFF110F0D),
    surfaceContainerLow = Color(0xFF1F1B18),
    surfaceContainer = Color(0xFF24201D),
    surfaceContainerHigh = Color(0xFF2F2A26),
    outline = Color(0xFF9C9087),
    outlineVariant = Color(0xFF4C443F),
    error = Color(0xFFFFB4AB)
)

private val ReaderTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 39.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
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
    MaterialTheme(
        colorScheme = colors,
        typography = ReaderTypography,
        shapes = ReaderShapes,
        content = content
    )
}
