package com.ritesh.cashiro.presentation.ui.features.settings

import com.ritesh.cashiro.data.repository.ModelImportError

data class SettingsUiState(
    val importExportMessage: String? = null,
    val exportedBackupFile: java.io.File? = null,
    val isSeeding: Boolean = false,
    val seedMessage: String? = null
)

data class ModelImportUiState(
    val running: Boolean = false,
    val progress: Int? = null,
    val error: ModelImportError? = null
)

data class ApiModelOperation(val saving: Boolean = false, val failed: Boolean = false)
