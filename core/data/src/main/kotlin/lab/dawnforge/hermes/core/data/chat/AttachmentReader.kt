package lab.dawnforge.hermes.core.data.chat

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A photo or file the user picked, read and ready to upload. */
class PickedFile(val name: String, val mimeType: String, val bytes: ByteArray) {
    /** An image Hermes can show the model as one (PNG, JPEG, GIF, WebP, BMP). */
    val isImage: Boolean get() = mimeType in IMAGE_TYPES

    internal companion object {
        val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp")
    }
}

sealed interface PickFailure {
    /** Larger than the app sends ([limitBytes]). */
    data class TooLarge(val limitBytes: Long) : PickFailure

    /** The picker's file could not be read. */
    data object Unreadable : PickFailure
}

/**
 * Reads what the system pickers return. A photo larger than a model needs (phone cameras take
 * 12 to 50 megapixels) is scaled down to [MAX_IMAGE_SIDE] and sent as a JPEG, which keeps an upload
 * over a phone link to a few hundred kilobytes; GIFs are sent as they are, to keep them moving.
 */
class AttachmentReader(private val resolver: ContentResolver) {
    /** Reads the file at [uri], refusing one larger than [limitBytes] (the app's attachment limit). */
    suspend fun read(uri: Uri, limitBytes: Long = MAX_FILE_BYTES): Result = withContext(Dispatchers.IO) {
        try {
            val (name, size) = describe(uri)
            if (size != null && size > limitBytes) return@withContext Result.Failed(PickFailure.TooLarge(limitBytes))
            val mime = resolver.getType(uri) ?: guessType(name)
            // At most one byte past the limit, so a file whose size the picker didn't say is never read whole.
            val bytes = resolver.openInputStream(uri)?.use { it.readNBytesCompat(limitBytes + 1) }
                ?: return@withContext Result.Failed(PickFailure.Unreadable)
            if (bytes.size > limitBytes) return@withContext Result.Failed(PickFailure.TooLarge(limitBytes))
            val picked = PickedFile(name, mime, bytes)
            Result.Read(if (picked.isImage && mime != "image/gif") shrink(uri, picked) else picked)
        } catch (_: IOException) {
            Result.Failed(PickFailure.Unreadable)
        } catch (_: SecurityException) {
            Result.Failed(PickFailure.Unreadable)
        }
    }

    sealed interface Result {
        data class Read(val file: PickedFile) : Result

        data class Failed(val failure: PickFailure) : Result
    }

    private fun describe(uri: Uri): Pair<String, Long?> {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                        ?.let(cursor::getString)
                    val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let(cursor::getLong)
                    return (name ?: fallbackName(uri)) to size
                }
            }
        return fallbackName(uri) to null
    }

    /** The photo at no more than [MAX_IMAGE_SIDE] on its longer side, the right way up, as a JPEG. */
    private fun shrink(uri: Uri, picked: PickedFile): PickedFile {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(picked.bytes, 0, picked.bytes.size, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest in 1..MAX_IMAGE_SIDE && picked.bytes.size <= SMALL_IMAGE_BYTES) return picked
        val bitmap = decodeScaled(uri, picked, longest) ?: return picked
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        return PickedFile(picked.name.substringBeforeLast('.') + ".jpg", "image/jpeg", out.toByteArray())
    }

    private fun decodeScaled(uri: Uri, picked: PickedFile, longest: Int): Bitmap? {
        val scale = if (longest > MAX_IMAGE_SIDE) MAX_IMAGE_SIDE.toFloat() / longest else 1f
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the photo's EXIF rotation.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            var sample = 1
            while (longest / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
            BitmapFactory.decodeByteArray(
                picked.bytes,
                0,
                picked.bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                },
            )
        }
    }

    private fun fallbackName(uri: Uri) = uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
        ?: "attachment"

    private fun guessType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "txt", "md", "log" -> "text/plain"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    companion object {
        /** The longest side a photo is sent at: plenty for a vision model. */
        const val MAX_IMAGE_SIDE = 2048

        /** A file is read into memory and sent as one socket frame; past this it is refused. */
        const val MAX_FILE_BYTES = 50L * 1024 * 1024

        /** A photo already this small and this size is sent as it is. */
        private const val SMALL_IMAGE_BYTES = 1_500_000
        private const val JPEG_QUALITY = 85
    }
}

/** Up to [limit] bytes of the stream (`InputStream.readNBytes` needs Android 13). */
private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var left = limit
    while (left > 0) {
        val read = read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
        if (read < 0) break
        out.write(buffer, 0, read)
        left -= read
    }
    return out.toByteArray()
}
