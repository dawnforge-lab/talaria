package lab.dawnforge.hermes.feature.cron

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lab.dawnforge.hermes.core.model.BlueprintField
import lab.dawnforge.hermes.core.model.BlueprintFieldType
import lab.dawnforge.hermes.core.model.CronBlueprint
import lab.dawnforge.hermes.core.model.DeliveryTarget
import lab.dawnforge.hermes.core.ui.failureText

/** The ready-made jobs Hermes offers, by category; one opens its blanks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CronBlueprintsScreen(
    viewModel: CronBlueprintsViewModel,
    onBack: () -> Unit,
    onOpen: (key: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_cron_from_blueprint)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        val failure = state.failure
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            failure != null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.feature_cron_blueprints_failed),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(failureText(failure), color = MaterialTheme.colorScheme.error)
                Button(onClick = viewModel::load) { Text(stringResource(R.string.feature_cron_retry)) }
            }

            state.blueprints.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.feature_cron_no_blueprints), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.feature_cron_no_blueprints_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                // By category, in the order Hermes lists them.
                state.blueprints.groupBy { it.category }.forEach { (category, blueprints) ->
                    item(key = "category-$category") {
                        Text(
                            category.replaceFirstChar(Char::uppercaseChar).ifEmpty {
                                stringResource(R.string.feature_cron_blueprint_other)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(blueprints, key = { it.key }) { blueprint ->
                        BlueprintRow(blueprint, onClick = { onOpen(blueprint.key) })
                    }
                }
            }
        }
    }
}

@Composable
private fun BlueprintRow(blueprint: CronBlueprint, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(blueprint.title) },
        supportingContent = {
            Column {
                if (blueprint.description.isNotEmpty()) {
                    Text(blueprint.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                blueprint.scheduleHuman?.let {
                    Text(
                        stringResource(R.string.feature_cron_blueprint_when, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
    HorizontalDivider()
}

/** One blueprint's blanks; Create makes the job and opens it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CronBlueprintScreen(
    viewModel: CronBlueprintViewModel,
    onBack: () -> Unit,
    onCreated: (profile: String, jobId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val created by rememberUpdatedState(onCreated)
    LaunchedEffect(viewModel) { viewModel.created.collect { created(viewModel.profile, it) } }
    val blueprint = state.blueprint
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(blueprint?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (state.creating) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(20.dp), strokeWidth = 2.dp)
                    } else if (blueprint != null) {
                        TextButton(onClick = viewModel::create, enabled = state.canCreate) {
                            Text(stringResource(R.string.feature_cron_create))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val failure = state.failure
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            blueprint == null -> Text(
                failure?.let { failureText(it) } ?: stringResource(R.string.feature_cron_blueprint_gone),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(padding).padding(24.dp),
            )

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                state.createFailure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
                if (blueprint.description.isNotEmpty()) Text(blueprint.description)
                for (field in blueprint.fields) {
                    FieldInput(
                        field,
                        value = state.values[field.name],
                        options = viewModel.optionsOf(field),
                        targets = state.targets,
                        onChange = { viewModel.onValueChange(field, it) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldInput(
    field: BlueprintField,
    value: String?,
    options: List<String>,
    targets: List<DeliveryTarget>,
    onChange: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(field.label, style = MaterialTheme.typography.titleSmall)
        when (field.type) {
            BlueprintFieldType.Time -> {
                var picking by remember { mutableStateOf(false) }
                // A missing or unreadable time shows as 08:00, as on iOS.
                val (hour, minute) = parseTime(value) ?: (DEFAULT_HOUR to 0)
                OutlinedButton(onClick = { picking = true }) { Text(clockTime(hour, minute)) }
                if (picking) {
                    TimeDialog(hour, minute, onDismiss = { picking = false }) { h, m ->
                        picking = false
                        onChange("%02d:%02d".format(h, m))
                    }
                }
            }

            BlueprintFieldType.Choice, BlueprintFieldType.Weekdays -> OptionPicker(
                value = value,
                options = options,
                optional = field.optional,
                title = { option -> optionTitle(field, option, targets) },
                onChange = onChange,
            )

            BlueprintFieldType.Text -> OutlinedTextField(
                value = value.orEmpty(),
                onValueChange = { onChange(it) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        field.help?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionPicker(
    value: String?,
    options: List<String>,
    optional: Boolean,
    title: @Composable (String) -> String,
    onChange: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val none = stringResource(R.string.feature_cron_none)
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value?.let { title(it) } ?: none,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (optional) {
                DropdownMenuItem(text = { Text(none) }, onClick = {
                    open = false
                    onChange(null)
                })
            }
            for (option in options) {
                DropdownMenuItem(text = { Text(title(option)) }, onClick = {
                    open = false
                    onChange(option)
                })
            }
        }
    }
}

@Composable
private fun optionTitle(field: BlueprintField, option: String, targets: List<DeliveryTarget>): String = when {
    field.isDelivery -> deliveryTitle(option, targets)

    field.type == BlueprintFieldType.Weekdays -> when (option) {
        "everyday" -> stringResource(R.string.feature_cron_every_day)
        "weekdays" -> stringResource(R.string.feature_cron_weekdays)
        "weekends" -> stringResource(R.string.feature_cron_weekends)
        else -> option
    }

    else -> option
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.feature_cron_back))
    }
}

/** "08:00" as hour and minute; null for anything else. */
private fun parseTime(value: String?): Pair<Int, Int>? {
    val match = Regex("""(\d{1,2}):(\d{2})""").matchEntire(value?.trim().orEmpty()) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    return if (hour in 0..LAST_HOUR && minute in 0..LAST_MINUTE) hour to minute else null
}

private const val DEFAULT_HOUR = 8
private const val LAST_HOUR = 23
private const val LAST_MINUTE = 59
