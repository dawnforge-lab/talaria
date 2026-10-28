package lab.dawnforge.hermes.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.cache.ChatCache
import lab.dawnforge.hermes.core.data.chat.AttachmentReader
import lab.dawnforge.hermes.core.data.chat.PickFailure
import lab.dawnforge.hermes.core.data.chat.PickedFile
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.data.share.ChatShortcuts
import lab.dawnforge.hermes.core.data.share.SharedDraft
import lab.dawnforge.hermes.core.data.share.SharedDrafts
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.SessionSummary

/** A dashboard to share to, with the profile the app shows for it. */
data class ShareWorkspace(val dashboard: DashboardId, val label: String, val profile: String)

/** Where the share goes: a new chat, or one of the workspace's chats. */
data class ShareTarget(val sessionId: String?, val title: String?)

/** Something shared that was left out, and why. */
sealed interface LeftOut {
    val name: String

    data class TooLarge(override val name: String, val limitBytes: Long) : LeftOut

    data class Unreadable(override val name: String) : LeftOut

    /** Past the 10 files, or past 100 MB in all. */
    data class TooMany(override val name: String) : LeftOut

    /** Text past 100,000 characters was cut. */
    data object TextCut : LeftOut {
        override val name = ""
    }
}

data class ShareUiState(
    val loading: Boolean = true,
    val text: String = "",
    val files: List<PickedFile> = emptyList(),
    val leftOut: List<LeftOut> = emptyList(),
    val workspaces: List<ShareWorkspace> = emptyList(),
    val workspace: ShareWorkspace? = null,
    val chats: List<SessionSummary> = emptyList(),
    val target: ShareTarget = ShareTarget(null, null),
) {
    val nothing: Boolean get() = !loading && text.isBlank() && files.isEmpty()
    val canAdd: Boolean get() = !loading && workspace != null && !nothing
}

/**
 * What another app shared, as iOS's share extension takes it: text and links, and up to 10 files
 * within the composer's size limit (100 MB in all). Add hands it to the chosen chat's composer;
 * nothing is sent until the user sends it.
 */
@HiltViewModel
class ShareViewModel @Inject constructor(
    private val dashboards: DashboardRepository,
    private val cache: ChatCache,
    private val reader: AttachmentReader,
    private val settings: AppSettings,
    private val drafts: SharedDrafts,
) : ViewModel() {
    private val _state = MutableStateFlow(ShareUiState())
    val state: StateFlow<ShareUiState> = _state.asStateFlow()
    private var loaded = false

    /** Reads [intent] once; [chosen] is a Direct Share chat (`dashboard|profile|session`). */
    fun load(intent: Intent) {
        if (loaded) return
        loaded = true
        val chosen = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.let(ChatShortcuts::parse)
        val text = sharedText(intent)
        val uris = sharedUris(intent)
        viewModelScope.launch {
            val all = dashboards.dashboards.first()
            val active = dashboards.activeDashboardId.first()
            val workspaces = all.map { dashboard ->
                ShareWorkspace(
                    dashboard.id,
                    dashboard.url.host,
                    dashboards.activeProfile(dashboard.id).first(),
                )
            }.sortedByDescending { it.dashboard == active }
            val workspace = chosen?.let { pick -> workspaces.firstOrNull { it.dashboard.value == pick.dashboard } }
                ?.copy(profile = chosen.profile)
                ?: workspaces.firstOrNull()
            _state.update {
                it.copy(
                    workspaces = workspaces,
                    workspace = workspace,
                    text = text.take(SharedDrafts.MAX_TEXT),
                    leftOut = if (text.length > SharedDrafts.MAX_TEXT) listOf(LeftOut.TextCut) else emptyList(),
                )
            }
            workspace?.let { loadChats(it, chosen?.sessionId) }
            readFiles(uris)
            _state.update { it.copy(loading = false) }
        }
    }

    fun choose(workspace: ShareWorkspace) {
        _state.update { it.copy(workspace = workspace, target = ShareTarget(null, null)) }
        viewModelScope.launch { loadChats(workspace, null) }
    }

    fun target(target: ShareTarget) = _state.update { it.copy(target = target) }

    /** The workspace's chats as last listed: pinned first, then the most recent, 20 at most. */
    private suspend fun loadChats(workspace: ShareWorkspace, chosen: String?) {
        val chats = cache.sessions(workspace.dashboard, workspace.profile, archived = false)
            .filter { it.source != "cron" }
            .sortedWith(compareByDescending<SessionSummary> { it.pinned }.thenByDescending { it.lastActiveAtMs ?: 0L })
            .take(MAX_CHATS)
        val target = chosen?.let { id -> chats.firstOrNull { it.id == id } }
            ?.let { ShareTarget(it.id, it.displayTitle) }
            ?: chosen?.let { ShareTarget(it, null) }
            ?: ShareTarget(null, null)
        _state.update { it.copy(chats = chats, target = target) }
    }

    private suspend fun readFiles(uris: List<Uri>) {
        val limit = settings.preferences.first().attachmentLimitBytes
        val files = mutableListOf<PickedFile>()
        val leftOut = mutableListOf<LeftOut>()
        var total = 0L
        for (uri in uris) {
            val label = uri.lastPathSegment.orEmpty()
            if (files.size >= SharedDrafts.MAX_FILES) {
                leftOut += LeftOut.TooMany(label)
                continue
            }
            when (val result = reader.read(uri, limit)) {
                is AttachmentReader.Result.Read -> if (total + result.file.bytes.size > SharedDrafts.MAX_TOTAL_BYTES) {
                    leftOut += LeftOut.TooMany(result.file.name)
                } else {
                    total += result.file.bytes.size
                    files += result.file
                }

                is AttachmentReader.Result.Failed -> leftOut += when (val failure = result.failure) {
                    is PickFailure.TooLarge -> LeftOut.TooLarge(label, failure.limitBytes)
                    PickFailure.Unreadable -> LeftOut.Unreadable(label)
                }
            }
        }
        _state.update { it.copy(files = files, leftOut = it.leftOut + leftOut) }
    }

    /** Hands the share to the chosen chat; answers what the app opens, or null when there's nothing. */
    fun add(): SharedOpen? {
        val current = _state.value
        val workspace = current.workspace ?: return null
        if (!current.canAdd) return null
        val id = drafts.put(SharedDraft(current.text.trim(), current.files))
        return SharedOpen(
            workspace.dashboard.value,
            workspace.profile,
            current.target.sessionId,
            current.target.title,
            id,
        )
    }

    companion object {
        private const val MAX_CHATS = 20

        /** The shared text, a link not repeated; a page title only when nothing else came. */
        fun sharedText(intent: Intent): String {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            val clipped = intent.clipData?.let { clip ->
                (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text?.toString()?.trim() }
            }.orEmpty()
            val parts = (listOf(text) + clipped).filter(String::isNotEmpty)
                .fold(mutableListOf<String>()) { kept, part -> kept.apply { if (none { part in it }) add(part) } }
            return parts.joinToString("\n\n").ifEmpty {
                intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
            }
        }

        fun sharedUris(intent: Intent): List<Uri> {
            val streams = when (intent.action) {
                Intent.ACTION_SEND_MULTIPLE ->
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()

                else -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            }
            val clipped = intent.clipData?.let { clip ->
                (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            }
            return (streams + clipped.orEmpty()).distinct()
        }
    }
}

/** The chat the app opens for a share, and the draft waiting for it. */
data class SharedOpen(
    val dashboard: String,
    val profile: String,
    val sessionId: String?,
    val title: String?,
    val draftId: String,
)
