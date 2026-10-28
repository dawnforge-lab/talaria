package lab.dawnforge.hermes.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * A profile's glow behind a top bar, as iOS tints its backdrop with it: the profile's colour
 * ([rgb], `0xRRGGBB`) fading out downwards, or the app's accent for a profile without one.
 */
@Composable
fun profileGlowBrush(rgb: Int?): Brush {
    val color = rgb?.let { Color(OPAQUE or it) } ?: MaterialTheme.colorScheme.primary
    return Brush.verticalGradient(listOf(color.copy(alpha = GLOW_ALPHA), Color.Transparent))
}

private const val OPAQUE = 0xFF000000.toInt()
private const val GLOW_ALPHA = 0.28f
