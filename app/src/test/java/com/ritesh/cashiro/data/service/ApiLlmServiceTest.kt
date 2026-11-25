package com.ritesh.cashiro.data.service

import com.ritesh.cashiro.data.repository.AiApiProvider
import com.ritesh.cashiro.data.repository.apiChatUrl
import com.ritesh.cashiro.domain.service.LlmInput
import com.ritesh.cashiro.domain.service.LlmTurn
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiLlmServiceTest {
    @Test
    fun resolvesBaseUrlsWithoutRedirectingCredentials() {
        assertEquals("https://api.openai.com/v1/chat/completions", apiChatUrl(AiApiProvider.OPENAI.baseUrl).toString())
        assertEquals("https://api.deepseek.com/chat/completions", apiChatUrl(AiApiProvider.DEEPSEEK.baseUrl).toString())
        assertEquals("https://example.test/proxy/v1/chat/completions", apiChatUrl("https://example.test/proxy/v1/").toString())
        assertEquals("https://example.test/v1/chat/completions", apiChatUrl("https://example.test/v1/chat/completions").toString())
        assertNull(apiChatUrl("http://example.test/v1"))
        assertNull(apiChatUrl("https://user:pass@example.test/v1"))
        assertNull(apiChatUrl("https://example.test/v1?token=value"))
    }

    @Test
    fun sendsOrderedTextTurnsAndProviderSpecificOptions() {
        val history = listOf(LlmTurn(LlmInput("Last question"), "Last answer"))
        val deepSeek = chatApiRequest(AiApiProvider.DEEPSEEK, "deepseek-v4-flash", "Use Chinese.", history, "Next question")
        val openAi = chatApiRequest(AiApiProvider.OPENAI, "gpt-5-mini", "Use Chinese.", history, "Next question")

        assertEquals("disabled", deepSeek["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertNull(openAi["thinking"])
        assertEquals(listOf("system", "user", "assistant", "user"), openAi["messages"]?.jsonArray?.map {
            it.jsonObject["role"]?.jsonPrimitive?.content
        })
        assertEquals("Next question", openAi["messages"]?.jsonArray?.last()?.jsonObject?.get("content")?.jsonPrimitive?.content)
    }

    @Test
    fun readsStreamDeltasAndJsonReplies() {
        assertEquals("您好", chatApiContent("""{"choices":[{"delta":{"content":"您好"}}]}"""))
        assertEquals("Hello", chatApiContent("""{"choices":[{"message":{"content":"Hello"}}]}""", "message"))
        assertNull(chatApiContent("""{"choices":[{"delta":{"reasoning_content":"reasoning"}}]}"""))
        assertNull(chatApiContent("""{"choices":[]}"""))
        assertThrows(ApiLlmException::class.java) {
            chatApiContent("""{"error":{"message":"provider error"}}""")
        }
        assertEquals(ApiLlmError.AUTH, apiErrorForStatus(401))
    }
}
