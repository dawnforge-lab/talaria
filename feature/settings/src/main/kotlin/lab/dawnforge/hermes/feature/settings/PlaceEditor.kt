package lab.dawnforge.hermes.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.phonedata.location.FoundPlace
import lab.dawnforge.hermes.core.data.phonedata.location.PlaceKind
import lab.dawnforge.hermes.core.data.phonedata.location.RawFix
import lab.dawnforge.hermes.core.data.phonedata.location.SavedPlace

/** A saved place being added or edited. */
internal data class PlaceDraft(
    val id: String = SavedPlace.newId(),
    val isNew: Boolean = true,
    val name: String = "",
    val kind: PlaceKind = PlaceKind.Home,
    val isPrivate: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** What the position came from, as the user knows it ("Your current location", an address). */
    val where: String? = null,
    val radius: Double = SavedPlace.DEFAULT_RADIUS,
) {
    val place: SavedPlace?
        get() {
            val lat = latitude ?: return null
            val lon = longitude ?: return null
            if (name.isBlank()) return null
            return SavedPlace.of(id, name, kind, isPrivate, lat, lon, radius)
        }

    companion object {
        fun of(place: SavedPlace) = PlaceDraft(
            id = place.id,
            isNew = false,
            name = place.name,
            kind = place.kind,
            isPrivate = place.isPrivate,
            latitude = place.latitude,
            longitude = place.longitude,
            radius = place.radius,
        )
    }
}

/**
 * Adds or edits a saved place, as iOS's editor: the current position or an address found with the
 * phone's geocoder, a name, a kind, a radius and Private. Where it is never leaves the phone.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PlaceEditor(
    draft: PlaceDraft,
    canLocate: Boolean,
    here: suspend () -> RawFix?,
    search: suspend (String) -> List<FoundPlace>?,
    onSave: (SavedPlace) -> Unit,
    onDelete: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var edited by remember(draft.id) { mutableStateOf(draft) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FoundPlace>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val notFound = stringResource(R.string.feature_settings_location_editor_not_found)
    val noMatches = stringResource(R.string.feature_settings_location_editor_no_matches)
    val searchFailed = stringResource(R.string.feature_settings_location_editor_search_failed)
    val currentLabel = stringResource(R.string.feature_settings_location_editor_current)

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        val title = if (draft.isNew) {
                            R.string.feature_settings_location_add_place
                        } else {
                            R.string.feature_settings_location_edit_place
                        }
                        Text(stringResource(title))
                    },
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Filled.Close, stringResource(R.string.feature_settings_location_cancel))
                        }
                    },
                    actions = {
                        TextButton(onClick = { edited.place?.let(onSave) }, enabled = edited.place != null) {
                            Text(stringResource(R.string.feature_settings_location_save))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = edited.name,
                    onValueChange = { edited = edited.copy(name = it.take(SavedPlace.MAX_NAME)) },
                    label = { Text(stringResource(R.string.feature_settings_location_editor_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaceKind.entries.forEach { kind ->
                        FilterChip(selected = edited.kind == kind, onClick = {
                            edited = edited.copy(kind = kind)
                        }, label = { Text(kindTitle(kind)) })
                    }
                }
                SwitchRow(stringResource(R.string.feature_settings_location_private), edited.isPrivate) {
                    edited =
                        edited.copy(isPrivate = it)
                }
                Text(
                    stringResource(R.string.feature_settings_location_private_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    stringResource(R.string.feature_settings_location_editor_where),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                val placed = edited.latitude != null
                Text(
                    when {
                        !placed -> stringResource(R.string.feature_settings_location_editor_not_placed)
                        edited.where != null -> edited.where!!
                        else -> stringResource(R.string.feature_settings_location_editor_placed)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (canLocate) {
                    OutlinedButton(
                        onClick = {
                            busy = true
                            message = null
                            scope.launch {
                                val fix = here()
                                if (fix != null) {
                                    edited =
                                        edited.copy(
                                            latitude = fix.latitude,
                                            longitude = fix.longitude,
                                            where = currentLabel,
                                        )
                                } else {
                                    message = notFound
                                }
                                busy = false
                            }
                        },
                        enabled = !busy,
                    ) { Text(stringResource(R.string.feature_settings_location_editor_use_current)) }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.feature_settings_location_editor_search)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        val text = query.trim()
                        if (text.isEmpty()) return@KeyboardActions
                        busy = true
                        message = null
                        scope.launch {
                            val found = search(text)
                            results = found.orEmpty()
                            message = when {
                                found == null -> searchFailed
                                found.isEmpty() -> noMatches
                                else -> null
                            }
                            busy = false
                        }
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) CircularProgressIndicator()
                results.forEach { found ->
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            edited = edited.copy(
                                latitude = found.latitude,
                                longitude = found.longitude,
                                where = found.detail.ifEmpty { found.name },
                                name = edited.name.ifBlank { found.name.take(SavedPlace.MAX_NAME) },
                            )
                            results = emptyList()
                        }.padding(vertical = 6.dp),
                    ) {
                        Text(found.name, style = MaterialTheme.typography.bodyMedium)
                        if (found.detail.isNotEmpty()) {
                            Text(
                                found.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    stringResource(R.string.feature_settings_location_editor_radius, edited.radius.roundToInt()),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Slider(
                    value = edited.radius.toFloat(),
                    onValueChange = { value ->
                        val stepped = (value / SavedPlace.RADIUS_STEP).roundToInt() * SavedPlace.RADIUS_STEP
                        edited = edited.copy(radius = stepped.coerceIn(SavedPlace.MIN_RADIUS, SavedPlace.MAX_RADIUS))
                    },
                    valueRange = SavedPlace.MIN_RADIUS.toFloat()..SavedPlace.MAX_RADIUS.toFloat(),
                    steps = ((SavedPlace.MAX_RADIUS - SavedPlace.MIN_RADIUS) / SavedPlace.RADIUS_STEP).toInt() - 1,
                )
                Text(
                    stringResource(R.string.feature_settings_location_editor_radius_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!draft.isNew) {
                    TextButton(onClick = { onDelete(draft.id) }) {
                        Text(
                            stringResource(R.string.feature_settings_location_editor_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
