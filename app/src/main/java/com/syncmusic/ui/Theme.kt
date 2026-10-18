package com.syncmusic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.syncmusic.R

/** Two phones, two colours: coral = the phone sharing, sky = the phone listening. */
object Palette {
    val Night = Color(0xFF10142A)
    val Deep = Color(0xFF1A2040)
    val Raised = Color(0xFF232A52)
    val Line = Color(0xFF2E3664)
    val Coral = Color(0xFFFF7E8B)
    val Sky = Color(0xFF8FD3FF)
    val Mist = Color(0xFFA9AFD3)
    val Cloud = Color(0xFFF1F2FA)
}

val Bricolage = FontFamily(
    Font(R.font.bricolage_500, FontWeight.Medium),
    Font(R.font.bricolage_700, FontWeight.Bold),
    Font(R.font.bricolage_800, FontWeight.ExtraBold),
)

private val base = Typography()
private val type = Typography(
    displaySmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.8).sp),
    headlineSmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
)

@Composable
fun SyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Coral, onPrimary = Palette.Night,
            secondary = Palette.Sky, onSecondary = Palette.Night,
            background = Palette.Night, onBackground = Palette.Cloud,
            surface = Palette.Night, onSurface = Palette.Cloud,
            surfaceVariant = Palette.Deep, onSurfaceVariant = Palette.Mist,
            surfaceContainer = Palette.Deep, surfaceContainerHigh = Palette.Raised,
            surfaceContainerHighest = Palette.Raised, surfaceContainerLow = Palette.Deep,
            outline = Palette.Line, outlineVariant = Palette.Line,
            secondaryContainer = Palette.Raised, onSecondaryContainer = Palette.Cloud,
        ),
        typography = type,
        content = content,
    )
}
