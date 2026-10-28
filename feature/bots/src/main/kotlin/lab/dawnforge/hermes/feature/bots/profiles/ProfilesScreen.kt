package lab.dawnforge.hermes.feature.bots.profiles

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lab.dawnforge.hermes.core.data.bots.BotColor
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.ui.BotAvatar
import lab.dawnforge.hermes.feature.bots.R

/** The profile manager: pictures, order and the default profile's name, as iOS's Profiles sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfilesScreen(viewModel: ProfilesViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event -> if (event == ProfilesEvent.Close) onBack() }
    }
    var picking by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val name = picking
        picking = null
        if (uri != null && name != null) viewModel.setPicture(name, uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_bots_profiles_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_bots_back))
                    }
                },
                actions = {
                    if (state.rows.size > 1) {
                        TextButton(onClick = viewModel::toggleReorder) {
                            val label = if (state.reordering) {
                                R.string.feature_bots_profiles_done
                            } else {
                                R.string.feature_bots_profiles_reorder
                            }
                            Text(stringResource(label))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    stringResource(
                        if (state.picturesUnavailable) {
                            R.string.feature_bots_profiles_note_no_pictures
                        } else {
                            R.string.feature_bots_profiles_note
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            itemsIndexed(state.rows, key = { _, row -> row.name }) { index, row ->
                ProfileCard(
                    row = row,
                    current = row.name == state.active,
                    reordering = state.reordering,
                    saving = state.saving == row.name,
                    problem = state.problem?.takeIf { it.first == row.name }?.second,
                    glow = state.glows[row.name],
                    picked = state.picks[row.name],
                    onPickGlow = { rgb -> viewModel.pickGlow(row.name, rgb) },
                    canMoveUp = index > 0,
                    canMoveDown = index < state.rows.lastIndex,
                    onSelect = { viewModel.select(row.name) },
                    onPick = {
                        picking = row.name
                        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onRemove = { viewModel.removePicture(row.name) },
                    onRename = viewModel::startRename,
                    onMove = { by -> viewModel.move(row.name, by) },
                )
                HorizontalDivider()
            }
        }
    }
    if (state.renaming) RenameDialog(viewModel)
}

@Composable
private fun ProfileCard(
    row: ProfileRow,
    current: Boolean,
    reordering: Boolean,
    saving: Boolean,
    problem: PictureProblem?,
    glow: Int?,
    picked: Int?,
    onPickGlow: (Int?) -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    onRename: () -> Unit,
    onMove: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !reordering && !current, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(contentAlignment = Alignment.Center) {
                BotAvatar(row.profile.label, row.color, row.picture, size = AVATAR.dp)
                if (saving) CircularProgressIndicator(Modifier.size(AVATAR.dp), strokeWidth = 2.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(row.profile.label, style = MaterialTheme.typography.titleMedium)
                val kind = if (row.name == Profile.DEFAULT) {
                    R.string.feature_bots_profiles_primary
                } else {
                    R.string.feature_bots_profiles_other
                }
                Text(
                    stringResource(kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                reordering -> {
                    IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                        val up = stringResource(R.string.feature_bots_profiles_move_up, row.profile.label)
                        Icon(Icons.Filled.KeyboardArrowUp, up)
                    }
                    IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                        val down = stringResource(R.string.feature_bots_profiles_move_down, row.profile.label)
                        Icon(Icons.Filled.KeyboardArrowDown, down)
                    }
                }

                current -> Icon(
                    Icons.Filled.Check,
                    stringResource(R.string.feature_bots_profiles_current),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (!reordering) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (row.canSetPicture) {
                    TextButton(onClick = onPick, enabled = !saving) {
                        val photo = if (row.hasPicture) {
                            R.string.feature_bots_profiles_change_photo
                        } else {
                            R.string.feature_bots_choose_photo
                        }
                        Text(stringResource(photo))
                    }
                    if (row.hasPicture) {
                        TextButton(onClick = onRemove, enabled = !saving) {
                            Text(stringResource(R.string.feature_bots_profiles_remove_photo))
                        }
                    }
                }
                if (row.name == Profile.DEFAULT) {
                    TextButton(onClick = onRename) { Text(stringResource(R.string.feature_bots_profiles_rename)) }
                }
            }
        }
        if (!reordering) GlowLine(glow, picked, onPickGlow)
        problem?.let {
            Text(
                stringResource(
                    when (it) {
                        PictureProblem.NotSaved -> R.string.feature_bots_profiles_photo_not_saved
                        PictureProblem.LookNotSaved -> R.string.feature_bots_profiles_photo_look_not_saved
                        PictureProblem.NotConnected -> R.string.feature_bots_profiles_photo_offline
                    },
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The name this phone shows for the default profile, as iOS's Settings › Profile. */
@Composable
private fun RenameDialog(viewModel: ProfilesViewModel) {
    var name by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { name = viewModel.defaultName() }
    val current = name ?: return
    AlertDialog(
        onDismissRequest = viewModel::cancelRename,
        title = { Text(stringResource(R.string.feature_bots_profiles_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.feature_bots_profiles_rename_note),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = current,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.feature_bots_profiles_rename_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.rename(current) }) {
                Text(stringResource(R.string.feature_bots_profiles_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelRename) {
                Text(stringResource(R.string.feature_bots_profiles_cancel))
            }
        },
    )
}

private const val AVATAR = 48

/**
 * The profile's background glow, as iOS's picker: Automatic (its photo's colour) or one picked
 * here, from the same swatches as a bot's colour.
 */
@Composable
private fun GlowLine(glow: Int?, picked: Int?, onPick: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.feature_bots_profiles_glow),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlowSwatch(
                rgb = glow ?: MaterialTheme.colorScheme.primary.toArgb(),
                selected = picked == null,
                label = stringResource(R.string.feature_bots_profiles_glow_auto),
                onClick = { onPick(null) },
            )
            BotColor.SWATCHES.forEach { swatch ->
                val rgb = BotColor.rgb(swatch) ?: return@forEach
                GlowSwatch(rgb, selected = picked == rgb, label = null, onClick = { onPick(rgb) })
            }
        }
    }
}

@Composable
private fun GlowSwatch(rgb: Int, selected: Boolean, label: String?, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent
    val name = label ?: stringResource(R.string.feature_bots_profiles_glow_swatch)
    Box(
        Modifier
            .size(SWATCH.dp)
            .border(2.dp, ring, CircleShape)
            .padding(3.dp)
            .background(Color(OPAQUE or rgb), CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) Text("A", color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

private const val SWATCH = 32
private const val OPAQUE = 0xFF000000.toInt()
