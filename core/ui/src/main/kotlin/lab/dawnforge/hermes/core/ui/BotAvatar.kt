package lab.dawnforge.hermes.core.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A bot's picture, or (without one) its colour with the first letter of its name, as Hermes Desktop
 * and iOS draw it.
 */
@Composable
fun BotAvatar(label: String, color: Int, picture: ByteArray?, size: Dp = 40.dp) {
    val bitmap = remember(picture) {
        picture?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    if (bitmap != null) {
        Image(
            bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
        return
    }
    Box(
        Modifier.size(size).background(Color(OPAQUE or color), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.trim().take(1).uppercase(),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (maxOf(MIN_LETTER, size.value * LETTER_SCALE)).sp,
        )
    }
}

private const val OPAQUE = 0xFF000000.toInt()
private const val MIN_LETTER = 11f
private const val LETTER_SCALE = 0.42f
