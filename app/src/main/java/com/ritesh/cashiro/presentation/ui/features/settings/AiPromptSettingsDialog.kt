package com.ritesh.cashiro.presentation.ui.features.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ritesh.cashiro.R
import com.ritesh.cashiro.data.preferences.AiDataAccess
import com.ritesh.cashiro.data.preferences.AiPromptPreferences

@Composable
internal fun AiPromptSettingsDialog(
    settings: AiPromptPreferences,
    onDismiss: () -> Unit,
    onSave: (String, Set<AiDataAccess>) -> Unit
) {
    var instruction by rememberSaveable { mutableStateOf(settings.instruction) }
    var selected by rememberSaveable(
        stateSaver = listSaver<Set<AiDataAccess>, String>(
            save = { access -> access.map { it.name } },
            restore = { names -> names.map(AiDataAccess::valueOf).toSet() }
        )
    ) { mutableStateOf(settings.dataAccess) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_prompt_settings)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ai_system_instruction)) },
                    minLines = 4,
                    maxLines = 7,
                    supportingText = { Text(stringResource(R.string.ai_system_instruction_description)) }
                )
                TextButton(onClick = { instruction = AiPromptPreferences.DEFAULT_INSTRUCTION }) {
                    Text(stringResource(R.string.ai_restore_default_prompt))
                }
                HorizontalDivider()
                Text(stringResource(R.string.ai_data_access), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.ai_data_access_description), style = MaterialTheme.typography.bodySmall)
                AiDataAccess.entries.forEach { option ->
                    val checked = option in selected
                    val (label, description) = option.labels()
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .toggleable(value = checked, role = Role.Switch, onValueChange = { allow ->
                                selected = if (allow) selected + option else selected - option
                            })
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(description), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = checked, onCheckedChange = null)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(instruction, selected)
                onDismiss()
            }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

private data class DataAccessLabels(@StringRes val label: Int, @StringRes val description: Int)

private fun AiDataAccess.labels(): DataAccessLabels = when (this) {
    AiDataAccess.MONTHLY_SUMMARY -> DataAccessLabels(R.string.ai_data_monthly, R.string.ai_data_monthly_description)
    AiDataAccess.RECENT_TRANSACTIONS -> DataAccessLabels(R.string.ai_data_transactions, R.string.ai_data_transactions_description)
    AiDataAccess.ACCOUNTS -> DataAccessLabels(R.string.ai_data_accounts, R.string.ai_data_accounts_description)
    AiDataAccess.BUDGETS -> DataAccessLabels(R.string.budgets, R.string.ai_data_budgets_description)
    AiDataAccess.SUBSCRIPTIONS -> DataAccessLabels(R.string.subscriptions, R.string.ai_data_subscriptions_description)
    AiDataAccess.CATEGORIES -> DataAccessLabels(R.string.ai_data_categories, R.string.ai_data_categories_description)
}
