package com.ritesh.cashiro.presentation.ui.features.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ritesh.cashiro.R
import com.ritesh.cashiro.core.Constants
import com.ritesh.cashiro.data.preferences.AiPromptPreferences
import com.ritesh.cashiro.data.repository.ApiAiModel
import com.ritesh.cashiro.data.repository.LocalAiModel
import com.ritesh.cashiro.data.repository.ModelImportError
import com.ritesh.cashiro.presentation.effects.overScrollVertical
import com.ritesh.cashiro.presentation.ui.components.CustomTitleTopAppBar
import com.ritesh.cashiro.presentation.ui.components.DeleteAIModelDialog
import com.ritesh.cashiro.presentation.ui.components.ListItem
import com.ritesh.cashiro.presentation.ui.components.ListItemPosition
import com.ritesh.cashiro.presentation.ui.components.PreferenceSwitch
import com.ritesh.cashiro.presentation.ui.components.toShape
import com.ritesh.cashiro.presentation.ui.features.categories.NavigationContent
import com.ritesh.cashiro.presentation.ui.icons.Iconax
import com.ritesh.cashiro.presentation.ui.icons.ImportArrow01
import com.ritesh.cashiro.presentation.ui.theme.Dimensions
import com.ritesh.cashiro.presentation.ui.theme.Spacing
import com.ritesh.cashiro.presentation.ui.theme.blue_dark
import com.ritesh.cashiro.presentation.ui.theme.blue_light
import com.ritesh.cashiro.presentation.ui.theme.cyan_dark
import com.ritesh.cashiro.presentation.ui.theme.cyan_light
import com.ritesh.cashiro.presentation.ui.theme.green_dark
import com.ritesh.cashiro.presentation.ui.theme.green_light
import com.ritesh.cashiro.presentation.ui.theme.purple_dark
import com.ritesh.cashiro.presentation.ui.theme.purple_light
import com.ritesh.cashiro.presentation.ui.theme.yellow_dark
import com.ritesh.cashiro.presentation.ui.theme.yellow_light
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiSettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    blurEffects: Boolean
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pinnedScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val hazeState = remember { HazeState() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CustomTitleTopAppBar(
                title = stringResource(R.string.ai_settings_title),
                scrollBehaviorSmall = pinnedScrollBehavior,
                scrollBehaviorLarge = scrollBehavior,
                hazeState = hazeState,
                hasBackButton = true,
                navigationContent = { NavigationContent(onNavigateBack) }
            )
        }
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .overScrollVertical()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = Dimensions.Padding.content,
                    top = insets.calculateTopPadding() + Dimensions.Padding.content,
                    end = Dimensions.Padding.content,
                    bottom = insets.calculateBottomPadding() + Spacing.md
                )
        ) {
            AiModelSettings(viewModel, blurEffects, hazeState)
        }
    }
}

@Composable
private fun AiModelSettings(
    viewModel: SettingsViewModel,
    blurEffects: Boolean,
    hazeState: HazeState
) {
    val enabled by viewModel.isAiEnabled.collectAsStateWithLifecycle(initialValue = false)
    val selection by viewModel.aiModelSelection.collectAsStateWithLifecycle()
    val promptSettings by viewModel.aiPromptPreferences.collectAsStateWithLifecycle(initialValue = AiPromptPreferences())
    val modelImport by viewModel.modelImport.collectAsStateWithLifecycle()
    val localModelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importAiModel)
    }
    var showPromptSettings by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showApiEditor by remember { mutableStateOf(false) }
    var apiModelToEdit by remember { mutableStateOf<ApiAiModel?>(null) }
    var apiModelToDelete by remember { mutableStateOf<ApiAiModel?>(null) }
    val apiOperation by viewModel.apiModelOperation.collectAsStateWithLifecycle()
    var modelToDelete by remember { mutableStateOf<LocalAiModel?>(null) }
    val selected = selection.selected
    val supportsLocalModels = Constants.Device.is64Bit

    Column(verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
        PreferenceSwitch(
            title = selected?.name ?: stringResource(R.string.ai_assistant),
            subtitle = stringResource(R.string.ai_settings_summary),
            checked = enabled,
            onCheckedChange = viewModel::setAiEnabled,
            leadingIcon = { AiSettingsIcon(Icons.Rounded.AutoAwesome, yellow_light, yellow_dark) },
            padding = PaddingValues(0.dp),
            isFirst = enabled,
            isSingle = !enabled,
            titleFontWeight = FontWeight.Medium
        )
        if (enabled) {
            AiSettingsRow(
                title = stringResource(R.string.ai_prompt_settings),
                leading = { AiSettingsIcon(Icons.Rounded.Tune, blue_light, blue_dark) },
                onClick = { showPromptSettings = true }
            )
            AiSettingsRow(
                title = stringResource(R.string.ai_select_model),
                subtitle = when (selected) {
                    is ApiAiModel -> stringResource(R.string.ai_api_selection, selected.name, selected.provider.displayLabel())
                    is LocalAiModel -> selected.name
                    null -> stringResource(R.string.ai_no_model_selected)
                },
                leading = { AiSettingsIcon(Icons.Rounded.Memory, purple_light, purple_dark) },
                onClick = if (selection.models.isNotEmpty() && !modelImport.running) {
                    { showModels = true }
                } else null
            )
            AiSettingsRow(
                title = stringResource(R.string.ai_add_api_model),
                leading = { AiSettingsIcon(Icons.Rounded.Cloud, cyan_light, cyan_dark) },
                isLast = !supportsLocalModels && selected == null,
                onClick = {
                    viewModel.clearApiModelOperation()
                    apiModelToEdit = null
                    showApiEditor = true
                }
            )
            if (supportsLocalModels) {
                val progress = modelImport.progress
                val importDescription = when {
                    modelImport.running && progress != null ->
                        stringResource(R.string.ai_import_model_progress, progress)
                    modelImport.running -> stringResource(R.string.ai_import_model_running)
                    modelImport.error == ModelImportError.UNSUPPORTED -> stringResource(R.string.ai_import_model_unsupported)
                    modelImport.error == ModelImportError.NO_SPACE -> stringResource(R.string.ai_chat_subtitle_insufficient_space)
                    modelImport.error == ModelImportError.IO -> stringResource(R.string.ai_import_model_failed)
                    modelImport.error == ModelImportError.DELETE -> stringResource(R.string.ai_delete_model_failed)
                    else -> stringResource(R.string.ai_import_model_summary)
                }
                AiSettingsRow(
                    title = stringResource(R.string.ai_import_model),
                    subtitle = importDescription,
                    leading = { AiSettingsIcon(Iconax.ImportArrow01, green_light, green_dark) },
                    isLast = selected == null,
                    showProgress = modelImport.running,
                    showError = modelImport.error != null,
                    onClick = if (modelImport.running) null else ({ localModelPicker.launch(arrayOf("*/*")) })
                )
            }
            when (selected) {
                is LocalAiModel -> AiSettingsRow(
                    title = stringResource(R.string.ai_delete_selected_model),
                    subtitle = selected.name,
                    leading = { AiSettingsDeleteIcon() },
                    isLast = true,
                    onClick = if (modelImport.running) null else ({ modelToDelete = selected })
                )
                is ApiAiModel -> {
                    AiSettingsRow(
                        title = stringResource(R.string.ai_edit_api_model),
                        leading = { AiSettingsIcon(Icons.Rounded.Edit, blue_light, blue_dark) },
                        onClick = {
                            viewModel.clearApiModelOperation()
                            apiModelToEdit = selected
                            showApiEditor = true
                        }
                    )
                    AiSettingsRow(
                        title = stringResource(R.string.ai_delete_api_model),
                        leading = { AiSettingsDeleteIcon() },
                        isLast = true,
                        onClick = {
                            viewModel.clearApiModelOperation()
                            apiModelToDelete = selected
                        }
                    )
                }
                null -> Unit
            }
        }
    }

    if (enabled && showPromptSettings) {
        AiPromptSettingsDialog(
            settings = promptSettings,
            onDismiss = { showPromptSettings = false },
            onSave = viewModel::saveAiPromptPreferences
        )
    }

    if (enabled && showModels) {
        AlertDialog(
            onDismissRequest = { showModels = false },
            title = { Text(stringResource(R.string.ai_select_model)) },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    selection.models.forEach { model ->
                        ListItem(
                            headline = { Text(model.name) },
                            supporting = {
                                Text(if (model is ApiAiModel) model.provider.displayLabel() else stringResource(R.string.ai_chat_preview_subtitle))
                            },
                            leading = { RadioButton(selected = selected?.id == model.id, onClick = null) },
                            padding = PaddingValues(0.dp),
                            onClick = {
                                viewModel.selectAiModel(model.id)
                                showModels = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModels = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (enabled && showApiEditor) {
        AiApiModelDialog(apiModelToEdit, viewModel, onDismiss = { showApiEditor = false })
    }
    apiModelToDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { if (!apiOperation.saving) apiModelToDelete = null },
            title = { Text(stringResource(R.string.ai_delete_api_model)) },
            text = {
                Text(if (apiOperation.failed) stringResource(R.string.ai_api_delete_error) else model.name)
            },
            confirmButton = {
                TextButton(
                    enabled = !apiOperation.saving,
                    onClick = { viewModel.deleteApiModel(model) { apiModelToDelete = null } }
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(enabled = !apiOperation.saving, onClick = { apiModelToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    modelToDelete?.let { model ->
        DeleteAIModelDialog(
            modelName = model.name,
            onDismiss = { modelToDelete = null },
            onDelete = {
                viewModel.deleteAiModel(model)
                modelToDelete = null
            },
            blurEffects = blurEffects,
            hazeState = hazeState
        )
    }
}

@Composable
private fun AiSettingsRow(
    title: String,
    leading: @Composable () -> Unit,
    onClick: (() -> Unit)?,
    subtitle: String? = null,
    isLast: Boolean = false,
    showProgress: Boolean = false,
    showError: Boolean = false
) {
    ListItem(
        headline = { Text(title, fontWeight = FontWeight.Medium) },
        supporting = subtitle?.let { label ->
            { Text(label, color = if (showError) MaterialTheme.colorScheme.error else Color.Unspecified) }
        },
        leading = leading,
        trailing = when {
            showProgress -> {
                { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
            }
            onClick != null -> {
                {
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> null
        },
        shape = (if (isLast) ListItemPosition.Bottom else ListItemPosition.Middle).toShape(),
        padding = PaddingValues(0.dp),
        onClick = onClick
    )
}

@Composable
internal fun AiSettingsIcon(icon: ImageVector, background: Color, tint: Color) {
    Box(
        modifier = Modifier.size(48.dp).background(background, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint)
    }
}

@Composable
private fun AiSettingsDeleteIcon() = AiSettingsIcon(
    Icons.Rounded.DeleteOutline,
    MaterialTheme.colorScheme.errorContainer,
    MaterialTheme.colorScheme.onErrorContainer
)
