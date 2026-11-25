package com.ritesh.cashiro.data.service

import com.ritesh.cashiro.data.cloud.security.AiApiStore
import com.ritesh.cashiro.data.repository.AiApiProvider
import com.ritesh.cashiro.data.repository.ApiAiModel
import com.ritesh.cashiro.data.repository.apiChatUrl
import com.ritesh.cashiro.domain.service.LlmTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiLlmService @Inject constructor(private val store: AiApiStore) {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun stream(model: ApiAiModel, systemPrompt: String, history: List<LlmTurn>, text: String): Flow<String> = callbackFlow {
        val key = store.key(model) ?: throw ApiLlmException(ApiLlmError.AUTH)
        val request = Request.Builder()
            .url(checkNotNull(apiChatUrl(model.baseUrl)))
            .header("Authorization", "Bearer $key")
            .header("Accept", "text/event-stream")
            .post(chatApiRequest(model.provider, model.name, systemPrompt, history, text).toString().toRequestBody(JSON))
            .build()
        val call = client.newCall(request)
        launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw ApiLlmException(apiErrorForStatus(response.code))
                    val body = response.body ?: throw ApiLlmException(ApiLlmError.EMPTY)
                    if (body.contentType()?.subtype == "json") {
                        val text = chatApiContent(body.string(), "message")
                        if (text.isNullOrBlank()) throw ApiLlmException(ApiLlmError.EMPTY)
                        send(text)
                        return@use
                    }
                    val source = body.source()
                    var hasText = false
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.substringAfter(':').trimStart()
                        if (payload == "[DONE]") break
                        if (payload.isBlank()) continue
                        val part = chatApiContent(payload)
                        if (!part.isNullOrEmpty()) {
                            send(part)
                            hasText = true
                        }
                    }
                    if (!hasText) throw ApiLlmException(ApiLlmError.EMPTY)
                }
                close()
            } catch (error: ApiLlmException) {
                close(error)
            } catch (_: IOException) {
                close(ApiLlmException(ApiLlmError.NETWORK))
            } catch (_: IllegalArgumentException) {
                close(ApiLlmException(ApiLlmError.RESPONSE))
            }
        }
        awaitClose { call.cancel() }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

internal fun chatApiRequest(
    provider: AiApiProvider,
    model: String,
    systemPrompt: String,
    history: List<LlmTurn>,
    text: String
): JsonObject {
    fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }
    return buildJsonObject {
        put("model", model)
        put("stream", true)
        if (provider == AiApiProvider.DEEPSEEK) {
            put("thinking", buildJsonObject { put("type", "disabled") })
        }
        putJsonArray("messages") {
            add(message("system", systemPrompt))
            history.forEach {
                add(message("user", it.question.text))
                add(message("assistant", it.answer))
            }
            add(message("user", text))
        }
    }
}

internal fun chatApiContent(payload: String, field: String = "delta"): String? {
    val event = Json.parseToJsonElement(payload).jsonObject
    if (event["error"] != null) throw ApiLlmException(ApiLlmError.RESPONSE)
    return event["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        ?.get(field)?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
}

internal fun apiErrorForStatus(code: Int) = when (code) {
    401, 403 -> ApiLlmError.AUTH
    402 -> ApiLlmError.BILLING
    404 -> ApiLlmError.NOT_FOUND
    429 -> ApiLlmError.RATE_LIMIT
    in 500..599 -> ApiLlmError.SERVER
    else -> ApiLlmError.RESPONSE
}

enum class ApiLlmError { AUTH, BILLING, NOT_FOUND, RATE_LIMIT, SERVER, RESPONSE, NETWORK, EMPTY }
class ApiLlmException(val error: ApiLlmError) : IOException()
