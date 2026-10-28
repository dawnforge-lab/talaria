package lab.dawnforge.hermes.feature.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.ContactsContract
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lab.dawnforge.hermes.core.model.CardMedia
import lab.dawnforge.hermes.core.model.CardMediaKind
import lab.dawnforge.hermes.core.model.CardPerson
import lab.dawnforge.hermes.core.model.ContactCard
import lab.dawnforge.hermes.core.model.MediaCard
import okhttp3.OkHttpClient
import okhttp3.Request

// ---- Media ---------------------------------------------------------------------------------

@Composable
internal fun MediaView(card: MediaCard) {
    val context = LocalContext.current
    val visual = card.media.filter { it.kind != CardMediaKind.Audio }
    if (visual.size == 1) {
        VisualTile(visual.first(), Modifier.fillMaxWidth().height(190.dp)) { openMedia(context, it) }
    } else if (visual.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            visual.forEach { item ->
                VisualTile(item, Modifier.width(220.dp).height(150.dp)) { openMedia(context, it) }
            }
        }
    }
    visual.forEach { item ->
        listOfNotNull(item.title, item.by, item.caption).takeIf { it.isNotEmpty() && visual.size == 1 }?.let {
            Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
    }
    card.media.filter { it.kind == CardMediaKind.Audio }.forEach { AudioRow(it) }
}

@Composable
private fun VisualTile(item: CardMedia, modifier: Modifier, onOpen: (CardMedia) -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).clickable {
            onOpen(item)
        },
    ) {
        val still = if (item.kind == CardMediaKind.Image) item.url else item.artwork
        if (still != null) WebImage(still, item.title, Modifier.matchParentSize())
        if (item.kind == CardMediaKind.Video) {
            Text(
                "▶" + (item.durationSeconds?.let { " " + duration(it) }.orEmpty()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(
                    Alignment.Center,
                ).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** Opens an image or a video in an app that shows it, or its page. */
private fun openMedia(context: Context, item: CardMedia) {
    val type = if (item.kind == CardMediaKind.Video) "video/*" else "image/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.url), type)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        HostFiles.browse(context, item.page ?: item.url)
    }
}

/** One audio item plays at a time, across every card. */
private object CardAudio {
    var player: MediaPlayer? = null
    var playing: String? = null

    fun stop() {
        player?.release()
        player = null
        playing = null
    }
}

@Composable
private fun AudioRow(item: CardMedia) {
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(item.url) {
        onDispose { if (CardAudio.playing == item.url) CardAudio.stop() }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        item.artwork?.let { WebImage(it, item.title, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))) }
            ?: Text("🎵", style = MaterialTheme.typography.headlineSmall)
        Column(Modifier.weight(1f)) {
            Text(
                item.title ?: item.url.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            listOfNotNull(item.by, item.durationSeconds?.let(::duration)).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            if (failed) {
                Text(
                    stringResource(R.string.feature_chat_card_audio_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        TextButton(
            onClick = {
                if (playing) {
                    CardAudio.stop()
                    playing = false
                } else {
                    CardAudio.stop()
                    failed = false
                    CardAudio.playing = item.url
                    CardAudio.player = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                        )
                        setOnPreparedListener { start() }
                        setOnCompletionListener {
                            CardAudio.stop()
                            playing = false
                        }
                        setOnErrorListener { _, _, _ ->
                            CardAudio.stop()
                            playing = false
                            failed = true
                            true
                        }
                        try {
                            setDataSource(item.url)
                            prepareAsync()
                        } catch (_: IOException) {
                            failed = true
                        }
                    }
                    playing = !failed
                }
            },
        ) { Text(if (playing) "❚❚" else "▶") }
    }
}

private fun duration(seconds: Int): String =
    "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)

// ---- Contact -------------------------------------------------------------------------------

@Composable
internal fun ContactView(card: ContactCard) {
    card.people.forEach { PersonRow(it) }
}

@Composable
private fun PersonRow(person: CardPerson) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val photo = person.photo
            if (photo != null) {
                WebImage(photo.url, person.name, Modifier.size(44.dp).clip(CircleShape))
            } else {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        initials(person.name),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(person.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                person.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        listOfNotNull(person.phone, person.email, person.address).forEach {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        person.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            person.phone?.let { phone ->
                val dial = dialable(phone)
                AssistChip(onClick = { start(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dial"))) }, label = {
                    Text(stringResource(R.string.feature_chat_card_call))
                })
                AssistChip(onClick = {
                    start(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$dial")))
                }, label = {
                    Text(stringResource(R.string.feature_chat_card_message))
                })
            }
            person.email?.let { email ->
                AssistChip(onClick = {
                    start(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(email))))
                }, label = {
                    Text(stringResource(R.string.feature_chat_card_email))
                })
            }
            person.url?.let { url ->
                AssistChip(onClick = {
                    HostFiles.browse(context, url)
                }, label = { Text(stringResource(R.string.feature_chat_card_website)) })
            }
            if (person.phone != null || person.email != null) {
                AssistChip(onClick = {
                    start(context, addContact(person))
                }, label = { Text(stringResource(R.string.feature_chat_card_add_contact)) })
            }
        }
    }
}

private fun initials(name: String): String =
    name.split(' ').filter(String::isNotBlank).take(2).joinToString("") { it.first().uppercase() }

/** Digits, with a leading + when the number had one before its first digit. */
private fun dialable(phone: String): String {
    val digits = phone.filter(Char::isDigit)
    val plus = phone.indexOf('+').let { it >= 0 && it < phone.indexOfFirst(Char::isDigit) }
    return if (plus) "+$digits" else digits
}

private fun addContact(person: CardPerson): Intent = Intent(Intent.ACTION_INSERT).apply {
    type = ContactsContract.Contacts.CONTENT_TYPE
    putExtra(ContactsContract.Intents.Insert.NAME, person.name)
    person.detail?.let { putExtra(ContactsContract.Intents.Insert.JOB_TITLE, it) }
    person.phone?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
    person.email?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
}

private fun start(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        android.widget.Toast.makeText(
            context,
            R.string.feature_chat_file_no_app,
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}

// ---- Web images ----------------------------------------------------------------------------

/** Pictures from the web (https only, as the card schema allows), kept a while and scaled down. */
private object WebImages {
    val client = OkHttpClient()
    val cache = LruCache<String, ImageBitmap>(WEB_IMAGES)

    suspend fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val bytes = response.body.bytes().takeIf { it.size <= MAX_IMAGE_BYTES } ?: return@withContext null
                    decode(bytes)?.also { cache.put(url, it) }
                }
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_PX) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
            },
        )?.asImageBitmap()
    }
}

@Composable
internal fun WebImage(url: String, description: String?, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(WebImages.cache.get(url), url) {
        if (value ==
            null
        ) {
            value = WebImages.load(url)
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surface)) {
        image?.let { Image(it, description, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
    }
}

private const val SECONDS_PER_MINUTE = 60
private const val WEB_IMAGES = 60
private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
private const val MAX_IMAGE_PX = 2048
