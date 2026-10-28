package lab.dawnforge.hermes.feature.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.settings.AppPreferences
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.data.settings.ProjectChats
import lab.dawnforge.hermes.core.designsystem.theme.accentSwatch
import lab.dawnforge.hermes.core.model.AccentChoice
import lab.dawnforge.hermes.core.model.ThemeChoice

/** The phone's own settings, which iOS keeps on the device too. */
@HiltViewModel
internal class PhoneSettingsViewModel @Inject constructor(private val settings: AppSettings) : ViewModel() {
    val preferences: StateFlow<AppPreferences?> = settings.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(change: (AppPreferences) -> AppPreferences) {
        viewModelScope.launch { settings.update(change) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceScreen(viewModel: PhoneSettingsViewModel, onBack: () -> Unit) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_settings_appearance)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_settings_back))
                    }
                },
            )
        },
    ) { padding ->
        val current = prefs ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { CardHeader(stringResource(R.string.feature_settings_theme)) }
            items(ThemeChoice.entries) { theme ->
                Choice(stringResource(themeLabel(theme)), selected = current.theme == theme) {
                    viewModel.update { it.copy(theme = theme) }
                }
            }
            item { CardHeader(stringResource(R.string.feature_settings_accent)) }
            items(AccentChoice.entries) { accent ->
                Choice(
                    stringResource(accentLabel(accent)),
                    selected = current.accent == accent,
                    swatch = accentSwatch(accent),
                ) {
                    viewModel.update { it.copy(accent = accent) }
                }
            }
            item { CardHeader(stringResource(R.string.feature_settings_project_chats)) }
            items(ProjectChats.entries) { choice ->
                Choice(stringResource(projectChatsLabel(choice)), selected = current.projectChats == choice) {
                    viewModel.update { it.copy(projectChats = choice) }
                }
            }
            item {
                Text(
                    stringResource(R.string.feature_settings_project_chats_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun projectChatsLabel(choice: ProjectChats): Int = when (choice) {
    ProjectChats.Hidden -> R.string.feature_settings_project_chats_hidden
    ProjectChats.Recent -> R.string.feature_settings_project_chats_recent
    ProjectChats.All -> R.string.feature_settings_project_chats_all
}

/** The Chat page's own section: how chats behave on this phone. */
internal fun LazyListScope.phoneCard(prefs: AppPreferences, update: ((AppPreferences) -> AppPreferences) -> Unit) {
    item(key = "phone") {
        Column {
            CardHeader(stringResource(R.string.feature_settings_card_phone))
            Toggle(
                R.string.feature_settings_open_newest,
                R.string.feature_settings_open_newest_help,
                prefs.openAtNewest,
            ) { on -> update { it.copy(openAtNewest = on) } }
            TextSize(prefs.textSize) { size -> update { it.copy(textSize = size) } }
            Toggle(
                R.string.feature_settings_return_sends,
                R.string.feature_settings_return_sends_help,
                prefs.returnKeySends,
            ) { on -> update { it.copy(returnKeySends = on) } }
            AttachmentLimit(prefs.attachmentLimitMb) { mb -> update { it.copy(attachmentLimitMb = mb) } }
            Toggle(R.string.feature_settings_haptics, R.string.feature_settings_haptics_help, prefs.haptics) { on ->
                update { it.copy(haptics = on) }
            }
            Toggle(
                R.string.feature_settings_take_over,
                R.string.feature_settings_take_over_help,
                prefs.takeOverChats,
            ) { on ->
                update { it.copy(takeOverChats = on) }
            }
        }
    }
}

@Composable
internal fun Toggle(@StringRes title: Int, @StringRes help: Int, on: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(help), style = MaterialTheme.typography.bodySmall) },
        trailingContent = { Switch(checked = on, onCheckedChange = null) },
        modifier = Modifier.toggleable(on, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun TextSize(size: Int, onChange: (Int) -> Unit) {
    var dragged by remember(size) { mutableStateOf(size.toFloat()) }
    val last = AppPreferences.TEXT_SCALES.lastIndex
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.feature_settings_text_size),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(TEXT_SIZES[dragged.roundToInt().coerceIn(0, last)]),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(stringResource(R.string.feature_settings_text_size_help), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = dragged,
            onValueChange = { dragged = it },
            onValueChangeFinished = { onChange(dragged.roundToInt()) },
            valueRange = 0f..last.toFloat(),
            steps = last - 1,
        )
    }
}

@Composable
private fun AttachmentLimit(mb: Int, onChange: (Int) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val title = stringResource(R.string.feature_settings_attachment_limit)
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(
                    stringResource(R.string.feature_settings_attachment_limit_help),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (mb > AppPreferences.LARGE_ATTACHMENT_MB) {
                    Text(
                        stringResource(R.string.feature_settings_attachment_large),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        trailingContent = {
            Text(stringResource(R.string.feature_settings_attachment_mb, mb), color = MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier.clickable { picking = true },
    )
    if (picking) {
        val options = AppPreferences.ATTACHMENT_LIMITS_MB.map {
            it.toString() to
                stringResource(R.string.feature_settings_attachment_mb, it)
        }
        ChoiceDialog(
            title = title,
            options = options,
            selected = mb.toString(),
            onPick = {
                picking = false
                onChange(it.toInt())
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
internal fun Choice(label: String, selected: Boolean, swatch: Color? = null, onPick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
            .selectable(selected, role = Role.RadioButton, onClick = onPick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        if (swatch != null) Box(Modifier.size(18.dp).background(swatch, CircleShape))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@StringRes
private fun themeLabel(theme: ThemeChoice): Int = when (theme) {
    ThemeChoice.System -> R.string.feature_settings_theme_system
    ThemeChoice.Light -> R.string.feature_settings_theme_light
    ThemeChoice.Dark -> R.string.feature_settings_theme_dark
}

@StringRes
private fun accentLabel(accent: AccentChoice): Int = when (accent) {
    AccentChoice.Wallpaper -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        R.string.feature_settings_accent_wallpaper
    } else {
        R.string.feature_settings_accent_wallpaper_old
    }

    AccentChoice.Amber -> R.string.feature_settings_accent_amber

    AccentChoice.Blue -> R.string.feature_settings_accent_blue

    AccentChoice.Green -> R.string.feature_settings_accent_green

    AccentChoice.Teal -> R.string.feature_settings_accent_teal

    AccentChoice.Purple -> R.string.feature_settings_accent_purple

    AccentChoice.Pink -> R.string.feature_settings_accent_pink
}

private val TEXT_SIZES = listOf(
    R.string.feature_settings_text_size_0,
    R.string.feature_settings_text_size_1,
    R.string.feature_settings_text_size_2,
    R.string.feature_settings_text_size_3,
    R.string.feature_settings_text_size_4,
)
