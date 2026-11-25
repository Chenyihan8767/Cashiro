package com.ritesh.cashiro.data.repository

import androidx.room.withTransaction
import com.ritesh.cashiro.data.database.CashiroDatabase
import com.ritesh.cashiro.data.database.dao.ChatDao
import com.ritesh.cashiro.data.database.dao.ChatSessionDao
import com.ritesh.cashiro.data.database.entity.ChatMessage
import com.ritesh.cashiro.data.database.entity.ChatSession
import com.ritesh.cashiro.data.model.ChatAttachment
import com.ritesh.cashiro.data.model.ChatAttachmentKind
import com.ritesh.cashiro.data.preferences.AiPromptPreferences
import com.ritesh.cashiro.data.preferences.UserPreferencesRepository
import com.ritesh.cashiro.data.service.ChatMediaStore
import com.ritesh.cashiro.data.service.ApiLlmService
import com.ritesh.cashiro.domain.service.LlmInput
import com.ritesh.cashiro.domain.service.LlmMedia
import com.ritesh.cashiro.domain.service.LlmService
import com.ritesh.cashiro.domain.service.LlmTurn
import com.ritesh.cashiro.utils.TokenUtils
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmRepository @Inject constructor(
    private val llmService: LlmService,
    private val apiService: ApiLlmService,
    private val database: CashiroDatabase,
    private val chatDao: ChatDao,
    private val chatSessionDao: ChatSessionDao,
    private val mediaStore: ChatMediaStore,
    private val modelRepository: ModelRepository,
    private val aiContextRepository: AiContextRepository,
    private val preferences: UserPreferencesRepository,
    private val currencyRepository: CurrencyRepository
) {
    private val _promptUsage = MutableStateFlow<AiPromptUsage?>(null)
    val promptUsage = _promptUsage.asStateFlow()

    fun getMessagesForSession(sessionId: String): Flow<List<ChatMessage>> = chatDao.getMessagesForSession(sessionId)
    fun getAllSessions(): Flow<List<ChatSession>> = chatSessionDao.getAllSessions()

    fun sendMessageStream(
        sessionId: String,
        draft: ChatMessage,
        replaceFrom: ChatMessage? = null,
        onSaved: () -> Unit = {}
    ): Flow<String> = flow {
        val model = modelRepository.requireSelectedModel()
        val settings = preferences.aiPromptPreferences.first()
        val apiModel = model as? ApiAiModel
        val scope = apiModel?.historyScope(settings.historyScope) ?: settings.historyScope
        val promptSettings = if (apiModel != null && !apiModel.shareFinancialData) {
            settings.copy(dataAccess = emptySet())
        } else settings
        check(model.supportsMedia || draft.attachments.isEmpty()) { "The selected model only accepts text." }

        val storedHistory = chatDao.getMessagesForAiContext(sessionId, scope)
            .filter { replaceFrom == null || it.timestamp < replaceFrom.timestamp }
        val pairs = storedHistory.zipWithNext().filter { (user, assistant) -> user.isUser && !assistant.isUser }
        val recentMedia = if (model.supportsMedia && draft.attachments.isEmpty()) {
            pairs.lastOrNull { (user, _) -> user.attachments.isNotEmpty() }?.first?.id
        } else null
        val history = pairs.mapNotNull { (user, assistant) ->
            val media = if (user.id == recentMedia) user.attachments.toModelMedia() else emptyList()
            if (user.message.isBlank() && media.isEmpty()) null
            else LlmTurn(LlmInput(user.message, media), assistant.message)
        }

        val prompt = if (promptSettings.dataAccess.isEmpty()) {
            promptSettings.instruction
        } else {
            val currency = currencyRepository.effectiveBaseCurrencyCode.first()
            val data = aiContextRepository.getChatContext(promptSettings.dataAccess, currency)
            buildAiSystemPrompt(promptSettings, data, currency)
        }
        _promptUsage.value = AiPromptUsage(settings, scope, TokenUtils.estimateTokens(prompt))
        check(preferences.aiPromptPreferences.first() == settings && modelRepository.requireSelectedModel() == model) {
            "AI settings changed. Send the message again to use the new settings."
        }

        withContext(NonCancellable) {
            val newMessage = draft.copy(sessionId = sessionId, aiHistoryScope = scope)
            if (replaceFrom == null) {
                mediaStore.saveDrafts(draft.attachments)
            }
            val removed = try {
                database.withTransaction {
                    if (chatDao.getMessageCountForSession(sessionId) == 0) {
                        val title = draft.message.trim().split(Regex("\\s+")).take(5).joinToString(" ")
                            .ifBlank { draft.attachments.firstOrNull()?.name.orEmpty() }.take(70)
                        chatSessionDao.insertSession(ChatSession(id = sessionId, title = title))
                    }
                    val removedMedia = if (replaceFrom != null) {
                        val later = chatDao.getMessagesAfter(sessionId, replaceFrom.timestamp)
                        chatDao.deleteMessagesAfter(sessionId, replaceFrom.timestamp)
                        later.flatMap { it.attachments }
                    } else emptyList()
                    chatDao.insertMessage(newMessage)
                    removedMedia
                }
            } catch (error: Exception) {
                if (replaceFrom == null) mediaStore.removeSaved(draft.attachments)
                throw error
            }
            mediaStore.removeSaved(removed)
            onSaved()
        }

        val media = draft.attachments.toModelMedia()
        val question = draft.message.ifBlank {
            if (media.any { it is LlmMedia.Audio }) "Transcribe the audio." else "Describe the image."
        }
        val response = StringBuilder()
        try {
            val stream = when (model) {
                is LocalAiModel -> llmService.generateResponseStream(model.file.absolutePath, prompt, history, LlmInput(question, media))
                is ApiAiModel -> apiService.stream(model, prompt, history, question)
            }
            stream.collect { part ->
                response.append(part)
                emit(part)
            }
        } finally {
            if (response.isNotEmpty()) {
                withContext(NonCancellable) {
                    chatDao.insertMessage(
                        ChatMessage(
                            message = response.toString(), isUser = false, sessionId = sessionId,
                            aiHistoryScope = scope, modelName = model.name
                        )
                    )
                }
            }
        }
    }

    private fun List<ChatAttachment>.toModelMedia(): List<LlmMedia> = mapNotNull { attachment ->
        val file = mediaStore.file(attachment)
        if (!file.isFile) return@mapNotNull null
        when (attachment.kind) {
            ChatAttachmentKind.IMAGE -> LlmMedia.Image(file.absolutePath)
            ChatAttachmentKind.AUDIO -> LlmMedia.Audio(file.absolutePath)
        }
    }

    suspend fun deleteAllMessagesForSession(sessionId: String) {
        val removed = database.withTransaction {
            val messages = chatDao.getMessagesInSession(sessionId)
            chatDao.deleteMessagesForSession(sessionId)
            chatSessionDao.deleteSession(sessionId)
            messages.flatMap { it.attachments }
        }
        mediaStore.removeSaved(removed)
    }

    suspend fun deleteAllChats() {
        val removed = database.withTransaction {
            val messages = chatDao.getAllMessages().first()
            chatDao.deleteAllMessages()
            chatSessionDao.deleteAllSessions()
            messages.flatMap { it.attachments }
        }
        mediaStore.removeSaved(removed)
    }

    suspend fun deleteMessage(messageId: String) {
        val message = database.withTransaction {
            val existing = chatDao.getMessageById(messageId)
            chatDao.deleteMessage(messageId)
            existing
        }
        mediaStore.removeSaved(message?.attachments.orEmpty())
    }

    suspend fun getMessageById(messageId: String): ChatMessage? = chatDao.getMessageById(messageId)

    suspend fun getLastUserMessageBefore(message: ChatMessage): ChatMessage? =
        chatDao.getLastUserMessageBefore(message.sessionId, message.timestamp)
}

data class AiPromptUsage(
    val settings: AiPromptPreferences,
    val historyScope: String,
    val tokens: Int
)
