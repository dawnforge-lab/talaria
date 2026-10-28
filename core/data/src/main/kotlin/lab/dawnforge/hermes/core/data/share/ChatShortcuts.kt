package lab.dawnforge.hermes.core.data.share

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import lab.dawnforge.hermes.core.data.R
import lab.dawnforge.hermes.core.data.bots.BotColor
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.SessionSummary

/**
 * The launcher shortcuts: New chat and Talk, then the recent chats. The chats are sharing shortcuts
 * too, so they show in the share sheet (Direct Share); tapped from the launcher they open the chat
 * through its `talaria://session/…` link. Each publish also tells the home-screen widget the chats
 * changed.
 */
class ChatShortcuts(private val context: Context) {
    /** Publishes New chat, Talk and the profile's pinned, then most recent, chats (as many as the launcher shows). */
    fun publish(dashboard: DashboardId, profile: String, chats: List<SessionSummary>) {
        val max = minOf(MAX_SHORTCUTS, ShortcutManagerCompat.getMaxShortcutCountPerActivity(context))
        val actions = listOf(
            action(NEW_CHAT, R.string.core_data_shortcut_new_chat, R.drawable.core_data_shortcut_new_chat, "newChat"),
            action(TALK, R.string.core_data_shortcut_talk, R.drawable.core_data_shortcut_talk, "talk"),
        )
        val shortcuts = actions + chats.asSequence()
            .filter { !it.archived && it.source != "cron" }
            .sortedWith(compareByDescending<SessionSummary> { it.pinned }.thenByDescending { it.lastActiveAtMs ?: 0L })
            .take(maxOf(0, max - actions.size))
            .mapIndexed { index, chat ->
                val rank = actions.size + index
                val title = chat.displayTitle.take(TITLE_LENGTH)
                ShortcutInfoCompat.Builder(context, id(dashboard.value, profile, chat.id))
                    .setShortLabel(title)
                    .setLongLabel(title)
                    .setIcon(icon(title))
                    .setIntent(
                        Intent(Intent.ACTION_VIEW, Uri.parse(link(dashboard.value, profile, chat.id)))
                            .setPackage(context.packageName),
                    )
                    .setCategories(setOf(SHARE_CATEGORY))
                    .setLongLived(true)
                    .setRank(rank)
                    .build()
            }
            .toList()
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
        context.sendBroadcast(Intent(ACTION_CHATS_CHANGED).setPackage(context.packageName))
    }

    /** New chat or Talk, started through the app's launcher entry point (which other apps can't reach). */
    private fun action(id: String, label: Int, icon: Int, path: String): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(context.getString(label))
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(
                Intent(Intent.ACTION_VIEW, Uri.parse("talaria://widget/v1/$path"))
                    .setComponent(ComponentName(context.packageName, LAUNCHER_ACTIVITY)),
            )
            .setRank(if (id == NEW_CHAT) 0 else 1)
            .build()

    /** The chat's initial on its colour, as the bots' faces are drawn. */
    private fun icon(title: String): IconCompat {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(OPAQUE or BotColor.fallback(title))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WHITE
            textSize = ICON_PX * LETTER_SCALE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val letter = title.trim().take(1).uppercase().ifEmpty { "#" }
        canvas.drawText(letter, ICON_PX / 2f, ICON_PX / 2f - (paint.descent() + paint.ascent()) / 2, paint)
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    /** A chosen Direct Share chat. */
    data class Chat(val dashboard: String, val profile: String, val sessionId: String)

    companion object {
        /** The category the share target in `res/xml/shortcuts.xml` names. */
        const val SHARE_CATEGORY = "lab.dawnforge.hermes.category.SHARE_TO_CHAT"

        /** Sent to the app itself when the chats were published: the widget reads them again. */
        const val ACTION_CHATS_CHANGED = "lab.dawnforge.hermes.CHATS_CHANGED"
        private const val LAUNCHER_ACTIVITY = "lab.dawnforge.hermes.launch.LauncherActivity"
        private const val NEW_CHAT = "new_chat"
        private const val TALK = "talk"
        private const val MAX_SHORTCUTS = 5
        private const val TITLE_LENGTH = 40
        private const val ICON_PX = 216
        private const val LETTER_SCALE = 0.36f
        private const val OPAQUE = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val SEPARATOR = '|'

        fun id(dashboard: String, profile: String, sessionId: String) =
            "chat$SEPARATOR$dashboard$SEPARATOR$profile$SEPARATOR$sessionId"

        fun parse(id: String): Chat? {
            val parts = id.split(SEPARATOR)
            if (parts.size != PARTS || parts[0] != "chat" || parts.drop(1).any(String::isEmpty)) return null
            return Chat(parts[1], parts[2], parts[3])
        }

        /** The chat's link, as iOS writes them. */
        fun link(dashboard: String, profile: String, sessionId: String) = "talaria://session/${Uri.encode(
            sessionId,
        )}?profile=${Uri.encode(profile)}&dashboard=${Uri.encode(dashboard)}"

        private const val PARTS = 4
    }
}
