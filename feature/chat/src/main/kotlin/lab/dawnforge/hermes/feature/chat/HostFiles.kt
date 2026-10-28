package lab.dawnforge.hermes.feature.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Files fetched from the host, handed to other apps through the file provider (`media/` in the cache). */
internal object HostFiles {
    /** A fresh place in the cache for a file called [name]: its own folder, made when the file is written. */
    fun cacheFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, "media/${UUID.randomUUID()}")
        return File(dir, name.replace('/', '_').replace('\\', '_').ifBlank { "file" })
    }

    /** The type other apps know [name] by, from its extension. */
    fun mimeType(name: String, fallback: String? = null): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: fallback?.takeIf(String::isNotBlank)
            ?: "application/octet-stream"
    }

    fun open(context: Context, file: File, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.feature_chat_file_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, file: File, mimeType: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null))
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(intent, null))
    }

    /** Opens a web link in the browser. */
    fun browse(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.feature_chat_file_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(text, text))
    }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
