package lab.dawnforge.hermes.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import lab.dawnforge.hermes.core.data.plugin.AppliedNotice
import lab.dawnforge.hermes.core.data.plugin.HermesChanges
import lab.dawnforge.hermes.core.data.plugin.ReviewItem

@HiltViewModel
class HermesChangesViewModel @Inject constructor(private val changes: HermesChanges) : ViewModel() {
    val review: StateFlow<List<ReviewItem>> = changes.review
    val notice: StateFlow<AppliedNotice?> = changes.notice
    val reviewing: StateFlow<Boolean> = changes.reviewing

    fun openReview(open: Boolean) = changes.showReview(open)

    fun resolve(items: List<ReviewItem>, apply: Boolean) = changes.resolve(items, apply)

    fun undo(notice: AppliedNotice) = changes.undo(notice)

    fun noticeShown(notice: AppliedNotice) = changes.noticeShown(notice)
}

/**
 * Changes Hermes asked of the phone's settings, over whatever is on screen: a banner when some
 * wait (Review opens them, each with its old and new value and Hermes' reason), and the changes
 * made without asking, with an Undo, for ten seconds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesChangesHost(viewModel: HermesChangesViewModel = hiltViewModel()) {
    val review by viewModel.review.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val reviewing by viewModel.reviewing.collectAsStateWithLifecycle()
    // Closed for the changes shown; new ones bring it back.
    var closedFor by remember { mutableStateOf<Set<String>>(emptySet()) }
    val ids = review.mapTo(HashSet()) { it.request.id }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (review.isNotEmpty() && !reviewing && !closedFor.containsAll(ids)) {
            Banner(review, onReview = { viewModel.openReview(true) }, onClose = { closedFor = ids })
        }
        notice?.let { applied ->
            LaunchedEffect(applied) {
                delay(NOTICE_MS)
                viewModel.noticeShown(applied)
            }
            Notice(applied, Modifier.align(Alignment.BottomCenter)) { viewModel.undo(applied) }
        }
    }
    if (reviewing) {
        ModalBottomSheet(onDismissRequest = { viewModel.openReview(false) }) {
            ReviewSheet(review, viewModel::resolve) { viewModel.openReview(false) }
        }
    }
}

@Composable
private fun Banner(review: List<ReviewItem>, onReview: () -> Unit, onClose: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (review.size == 1) {
                    stringResource(R.string.feature_settings_changes_wants, review.first().title)
                } else {
                    pluralStringResource(R.plurals.feature_settings_changes_wants_many, review.size, review.size)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReview) { Text(stringResource(R.string.feature_settings_changes_review)) }
            TextButton(onClick = onClose) { Text(stringResource(R.string.feature_settings_changes_later)) }
        }
    }
}

@Composable
private fun ReviewSheet(review: List<ReviewItem>, onResolve: (List<ReviewItem>, Boolean) -> Unit, onDone: () -> Unit) {
    LaunchedEffect(review.isEmpty()) { if (review.isEmpty()) onDone() }
    Column(
        Modifier.fillMaxWidth().padding(
            horizontal = 20.dp,
        ).padding(bottom = 20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.feature_settings_changes_title), style = MaterialTheme.typography.titleLarge)
        review.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.feature_settings_changes_from_to, item.oldValue, item.newValue))
                if (item.request.reason.isNotBlank()) {
                    Text(
                        "“${item.request.reason}”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onResolve(listOf(item), true) }) {
                        Text(stringResource(R.string.feature_settings_changes_apply))
                    }
                    OutlinedButton(onClick = { onResolve(listOf(item), false) }) {
                        Text(stringResource(R.string.feature_settings_changes_dismiss))
                    }
                }
            }
            HorizontalDivider()
        }
        if (review.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onResolve(review, true) }) {
                    Text(stringResource(R.string.feature_settings_changes_apply_all))
                }
                OutlinedButton(onClick = { onResolve(review, false) }) {
                    Text(stringResource(R.string.feature_settings_changes_dismiss_all))
                }
            }
        }
    }
}

@Composable
private fun Notice(notice: AppliedNotice, modifier: Modifier, onUndo: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().padding(12.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (notice.changes.size == 1) {
                    stringResource(R.string.feature_settings_changes_changed, notice.changes.first().title)
                } else {
                    pluralStringResource(
                        R.plurals.feature_settings_changes_changed_many,
                        notice.changes.size,
                        notice.changes.size,
                    )
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUndo) {
                Text(
                    stringResource(R.string.feature_settings_changes_undo),
                    color = MaterialTheme.colorScheme.inversePrimary,
                )
            }
        }
    }
}

private const val NOTICE_MS = 10_000L
