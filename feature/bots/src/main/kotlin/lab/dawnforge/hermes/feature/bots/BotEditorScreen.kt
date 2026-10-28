package lab.dawnforge.hermes.feature.bots

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lab.dawnforge.hermes.core.data.bots.BotColor
import lab.dawnforge.hermes.core.ui.BotAvatar
import lab.dawnforge.hermes.core.ui.failureText

/** Making or editing a bot, as iOS's bot editor: picture, colour, name, description and personality. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BotEditorScreen(viewModel: BotEditorViewModel, onClose: () -> Unit, onCreated: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val avatars by viewModel.avatars.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::pickPicture)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state.creating) R.string.feature_bots_new else R.string.feature_bots_edit))
                },
                navigationIcon = {
                    IconButton(onClick = onClose, enabled = !state.saving) {
                        Icon(Icons.Filled.Close, stringResource(R.string.feature_bots_cancel))
                    }
                },
                actions = {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(24.dp))
                    } else {
                        TextButton(
                            onClick = {
                                viewModel.save { created -> if (created != null) onCreated(created) else onClose() }
                            },
                            enabled = state.canSave,
                        ) {
                            Text(
                                stringResource(
                                    pick(state.creating, R.string.feature_bots_create, R.string.feature_bots_save),
                                ),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            state.warning?.let { Text(warningText(it), color = MaterialTheme.colorScheme.error) }

            // Picture.
            val label = state.name.ifBlank { state.bot?.label ?: "?" }
            val color = state.color?.let(BotColor::rgb) ?: BotColor.fallback(state.bot?.name ?: state.slug)
            val picture = when (val choice = state.picture) {
                is PictureChoice.New -> choice.png
                PictureChoice.Removed -> null
                PictureChoice.Unchanged -> state.bot?.let { avatars[it.name] }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BotAvatar(label, color, picture, size = AVATAR_SIZE.dp)
                Column {
                    TextButton(onClick = {
                        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text(stringResource(R.string.feature_bots_choose_photo)) }
                    when {
                        state.picture == PictureChoice.Removed -> TextButton(onClick = viewModel::keepPicture) {
                            Text(stringResource(R.string.feature_bots_keep_photo))
                        }

                        picture != null -> TextButton(onClick = viewModel::removePicture) {
                            Text(stringResource(R.string.feature_bots_remove_photo))
                        }
                    }
                }
            }

            // Colour.
            Text(stringResource(R.string.feature_bots_color), style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Swatch(null, BotColor.fallback(state.bot?.name ?: state.slug), state.color == null, viewModel::setColor)
                BotColor.SWATCHES.forEach { swatch ->
                    Swatch(swatch, BotColor.rgb(swatch) ?: 0, state.color == swatch, viewModel::setColor)
                }
            }
            Help(stringResource(R.string.feature_bots_color_help))

            // Name.
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = {
                    Text(
                        stringResource(
                            pick(state.creating, R.string.feature_bots_name, R.string.feature_bots_display_name),
                        ),
                    )
                },
                placeholder = state.bot?.let { bot -> { Text(bot.displayName ?: bot.name) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Help(nameHelp(state))

            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::setDescription,
                label = { Text(stringResource(R.string.feature_bots_description)) },
                placeholder = { Text(stringResource(R.string.feature_bots_description_placeholder)) },
                minLines = 2,
                maxLines = DESCRIPTION_LINES,
                enabled = !state.detailsFailed,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.feature_bots_personality), style = MaterialTheme.typography.titleSmall)
            if (state.detailsFailed) {
                Text(stringResource(R.string.feature_bots_personality_failed), color = MaterialTheme.colorScheme.error)
            } else {
                OutlinedTextField(
                    value = state.soul,
                    onValueChange = viewModel::setSoul,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    minLines = SOUL_LINES,
                    modifier = Modifier.fillMaxWidth().heightIn(min = SOUL_HEIGHT.dp),
                )
                Help(
                    stringResource(
                        pick(
                            state.creating,
                            R.string.feature_bots_personality_help_new,
                            R.string.feature_bots_personality_help,
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun Swatch(value: String?, rgb: Int, selected: Boolean, onPick: (String?) -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent
    Box(
        Modifier.size(SWATCH.dp).border(2.dp, ring, CircleShape).padding(4.dp)
            .background(Color(OPAQUE or rgb), CircleShape).clickable { onPick(value) },
        contentAlignment = Alignment.Center,
    ) {
        if (value == null) {
            Text(
                stringResource(R.string.feature_bots_color_auto),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Help(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun nameHelp(state: BotEditorState): String = when {
    !state.creating -> stringResource(R.string.feature_bots_profile_id, state.bot?.name.orEmpty())
    state.nameProblem == NameProblem.Empty -> stringResource(R.string.feature_bots_name_help)
    state.nameProblem == NameProblem.Invalid -> stringResource(R.string.feature_bots_name_invalid)
    state.nameProblem == NameProblem.Taken -> stringResource(R.string.feature_bots_name_taken, state.slug)
    else -> stringResource(R.string.feature_bots_profile_id, state.slug)
}

@Composable
private fun warningText(warning: BotWarning): String = stringResource(
    when (warning) {
        BotWarning.LookNotSaved -> R.string.feature_bots_warning_look
        BotWarning.NameOrColorNotSaved -> R.string.feature_bots_warning_name
        BotWarning.DetailsNotSaved -> R.string.feature_bots_warning_details
        BotWarning.PictureNotRemoved -> R.string.feature_bots_warning_picture
    },
)

/** [whenOn] when [on], else [otherwise]. */
private fun pick(on: Boolean, whenOn: Int, otherwise: Int) = if (on) whenOn else otherwise

private const val OPAQUE = 0xFF000000.toInt()
private const val AVATAR_SIZE = 88
private const val SWATCH = 40
private const val DESCRIPTION_LINES = 5
private const val SOUL_LINES = 8
private const val SOUL_HEIGHT = 160
