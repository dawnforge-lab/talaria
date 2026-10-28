package lab.dawnforge.hermes.feature.chat

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lab.dawnforge.hermes.core.data.chat.MediaRepository
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.data.workspace.Artifact
import lab.dawnforge.hermes.core.data.workspace.ArtifactFilter
import lab.dawnforge.hermes.core.data.workspace.ArtifactKind
import lab.dawnforge.hermes.core.data.workspace.WorkspaceRepository
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.feature.chat.navigation.ArtifactsRoute

/** Something the list asks the person about an artifact it couldn't open. */
internal sealed interface ArtifactProblem {
    data class Missing(val path: String?) : ArtifactProblem

    data object TooLarge : ArtifactProblem

    data class Failed(val failure: ConnectionFailure) : ArtifactProblem
}

internal data class ArtifactsUiState(
    /** One chat's artifacts (its title), or the profile's when null. */
    val chatTitle: String? = null,
    val forChat: Boolean = false,
    /** Whether the host's plugin lists artifacts; null until known. */
    val supported: Boolean? = null,
    val filter: ArtifactFilter = ArtifactFilter.All,
    val query: String = "",
    val showMissing: Boolean = false,
    val items: List<Artifact> = emptyList(),
    /** The items are from before the filter or search changed, until the new ones come. */
    val stale: Boolean = false,
    val nextBefore: Long? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val failure: ConnectionFailure? = null,
    val loadingMore: Boolean = false,
    val moreFailure: ConnectionFailure? = null,
    /** The artifact being fetched to open it. */
    val opening: String? = null,
    val problem: ArtifactProblem? = null,
    /** A page open whole: its name and HTML. */
    val page: Pair<String, String>? = null,
)

/** What the screen hands to another app. */
internal sealed interface ArtifactEffect {
    data class OpenFile(val file: File, val mimeType: String) : ArtifactEffect

    data class Browse(val url: String) : ArtifactEffect
}

/**
 * Files, pages and links chats produced, as the host's push plugin lists them: the profile's, or
 * one chat's. Pictures get thumbnails; files open in another app, pages in the sandboxed viewer.
 */
@HiltViewModel
internal class ArtifactsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dashboards: DashboardRepository,
    private val workspace: WorkspaceRepository,
    private val media: MediaRepository,
    private val settings: AppSettings,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ArtifactsRoute>()
    private val _state =
        MutableStateFlow(ArtifactsUiState(chatTitle = route.title, forChat = route.sessionIds.isNotEmpty()))
    val state: StateFlow<ArtifactsUiState> = _state.asStateFlow()
    private val effectChannel = Channel<ArtifactEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var loadJob: Job? = null
    private val thumbnails = LruCache<String, ImageBitmap>(THUMBNAILS)

    init {
        viewModelScope.launch {
            val show = settings.showMissingArtifacts.first()
            _state.update { it.copy(showMissing = show) }
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val supported = (workspace.artifactsSupported(id) as? NetResult.Success)?.value ?: true
            _state.update { it.copy(supported = supported, loading = supported) }
            if (supported) reload()
        }
    }

    fun filter(filter: ArtifactFilter) {
        if (filter == _state.value.filter) return
        _state.update { it.copy(filter = filter) }
        reload()
    }

    /** Searching waits for typing to pause. */
    fun search(query: String) {
        _state.update { it.copy(query = query) }
        reload(debounce = true)
    }

    fun showMissing(show: Boolean) {
        _state.update { it.copy(showMissing = show) }
        viewModelScope.launch { settings.setShowMissingArtifacts(show) }
        reload()
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        reload()
    }

    fun retry() {
        _state.update { it.copy(loading = true, failure = null) }
        reload()
    }

    /** The next page, when the end of the list shows. */
    fun loadMore() {
        val current = _state.value
        val before = current.nextBefore ?: return
        if (current.loadingMore || current.loading || current.stale || current.moreFailure != null) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            when (val result = page(id, before)) {
                is NetResult.Success -> _state.update { state ->
                    // The first (newest) mention of a file wins.
                    val known = state.items.mapTo(HashSet()) { it.id }
                    state.copy(
                        items = state.items + result.value.items.filter { it.id !in known }.distinctBy { it.id },
                        nextBefore = result.value.nextBefore,
                        loadingMore = false,
                    )
                }

                is NetResult.Failure -> _state.update { it.copy(loadingMore = false, moreFailure = result.failure) }
            }
        }
    }

    fun retryMore() {
        _state.update { it.copy(moreFailure = null) }
        loadMore()
    }

    /** Opens [item]: a link in the browser, a page whole, a file (fetched into [target]) in another app. */
    fun open(item: Artifact, target: File) {
        if (_state.value.opening != null) return
        val path = item.path
        if (path == null) {
            item.url?.let { url -> viewModelScope.launch { effectChannel.send(ArtifactEffect.Browse(url)) } }
            return
        }
        if (!item.exists) {
            _state.update { it.copy(problem = ArtifactProblem.Missing(path)) }
            return
        }
        _state.update { it.copy(opening = item.id) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            if (item.kind == ArtifactKind.Page) {
                val html = (media.file(id, route.profile, path) as? NetResult.Success)?.value?.bytes
                    ?.takeIf { it.size <= MAX_PAGE_BYTES }
                    ?.let {
                        runCatching {
                            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(it)).toString()
                        }.getOrNull()
                    }
                _state.update {
                    if (html == null) {
                        it.copy(opening = null, problem = ArtifactProblem.Missing(path))
                    } else {
                        it.copy(opening = null, page = item.name to html)
                    }
                }
                return@launch
            }
            val result = workspace.download(id, route.profile, path, target)
            _state.update { it.copy(opening = null) }
            when (result) {
                is NetResult.Success -> effectChannel.send(
                    ArtifactEffect.OpenFile(target, HostFiles.mimeType(item.name)),
                )

                is NetResult.Failure -> _state.update {
                    it.copy(
                        problem = when (result.failure) {
                            is ConnectionFailure.TooLarge -> ArtifactProblem.TooLarge
                            is ConnectionFailure.NotFound -> ArtifactProblem.Missing(path)
                            else -> ArtifactProblem.Failed(result.failure)
                        },
                    )
                }
            }
        }
    }

    fun problemShown() = _state.update { it.copy(problem = null) }

    fun closePage() = _state.update { it.copy(page = null) }

    /** A picture's thumbnail, read once and kept while the list shows. */
    suspend fun thumbnail(item: Artifact): ImageBitmap? {
        val path = item.path?.takeIf { item.kind == ArtifactKind.Image && item.exists } ?: return null
        thumbnails.get(path)?.let { return it }
        val id = dashboards.activeDashboardId.first() ?: return null
        val bytes = (media.file(id, route.profile, path) as? NetResult.Success)?.value?.bytes ?: return null
        val image = withContext(Dispatchers.Default) { decode(bytes) } ?: return null
        thumbnails.put(path, image)
        return image
    }

    private fun reload(debounce: Boolean = false) {
        loadJob?.cancel()
        _state.update { it.copy(stale = it.items.isNotEmpty(), loading = it.items.isEmpty(), moreFailure = null) }
        loadJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_PAUSE_MS)
            val id = dashboards.activeDashboardId.first() ?: return@launch
            when (val result = page(id, before = null)) {
                is NetResult.Success -> _state.update {
                    it.copy(
                        items = result.value.items.distinctBy { item -> item.id },
                        nextBefore = result.value.nextBefore,
                        stale = false,
                        loading = false,
                        refreshing = false,
                        failure = null,
                    )
                }

                // A failed refresh keeps what was shown.
                is NetResult.Failure -> _state.update {
                    it.copy(loading = false, refreshing = false, stale = false, failure = result.failure)
                }
            }
        }
    }

    private suspend fun page(id: DashboardId, before: Long?) = _state.value.let { state ->
        workspace.artifacts(id, route.profile, route.sessionIds, state.filter, state.query, state.showMissing, before)
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    private companion object {
        const val SEARCH_PAUSE_MS = 350L
        const val THUMBNAILS = 300
        const val THUMBNAIL_PX = 360

        /** As iOS: a page larger than this isn't drawn. */
        const val MAX_PAGE_BYTES = 4 * 1024 * 1024
    }
}
