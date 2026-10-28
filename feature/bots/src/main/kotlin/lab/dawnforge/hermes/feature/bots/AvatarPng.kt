package lab.dawnforge.hermes.feature.bots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/** A picture for a bot or profile, as iOS sends it: cropped square from the middle, 256 px, PNG. */
internal object AvatarPng {
    private const val AVATAR_PX = 256
    private const val PNG_QUALITY = 100

    /** The picture at [uri], or null when it can't be read. Reads and encodes: call it off the main thread. */
    fun from(context: Context, uri: Uri): ByteArray? {
        val source = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) ?: return null
        val side = minOf(source.width, source.height)
        val cropped = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, AVATAR_PX, AVATAR_PX, true)
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            out.toByteArray()
        }
    }
}
