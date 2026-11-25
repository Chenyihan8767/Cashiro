package com.ritesh.cashiro.presentation.ui.features.chat

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ritesh.cashiro.core.Constants
import com.ritesh.cashiro.data.database.entity.ChatMessage
import com.ritesh.cashiro.data.model.ChatAttachment
import com.ritesh.cashiro.data.model.ChatAttachmentKind
import com.ritesh.cashiro.data.preferences.AiDataAccess
import com.ritesh.cashiro.data.preferences.UserPreferencesRepository
import com.ritesh.cashiro.data.repository.ApiAiModel
import com.ritesh.cashiro.data.repository.LlmRepository
import com.ritesh.cashiro.data.repository.ModelRepository
import com.ritesh.cashiro.data.service.ApiLlmError
import com.ritesh.cashiro.data.service.ApiLlmException
import com.ritesh.cashiro.data.service.ChatMediaError
import com.ritesh.cashiro.data.service.ChatMediaException
import com.ritesh.cashiro.data.service.ChatMediaStore
import com.ritesh.cashiro.utils.TokenUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val llmRepository: LlmRepository,
    modelRepository: ModelRepository,
    private val mediaStore: ChatMediaStore,
    private val preferences: UserPreferencesRepository
) : ViewModel() {
    private var generationJob: Job? = null
    private var mediaJob: Job? = null
    private val _mediaState = MutableStateFlow(ChatMediaState())
    val mediaState = _mediaState.asStateFlow()
    private val _currentSessionId = MutableStateFlow(UUID.randomUUID().toString())
    val currentSessionId = _currentSessionId.asStateFlow()
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()
    private val _currentResponse = MutableStateFlow("")
    val currentResponse = _currentResponse.asStateFlow()
    val modelSelection = modelRepository.selection
    val aiPromptPreferences = preferences.aiPromptPreferences
    val isTokenInfoEnabled = preferences.isTokenInfoEnabled

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages = currentSessionId.flatMapLatest { llmRepository.getMessagesForSession(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chatSessions = llmRepository.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chatStats = combine(messages, currentResponse, llmRepository.promptUsage, aiPromptPreferences, modelSelection) { history, reply, usage, settings, selection ->
        val apiModel = selection.selected as? ApiAiModel
        val historyScope = apiModel?.historyScope(settings.historyScope) ?: settings.historyScope
        val matchingUsage = usage?.takeIf { it.settings == settings && it.historyScope == historyScope }
        val systemTokens = matchingUsage?.tokens ?: TokenUtils.estimateTokens(settings.instruction)
        val contextText = history.filter { it.aiHistoryScope == historyScope }.joinToString(" ") { it.message }
        val historyAndReply = "$contextText $reply"
        val estimatedTokens = systemTokens + TokenUtils.estimateTokens(historyAndReply)
        ChatStats(
            messageCount = history.size,
            estimatedTokens = estimatedTokens,
            systemPromptTokens = systemTokens,
            contextUsagePercent = TokenUtils.calculateContextUsagePercent(estimatedTokens, Constants.Ai.CONTEXT_TOKENS)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatStats())

    init {
        viewModelScope.launch {
            combine(preferences.isAiEnabled, modelSelection, aiPromptPreferences) { enabled, selection, settings ->
                (selection.selected to settings).takeIf { enabled }
            }.distinctUntilChanged().drop(1).collect {
                stopGeneration()
                if (modelSelection.value.selected?.supportsMedia != true) clearDraftMedia()
            }
        }

        viewModelScope.launch {
            preferences.getLastChatSessionId()?.let { _currentSessionId.value = it }
            currentSessionId.collect { preferences.saveLastChatSessionId(it) }
        }
    }

    fun saveAiPromptPreferences(instruction: String, dataAccess: Set<AiDataAccess>) {
        viewModelScope.launch {
            stopGeneration()
            preferences.saveAiPromptPreferences(instruction, dataAccess)
        }
    }

    fun sendMessage(message: String) {
        val attachments = _mediaState.value.attachments
        if ((message.isBlank() && attachments.isEmpty()) || _mediaState.value.busy) return
        generate {
            MessageRequest(ChatMessage(message = message.trim(), isUser = true, attachments = attachments))
        }
    }

    fun editMessage(messageId: String, message: String) {
        generate {
            llmRepository.getMessageById(messageId)?.takeIf { it.isUser }?.let { existing ->
                if (message.isBlank() && existing.attachments.isEmpty()) null
                else MessageRequest(existing.copy(message = message.trim()), replaceFrom = existing)
            }
        }
    }

    fun regenerateMessage(messageId: String) {
        generate {
            val response = llmRepository.getMessageById(messageId)?.takeUnless { it.isUser }
            val selected = modelSelection.value.selected as? ApiAiModel
            val expectedScope = selected?.historyScope(preferences.aiPromptPreferences.first().historyScope)
            response?.takeIf { selected == null || it.aiHistoryScope == expectedScope }
                ?.let { llmRepository.getLastUserMessageBefore(it) }?.let { message ->
                MessageRequest(message, replaceFrom = message)
            }
        }
    }

    private fun generate(prepare: suspend () -> MessageRequest?) {
        if (generationJob?.isActive == true || modelSelection.value.selected == null) return
        val sessionId = currentSessionId.value
        val previousJob = generationJob
        _uiState.value = ChatUiState(isLoading = true)
        _currentResponse.value = ""

        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                previousJob?.join()
                prepare()?.let { request ->
                    llmRepository.sendMessageStream(sessionId, request.draft, request.replaceFrom) {
                        if (request.replaceFrom == null) {
                            mediaStore.removeDrafts(request.draft.attachments)
                            _mediaState.update { it.copy(attachments = it.attachments - request.draft.attachments.toSet()) }
                        }
                    }.collect {
                        _currentResponse.value += it
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiLlmException) {
                _uiState.update { it.copy(apiError = error.error) }
            } catch (error: Throwable) {
                Log.e("ChatViewModel", "AI generation failed", error)
                _uiState.update { it.copy(error = ChatErrorMapper.friendly(error)) }
            } finally {
                if (generationJob == coroutineContext.job) {
                    generationJob = null
                    _uiState.update { it.copy(isLoading = false) }
                    _currentResponse.value = ""
                }
            }
        }
        generationJob = job
        job.start()
    }

    fun mediaFile(attachment: ChatAttachment, draft: Boolean = false) = mediaStore.file(attachment, draft)

    fun importMedia(uri: Uri, kind: ChatAttachmentKind, temporarySource: Boolean = false) {
        val state = _mediaState.value
        val max = if (kind == ChatAttachmentKind.IMAGE) ChatMediaStore.MAX_IMAGES else 1
        if (state.attachments.count { it.kind == kind } >= max) return
        val fallback = if (kind == ChatAttachmentKind.IMAGE) ChatMediaError.IMAGE else ChatMediaError.WAV_FORMAT
        addMedia(fallback) { mediaStore.import(uri, kind, temporarySource) }
    }

    fun startRecording(name: String) {
        if (_mediaState.value.attachments.any { it.kind == ChatAttachmentKind.AUDIO }) return
        addMedia(ChatMediaError.RECORDING, recording = true) {
            mediaStore.record(name) { _mediaState.value.recording }
        }
    }

    private fun addMedia(fallback: ChatMediaError, recording: Boolean = false, load: suspend () -> ChatAttachment?) {
        val state = _mediaState.value
        if (state.busy || _uiState.value.isLoading || modelSelection.value.selected?.supportsMedia != true) return
        _mediaState.value = state.copy(busy = true, recording = recording, error = null)
        mediaJob = viewModelScope.launch {
            try {
                val attachment = load()
                if (attachment != null) {
                    _mediaState.update { it.copy(attachments = it.attachments + attachment) }
                } else {
                    _mediaState.update { it.copy(error = fallback) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("ChatViewModel", "Unable to add chat attachment", error)
                _mediaState.update { it.copy(error = (error as? ChatMediaException)?.reason ?: fallback) }
            } finally {
                if (mediaJob == coroutineContext.job) {
                    mediaJob = null
                    _mediaState.update { it.copy(busy = false, recording = false) }
                }
            }
        }
    }

    fun finishRecording() {
        _mediaState.update { it.copy(recording = false) }
    }

    fun cancelRecording() {
        if (_mediaState.value.recording) mediaJob?.cancel()
    }

    fun clearMediaError() {
        _mediaState.update { it.copy(error = null) }
    }

    fun removeDraft(attachment: ChatAttachment) {
        mediaStore.removeDrafts(listOf(attachment))
        _mediaState.update { it.copy(attachments = it.attachments - attachment) }
    }

    private fun clearDraftMedia() {
        mediaJob?.cancel()
        mediaStore.removeDrafts(_mediaState.value.attachments)
        _mediaState.value = ChatMediaState()
    }

    fun stopGeneration() {
        generationJob?.cancel()
        _uiState.update { it.copy(isLoading = false) }
        _currentResponse.value = ""
    }

    fun startNewChat() = loadSession(UUID.randomUUID().toString())

    fun loadSession(sessionId: String) {
        stopGeneration()
        clearDraftMedia()
        _currentSessionId.value = sessionId
        clearError()
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            if (sessionId == currentSessionId.value) {
                val activeJob = generationJob
                startNewChat()
                activeJob?.join()
            }
            llmRepository.deleteAllMessagesForSession(sessionId)
        }
    }

    fun deleteMessage(messageId: String) {
        if (generationJob?.isActive == true) return
        viewModelScope.launch { llmRepository.deleteMessage(messageId) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null, apiError = null) }
    }

    override fun onCleared() {
        clearDraftMedia()
        super.onCleared()
    }

    private data class MessageRequest(val draft: ChatMessage, val replaceFrom: ChatMessage? = null)
}

data class ChatMediaState(
    val attachments: List<ChatAttachment> = emptyList(),
    val busy: Boolean = false,
    val recording: Boolean = false,
    val error: ChatMediaError? = null
)

data class ChatUiState(val isLoading: Boolean = false, val error: String? = null, val apiError: ApiLlmError? = null)

data class ChatStats(
    val messageCount: Int = 0,
    val estimatedTokens: Int = 0,
    val systemPromptTokens: Int = 0,
    val maxTokens: Int = Constants.Ai.CONTEXT_TOKENS,
    val contextUsagePercent: Int = 0
)
