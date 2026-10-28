package lab.dawnforge.hermes.feature.bots.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lab.dawnforge.hermes.core.data.bots.BotColor
import lab.dawnforge.hermes.core.data.groups.GroupCreate
import lab.dawnforge.hermes.core.data.groups.GroupRoom
import lab.dawnforge.hermes.core.ui.BotAvatar
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.feature.bots.R

/** A new group chat: its name and 2–6 of the bots. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupCreateScreen(viewModel: GroupCreateViewModel, onClose: () -> Unit, onCreated: (GroupRoom) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val avatars by viewModel.avatars.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_bots_group_new)) },
                navigationIcon = {
                    IconButton(onClick = onClose, enabled = !state.creating) {
                        Icon(Icons.Filled.Close, stringResource(R.string.feature_bots_cancel))
                    }
                },
                actions = {
                    if (state.creating) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(24.dp))
                    } else {
                        TextButton(onClick = { viewModel.create(onCreated) }, enabled = state.canCreate) {
                            Text(stringResource(R.string.feature_bots_create))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (val problem = state.problem) {
                CreateProblem.HandleClash -> Problem(stringResource(R.string.feature_bots_group_handle_clash))

                is CreateProblem.Failed -> Problem(
                    stringResource(R.string.feature_bots_group_create_failed, failureText(problem.failure)),
                )

                null -> Unit
            }
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.feature_bots_group_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Text(
                stringResource(R.string.feature_bots_group_pick, GroupCreate.MIN_MEMBERS, GroupCreate.MAX_MEMBERS),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
            state.bots.forEach { bot ->
                val selected = bot.name in state.selected
                ListItem(
                    leadingContent = { BotAvatar(bot.label, BotColor.of(bot), avatars[bot.name]) },
                    headlineContent = { Text(bot.label) },
                    supportingContent = bot.description.trim().takeIf(String::isNotEmpty)?.let {
                        { Text(it, maxLines = 1) }
                    },
                    trailingContent = {
                        Checkbox(checked = selected, onCheckedChange = { viewModel.toggle(bot.name) })
                    },
                    modifier = Modifier.clickable { viewModel.toggle(bot.name) },
                )
            }
        }
    }
}

@Composable
private fun Problem(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
}
