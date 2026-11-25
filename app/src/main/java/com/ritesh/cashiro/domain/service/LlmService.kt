package com.ritesh.cashiro.domain.service

import kotlinx.coroutines.flow.Flow

sealed interface LlmMedia {
    data class Image(val path: String) : LlmMedia
    data class Audio(val path: String) : LlmMedia
}

data class LlmInput(val text: String, val media: List<LlmMedia> = emptyList())
data class LlmTurn(val question: LlmInput, val answer: String)

interface LlmService {
    fun generateResponseStream(
        modelPath: String,
        systemPrompt: String,
        history: List<LlmTurn>,
        input: LlmInput
    ): Flow<String>

    suspend fun reset()
}
