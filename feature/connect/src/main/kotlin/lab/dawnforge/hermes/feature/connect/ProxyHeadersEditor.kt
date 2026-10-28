package lab.dawnforge.hermes.feature.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import lab.dawnforge.hermes.core.net.ProxyHeader
import lab.dawnforge.hermes.core.net.ProxyHeaderProblem
import lab.dawnforge.hermes.core.net.ProxyHeaderRules

/**
 * Extra headers a reverse proxy in front of Hermes asks for, as iOS's editor: a name and a hidden
 * value per row, sent with every request to this server over HTTPS, and kept encrypted.
 */
@Composable
internal fun ProxyHeadersEditor(
    rows: List<ProxyHeader>,
    onChange: (List<ProxyHeader>) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.feature_connect_headers_note), style = MaterialTheme.typography.bodySmall)
        rows.forEachIndexed { index, row ->
            HeaderRow(
                row = row,
                problem = ProxyHeaderRules.problem(row, rows).takeUnless { row.name.isBlank() && row.value.isBlank() },
                enabled = enabled,
                onChange = { changed -> onChange(rows.toMutableList().also { it[index] = changed }) },
                onRemove = { onChange(rows.toMutableList().also { it.removeAt(index) }) },
            )
        }
        OutlinedButton(onClick = { onChange(rows + ProxyHeader("", "")) }, enabled = enabled) {
            Text(stringResource(R.string.feature_connect_headers_add))
        }
    }
}

@Composable
private fun HeaderRow(
    row: ProxyHeader,
    problem: ProxyHeaderProblem?,
    enabled: Boolean,
    onChange: (ProxyHeader) -> Unit,
    onRemove: () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = row.name,
                onValueChange = { onChange(row.copy(name = it)) },
                label = { Text(stringResource(R.string.feature_connect_headers_name)) },
                singleLine = true,
                enabled = enabled,
                isError = problem != null && problem != ProxyHeaderProblem.InvalidValue,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(Icons.Filled.Close, stringResource(R.string.feature_connect_headers_remove))
            }
        }
        OutlinedTextField(
            value = row.value,
            onValueChange = { onChange(row.copy(value = it)) },
            label = { Text(stringResource(R.string.feature_connect_headers_value)) },
            singleLine = true,
            enabled = enabled,
            isError = problem == ProxyHeaderProblem.InvalidValue,
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                TextButton(onClick = { shown = !shown }) {
                    Text(
                        stringResource(
                            if (shown) R.string.feature_connect_headers_hide else R.string.feature_connect_headers_show,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        problem?.let {
            Text(
                stringResource(
                    when (it) {
                        ProxyHeaderProblem.EmptyName -> R.string.feature_connect_headers_empty_name
                        ProxyHeaderProblem.InvalidName -> R.string.feature_connect_headers_invalid_name
                        ProxyHeaderProblem.Reserved -> R.string.feature_connect_headers_reserved
                        ProxyHeaderProblem.InvalidValue -> R.string.feature_connect_headers_invalid_value
                        ProxyHeaderProblem.Duplicate -> R.string.feature_connect_headers_duplicate
                    },
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Whether every row that isn't blank can be sent. */
internal fun List<ProxyHeader>.allValid(): Boolean =
    ProxyHeaderRules.tidy(this).let { kept -> kept.all { ProxyHeaderRules.problem(it, kept) == null } }
