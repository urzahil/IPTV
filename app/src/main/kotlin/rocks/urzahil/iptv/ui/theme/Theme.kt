package rocks.urzahil.iptv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * The app is always dark: one Material 3 colour scheme, no dynamic colour, no light theme.
 * Screens must use MaterialTheme.colorScheme roles only (no hard-coded colours).
 * Keep `window_background` in res/values/colors.xml equal to [IptvDarkColors] background.
 */
val IptvDarkColors = darkColorScheme(
    primary = Color(0xFF7FD2F0),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF004E5C),
    onPrimaryContainer = Color(0xFFB6EBFF),
    inversePrimary = Color(0xFF00677A),
    secondary = Color(0xFFB2CBD3),
    onSecondary = Color(0xFF1D343B),
    secondaryContainer = Color(0xFF344A52),
    onSecondaryContainer = Color(0xFFCEE7EF),
    tertiary = Color(0xFFC5C2EA),
    onTertiary = Color(0xFF2E2D4D),
    tertiaryContainer = Color(0xFF454364),
    onTertiaryContainer = Color(0xFFE3DFFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1417),
    onBackground = Color(0xFFDEE3E6),
    surface = Color(0xFF0F1417),
    onSurface = Color(0xFFDEE3E6),
    surfaceVariant = Color(0xFF40484C),
    onSurfaceVariant = Color(0xFFBFC8CC),
    surfaceTint = Color(0xFF7FD2F0),
    inverseSurface = Color(0xFFDEE3E6),
    inverseOnSurface = Color(0xFF2B3134),
    outline = Color(0xFF8A9296),
    outlineVariant = Color(0xFF40484C),
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFF0F1417),
    surfaceBright = Color(0xFF353A3D),
    surfaceContainerLowest = Color(0xFF0A0F11),
    surfaceContainerLow = Color(0xFF171C1F),
    surfaceContainer = Color(0xFF1B2023),
    surfaceContainerHigh = Color(0xFF262B2D),
    surfaceContainerHighest = Color(0xFF303538),
)

private val IptvShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IptvDarkColors,
        typography = MaterialTheme.typography,
        shapes = IptvShapes,
        content = content,
    )
}
