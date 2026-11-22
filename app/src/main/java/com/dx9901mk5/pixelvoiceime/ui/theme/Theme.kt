package com.dx9901mk5.pixelvoiceime.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.dx9901mk5.pixelvoiceime.data.AppThemeMode

private val PixelLightColors = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF00639B),
    secondaryContainer = Color(0xFFC2E7FF),
    surface = Color(0xFFF8F9FA),
    surfaceVariant = Color(0xFFE8EEF7),
    background = Color(0xFFF8F9FA)
)

private val PixelDarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF7FCFFF),
    secondaryContainer = Color(0xFF004A77),
    surface = Color(0xFF131314),
    surfaceVariant = Color(0xFF282A2C),
    background = Color(0xFF131314)
)

private val OrangeCharcoalColors = darkColorScheme(
    primary = Color(0xFFFF7A00),
    onPrimary = Color(0xFF1A0B00),
    primaryContainer = Color(0xFF4D2600),
    onPrimaryContainer = Color(0xFFFFD8B8),
    secondary = Color(0xFFFFA94D),
    onSecondary = Color(0xFF1F1200),
    secondaryContainer = Color(0xFF382412),
    onSecondaryContainer = Color(0xFFFFE5C9),
    surface = Color(0xFF18191D),
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF26282E),
    onSurfaceVariant = Color(0xFFB8BCC6),
    background = Color(0xFF111215),
    onBackground = Color(0xFFF3F4F6),
    outline = Color(0xFF545863)
)

@Composable
fun PixelVoiceTheme(
    themeMode: AppThemeMode = AppThemeMode.DYNAMIC_PIXEL,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        themeMode == AppThemeMode.ORANGE_CHARCOAL -> OrangeCharcoalColors
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> PixelDarkColors
        else -> PixelLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
