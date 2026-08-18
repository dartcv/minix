package me.dartcv.minix.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import me.dartcv.minix.core.model.AccentOption
import me.dartcv.minix.core.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = PanelWhite,
    primaryContainer = SoftBlue,
    onPrimaryContainer = Ink,
    secondary = Mint,
    onSecondary = Ink,
    secondaryContainer = Color(0xFFD8F6E4),
    onSecondaryContainer = Ink,
    tertiary = Coral,
    onTertiary = Ink,
    background = MistBlue,
    onBackground = Ink,
    surface = PanelWhite,
    onSurface = Ink,
    surfaceVariant = Rail,
    onSurfaceVariant = Slate,
    outline = Color(0xFFB6C6D0),
)

private val DarkColors = darkColorScheme(
    primary = InkOnDark,
    onPrimary = Ink,
    primaryContainer = RailDark,
    onPrimaryContainer = InkOnDark,
    secondary = Mint,
    onSecondary = Ink,
    tertiary = Coral,
    onTertiary = Ink,
    background = MistBlueDark,
    onBackground = InkOnDark,
    surface = PanelDark,
    onSurface = InkOnDark,
    surfaceVariant = RailDark,
    onSurfaceVariant = Color(0xFFC8D6DF),
    outline = Color(0xFF4C6577),
)

@Composable
fun MinixTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accentOption: AccentOption = AccentOption.INK,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val accent = when (accentOption) {
        AccentOption.INK -> if (darkTheme) InkOnDark else Ink
        AccentOption.MINT -> Mint
        AccentOption.CORAL -> Coral
        AccentOption.SKY -> Color(0xFF4C9FC1)
    }
    val baseColors = if (darkTheme) DarkColors else LightColors
    val colors = baseColors.copy(
        primary = accent,
        onPrimary = when {
            darkTheme -> Ink
            accentOption == AccentOption.INK -> PanelWhite
            else -> Ink
        },
        secondary = if (accentOption == AccentOption.MINT) Coral else Mint,
    )

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
