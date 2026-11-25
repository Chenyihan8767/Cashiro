package com.ritesh.cashiro.presentation.ui.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ritesh.cashiro.R
import com.ritesh.cashiro.data.repository.AiApiProvider
import com.ritesh.cashiro.data.repository.ApiAiModel
import com.ritesh.cashiro.data.repository.apiChatUrl

@Composable
internal fun AiApiModelDialog(model: ApiAiModel?, viewModel: SettingsViewModel, onDismiss: () -> Unit) {
    var provider by remember { mutableStateOf(model?.provider ?: AiApiProvider.DEEPSEEK) }
    var baseUrl by remember { mutableStateOf(model?.baseUrl.orEmpty()) }
    var name by remember { mutableStateOf(model?.name ?: provider.exampleModel) }
    var apiKey by remember { mutableStateOf("") }
    var shareData by remember { mutableStateOf(model?.shareFinancialData ?: false) }
    val state by viewModel.apiModelOperation.collectAsStateWithLifecycle()
    val endpoint = apiChatUrl(provider.baseUrl.ifEmpty { baseUrl })
    val keepKey = model != null && model.provider == provider && model.baseUrl == endpoint?.toString()

    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text(stringResource(if (model == null) R.string.ai_add_api_model else R.string.ai_edit_api_model)) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiApiProvider.entries.forEach { option ->
                        FilterChip(
                            selected = option == provider,
                            enabled = !state.saving,
                            onClick = {
                                if (option != provider) {
                                    provider = option
                                    name = option.exampleModel
                                    baseUrl = if (model?.provider == option) model.baseUrl else ""
                                    apiKey = ""
                                    shareData = false
                                }
                            },
                            label = { Text(option.displayLabel()) }
                        )
                    }
                }
                if (provider == AiApiProvider.CUSTOM) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ai_api_url)) },
                        placeholder = { Text("https://example.com/v1") },
                        isError = baseUrl.isNotBlank() && endpoint == null,
                        enabled = !state.saving,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ai_api_model_id)) },
                    enabled = !state.saving,
                    singleLine = true
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ai_api_key)) },
                    supportingText = if (keepKey) ({ Text(stringResource(R.string.ai_api_keep_key)) }) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !state.saving,
                    singleLine = true
                )
                Text(stringResource(R.string.ai_api_transfer_notice), style = MaterialTheme.typography.bodySmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.ai_api_share_data), Modifier.weight(1f))
                    Switch(checked = shareData, onCheckedChange = { shareData = it }, enabled = !state.saving)
                }
                if (state.failed) {
                    Text(stringResource(R.string.ai_api_save_error), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.saving && endpoint != null && name.isNotBlank() && (keepKey || apiKey.isNotBlank()),
                onClick = {
                    viewModel.saveApiModel(model, provider, baseUrl, name, apiKey, shareData, onDismiss)
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.saving) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
internal fun AiApiProvider.displayLabel(): String =
    if (this == AiApiProvider.CUSTOM) stringResource(R.string.ai_api_custom) else label
