package lab.dawnforge.hermes.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import lab.dawnforge.hermes.core.model.AccentChoice
import lab.dawnforge.hermes.core.model.ThemeChoice

/**
 * The app theme: light, dark or the system's, in the wallpaper's colours (Android 12 and later) or
 * one of the iOS app's accents. Before Android 12 the wallpaper choice is Material 3's own scheme.
 */
@Composable
fun HermesTheme(
    theme: ThemeChoice = ThemeChoice.System,
    accent: AccentChoice = AccentChoice.Wallpaper,
    content: @Composable () -> Unit,
) {
    val dark = when (theme) {
        ThemeChoice.System -> isSystemInDarkTheme()
        ThemeChoice.Light -> false
        ThemeChoice.Dark -> true
    }
    val swatch = SWATCHES[accent]
    val colorScheme: ColorScheme = when {
        swatch != null -> if (dark) swatch.darkScheme() else swatch.lightScheme()

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        dark -> darkColorScheme()

        else -> lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

/** [accent]'s colour on a light background, to show the choice; null for the wallpaper's. */
fun accentSwatch(accent: AccentChoice): Color? = SWATCHES[accent]?.onLight

/** An accent's colour on a dark background, and on a light one (the iOS app's values). */
private class Swatch(val onDark: Color, val onLight: Color) {
    fun lightScheme() = lightColorScheme(
        primary = onLight,
        onPrimary = Color.White,
        primaryContainer = lerp(onLight, Color.White, 0.82f),
        onPrimaryContainer = lerp(onLight, Color.Black, 0.55f),
        secondary = lerp(onLight, Color.Gray, 0.35f),
        secondaryContainer = lerp(onLight, Color.White, 0.88f),
        onSecondaryContainer = lerp(onLight, Color.Black, 0.6f),
        inversePrimary = onDark,
    )

    fun darkScheme() = darkColorScheme(
        primary = onDark,
        onPrimary = lerp(onDark, Color.Black, 0.78f),
        primaryContainer = lerp(onDark, Color.Black, 0.62f),
        onPrimaryContainer = lerp(onDark, Color.White, 0.75f),
        secondary = lerp(onDark, Color.Gray, 0.35f),
        secondaryContainer = lerp(onDark, Color.Black, 0.7f),
        onSecondaryContainer = lerp(onDark, Color.White, 0.8f),
        inversePrimary = onLight,
    )
}

private fun rgb(red: Float, green: Float, blue: Float) = Color(red, green, blue)

private val SWATCHES = mapOf(
    AccentChoice.Amber to Swatch(rgb(0.88f, 0.67f, 0.28f), rgb(0.55f, 0.37f, 0.13f)),
    AccentChoice.Blue to Swatch(rgb(0.33f, 0.58f, 0.98f), rgb(0.12f, 0.36f, 0.80f)),
    AccentChoice.Green to Swatch(rgb(0.30f, 0.72f, 0.47f), rgb(0.09f, 0.47f, 0.27f)),
    AccentChoice.Teal to Swatch(rgb(0.22f, 0.70f, 0.75f), rgb(0.04f, 0.45f, 0.51f)),
    AccentChoice.Purple to Swatch(rgb(0.62f, 0.48f, 0.96f), rgb(0.43f, 0.28f, 0.80f)),
    AccentChoice.Pink to Swatch(rgb(0.93f, 0.42f, 0.64f), rgb(0.76f, 0.19f, 0.44f)),
)
