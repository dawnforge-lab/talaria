package lab.dawnforge.hermes.feature.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lab.dawnforge.hermes.core.chat.MediaKind
import lab.dawnforge.hermes.core.chat.ReplyPart
import lab.dawnforge.hermes.core.chat.SettingsLink
import lab.dawnforge.hermes.core.chat.linkSessions
import lab.dawnforge.hermes.core.chat.mediaKind
import lab.dawnforge.hermes.core.chat.parseSessionLink
import lab.dawnforge.hermes.core.chat.splitReply
import lab.dawnforge.hermes.core.data.chat.MediaFile

/** How the transcript reaches files on the Hermes host, and opens the chats replies link to. */
internal class MediaActions(
    /** The file at a host path; for a sent picture whose file is gone, the copy in its prompt row. */
    val load: suspend (path: String, rowId: Long?, index: Int) -> MediaFile?,
    val openChat: (profile: String?, sessionId: String) -> Unit,
    /** A Settings page Hermes linked to (`talaria://settings/<page>`). */
    val openSettings: (SettingsLink) -> Unit = {},
)

internal val LocalMediaActions = staticCompositionLocalOf<MediaActions?> { null }

/**
 * A finished reply: its Markdown, with each file it names (`MEDIA:`) shown in its place, and its
 * links to other chats and to Settings pages opening them here. A reply still being written is Markdown only, so no
 * half-written path is fetched.
 */
@Composable
internal fun ReplyBody(text: String, streaming: Boolean) {
    val media = LocalMediaActions.current
    val openChatLabel = stringResource(R.string.feature_chat_open_chat)
    if (streaming || media == null) {
        ChatMarkdown(text, streaming = streaming)
        return
    }
    val parts = remember(text, openChatLabel) { splitReply(linkSessions(text, openChatLabel)) }
    val system = LocalUriHandler.current
    val handler = remember(system, media) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val chat = parseSessionLink(uri)
                val page = SettingsLink.parse(uri)
                when {
                    chat != null -> media.openChat(chat.first, chat.second)
                    page != null -> media.openSettings(page)
                    else -> system.openUri(uri)
                }
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides handler) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            parts.forEach { part ->
                when (part) {
                    is ReplyPart.Markdown -> ChatMarkdown(part.text, streaming = false)
                    is ReplyPart.Media -> HostFile(part.path, part.label, part.kind)
                    is ReplyPart.Math -> MarkupBlock(MarkupKind.Math, part.source)
                    is ReplyPart.Diagram -> MarkupBlock(MarkupKind.Diagram, part.source)
                    is ReplyPart.Page -> PagePreview(part.path)
                }
            }
        }
    }
}

/** A file on the Hermes host, drawn by its kind: a picture inline, audio as a player, else a file card. */
@Composable
internal fun HostFile(path: String, label: String?, kind: MediaKind = mediaKind(path)) {
    when (kind) {
        MediaKind.Image -> HostImage(path, rowId = null, index = 0, label = label)
        MediaKind.Audio -> AudioRow(path, label)
        else -> ReplyFileCard(path, label, kind)
    }
}

/** A picture on the Hermes host, fitted to the column; a tap shows it whole. */
@Composable
internal fun HostImage(path: String, rowId: Long?, index: Int, label: String?, modifier: Modifier = Modifier) {
    val media = LocalMediaActions.current ?: return
    var viewing by remember { mutableStateOf(false) }
    val loaded by produceState<LoadedImage>(LoadedImage.Loading, path, rowId, index) {
        val file = media.load(path, rowId, index)
        value = file?.let { withContext(Dispatchers.Default) { decode(it.bytes, MAX_INLINE_PX) } }
            ?.let { LoadedImage.Shown(it, file) } ?: LoadedImage.Failed
    }
    val shape = RoundedCornerShape(12.dp)
    when (val image = loaded) {
        LoadedImage.Loading -> Box(
            modifier.size(INLINE_PLACEHOLDER).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }

        LoadedImage.Failed -> Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = modifier,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.feature_chat_image_unavailable, label ?: fileName(path)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }

        is LoadedImage.Shown -> {
            Image(
                image.bitmap,
                contentDescription = label ?: fileName(path),
                contentScale = ContentScale.Fit,
                modifier = modifier
                    .widthIn(max = INLINE_MAX_WIDTH)
                    .heightIn(max = INLINE_MAX_HEIGHT)
                    .clip(shape)
                    .clickable { viewing = true },
            )
            if (viewing) ImageViewer(image.file, fileName(path), onClose = { viewing = false })
        }
    }
}

private sealed interface LoadedImage {
    data object Loading : LoadedImage

    data object Failed : LoadedImage

    class Shown(val bitmap: ImageBitmap, val file: MediaFile) : LoadedImage
}

/** A picture on the whole screen: pinch to zoom, and share it to another app. */
@Composable
private fun ImageViewer(file: MediaFile, name: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val full by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.Default) { decode(file.bytes, MAX_FULL_PX) }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            full?.let { bitmap ->
                Image(
                    bitmap,
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                IconButton(onClick = { share(context, file, name) }) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = stringResource(R.string.feature_chat_share),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.feature_chat_close_image),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

/** An audio file the agent made (a spoken reply, say): play it here. One plays at a time. */
@Composable
private fun AudioRow(path: String, label: String?) {
    val media = LocalMediaActions.current ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    LaunchedEffect(playing) {
        while (playing) {
            player?.let { if (it.duration > 0) progress = it.currentPosition.toFloat() / it.duration }
            delay(PROGRESS_EVERY_MS)
        }
    }
    OutlinedCard(Modifier.widthIn(max = INLINE_MAX_WIDTH)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                enabled = !loading,
                onClick = {
                    val current = player
                    when {
                        current != null && playing -> {
                            current.pause()
                            playing = false
                        }

                        current != null -> {
                            NowPlaying.take(current)
                            current.start()
                            playing = true
                        }

                        else -> scope.launch {
                            loading = true
                            val file = media.load(path, null, 0)
                            val local = file?.let {
                                withContext(Dispatchers.IO) { saveToCache(context, it, fileName(path)) }
                            }
                            loading = false
                            if (local == null) {
                                failed = true
                                return@launch
                            }
                            runCatching {
                                MediaPlayer().apply {
                                    setDataSource(local.path)
                                    setOnCompletionListener {
                                        playing = false
                                        progress = 0f
                                    }
                                    prepare()
                                }
                            }.onSuccess {
                                player = it
                                NowPlaying.take(it)
                                it.start()
                                playing = true
                            }.onFailure { failed = true }
                        }
                    }
                },
            ) {
                when {
                    loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)

                    playing -> Box(
                        Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                    )

                    else -> Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.feature_chat_play),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label ?: fileName(path),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (failed) {
                    Text(
                        stringResource(R.string.feature_chat_audio_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    LinearProgressIndicator(progress = {
                        progress
                    }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
        }
    }
}

/** The one audio clip playing: starting another pauses it. */
private object NowPlaying {
    private var current: MediaPlayer? = null

    fun take(player: MediaPlayer) {
        current?.takeIf { it != player }?.let { runCatching { if (it.isPlaying) it.pause() } }
        current = player
    }
}

/** A file the agent made that the chat doesn't show itself: a tap opens it in another app. */
@Composable
internal fun ReplyFileCard(path: String, label: String?, kind: MediaKind) {
    val media = LocalMediaActions.current ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    OutlinedCard(
        onClick = {
            if (opening) return@OutlinedCard
            opening = true
            scope.launch {
                val file = media.load(path, null, 0)
                opening = false
                if (file == null) {
                    Toast.makeText(context, R.string.feature_chat_file_unavailable, Toast.LENGTH_SHORT).show()
                } else {
                    open(context, file, fileName(path))
                }
            }
        },
        modifier = Modifier.widthIn(max = INLINE_MAX_WIDTH),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (opening) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Info, contentDescription = null)
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    label ?: fileName(path),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        when (kind) {
                            MediaKind.Video -> R.string.feature_chat_file_video
                            MediaKind.Page -> R.string.feature_chat_file_page
                            else -> R.string.feature_chat_file
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A decoded picture no larger than [maxPx] on its long side. */
private fun decode(bytes: ByteArray, maxPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

/** Writes [file] under the app's cache (`media/`), where the file provider hands it to other apps. */
private fun saveToCache(context: Context, file: MediaFile, name: String): File? = runCatching {
    val dir = File(context.cacheDir, "media").apply { mkdirs() }
    File(dir, name.replace('/', '_')).apply { writeBytes(file.bytes) }
}.getOrNull()

private fun uriFor(context: Context, local: File) =
    FileProvider.getUriForFile(context, "${context.packageName}.files", local)

private fun open(context: Context, file: MediaFile, name: String) {
    val local = saveToCache(context, file, name) ?: return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uriFor(context, local), file.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.feature_chat_file_no_app, Toast.LENGTH_SHORT).show()
    }
}

private fun share(context: Context, file: MediaFile, name: String) {
    val local = saveToCache(context, file, name) ?: return
    val intent = Intent(Intent.ACTION_SEND)
        .setType(file.mimeType)
        .putExtra(Intent.EXTRA_STREAM, uriFor(context, local))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null))
}

private fun fileName(path: String) = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

private val INLINE_MAX_WIDTH = 320.dp
private val INLINE_MAX_HEIGHT = 360.dp
private val INLINE_PLACEHOLDER = 120.dp
private const val MAX_INLINE_PX = 1024
private const val MAX_FULL_PX = 2048
private const val MAX_ZOOM = 5f
private const val PROGRESS_EVERY_MS = 250L
