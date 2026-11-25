package com.ritesh.cashiro.data.repository

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
enum class AiApiProvider(val label: String, val baseUrl: String, val exampleModel: String) {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-v4-flash"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-5-mini"),
    CUSTOM("", "", "")
}

@Serializable
data class ApiAiModel(
    override val id: String,
    val provider: AiApiProvider,
    val baseUrl: String,
    override val name: String,
    val shareFinancialData: Boolean = false,
    val historyId: String = id
) : AiModel {
    override val supportsMedia: Boolean get() = false

    fun historyScope(promptScope: String): String = "$promptScope:$historyId"
}

internal fun apiChatUrl(value: String): HttpUrl? {
    val url = value.trim().toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    } ?: return null
    val path = url.encodedPath.trimEnd('/')
    return url.newBuilder().encodedPath(
        if (path.endsWith("/chat/completions")) path else "$path/chat/completions"
    ).build()
}

sealed interface AiModel {
    val id: String
    val name: String
    val supportsMedia: Boolean
}
