package com.ritesh.cashiro.data.service

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.ritesh.cashiro.core.Constants
import com.ritesh.cashiro.domain.service.LlmInput
import com.ritesh.cashiro.domain.service.LlmMedia
import com.ritesh.cashiro.domain.service.LlmService
import com.ritesh.cashiro.domain.service.LlmTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class LiteRtLmServiceImpl(private val context: Context) : LlmService {
    private val lock = Mutex()
    private var engine: Engine? = null
    private var loadedModel: EngineRequest? = null

    override fun generateResponseStream(
        modelPath: String,
        systemPrompt: String,
        history: List<LlmTurn>,
        input: LlmInput
    ): Flow<String> = flow {
        lock.withLock {
            val media = history.flatMap { it.question.media } + input.media
            val request = EngineRequest(
                modelPath,
                vision = media.any { it is LlmMedia.Image },
                audio = media.any { it is LlmMedia.Audio }
            )
            if (loadedModel?.supports(request) != true) closeEngine()
            val currentEngine = engine ?: openModel(request)
            currentCoroutineContext().ensureActive()

            val config = ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                initialMessages = history.flatMap { listOf(Message.user(it.question.toContents()), Message.model(it.answer)) },
                samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.3)
            )
            currentEngine.createConversation(config).use { conversation ->
                try {
                    conversation.sendMessageAsync(input.toContents()).collect { message ->
                        val text = message.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                        if (text.isNotEmpty()) emit(text)
                    }
                } catch (error: CancellationException) {
                    runCatching { conversation.cancelProcess() }
                    throw error
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun reset() = withContext(Dispatchers.IO) {
        lock.withLock { closeEngine() }
    }

    private fun closeEngine() {
        try {
            engine?.close()
        } finally {
            engine = null
            loadedModel = null
        }
    }

    private fun openModel(request: EngineRequest): Engine {
        val cacheDir = File(context.cacheDir, "litert_lm_cache").also { it.mkdirs() }
        val hasGpu = try {
            System.loadLibrary("OpenCL")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
        val cpu = Backend.CPU()
        val gpu = Backend.GPU()
        val backends = buildList {
            if (hasGpu) {
                add(gpu to gpu)
                if (request.vision) add(cpu to gpu)
            }
            add(cpu to cpu)
        }
        var failure: Throwable? = null
        for ((backend, visionBackend) in backends) {
            var candidate: Engine? = null
            try {
                candidate = Engine(
                    EngineConfig(
                        modelPath = request.modelPath,
                        backend = backend,
                        visionBackend = visionBackend.takeIf { request.vision },
                        audioBackend = cpu.takeIf { request.audio },
                        maxNumTokens = Constants.Ai.CONTEXT_TOKENS,
                        cacheDir = cacheDir.absolutePath
                    )
                )
                candidate.initialize()
                engine = candidate
                loadedModel = request
                Log.i(TAG, "Loaded ${File(request.modelPath).name} with $backend (vision=${request.vision}, audio=${request.audio})")
                return candidate
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (candidate?.isInitialized() == true) runCatching { candidate.close() }
                Log.w(TAG, "Model initialization failed with $backend", error)
                failure = error
            }
        }
        throw IllegalStateException("Could not load the selected model. Try another LiteRT-LM model.", failure)
    }

    private fun LlmInput.toContents() = Contents.of(buildList {
        media.forEach {
            add(when (it) {
                is LlmMedia.Image -> Content.ImageFile(it.path)
                is LlmMedia.Audio -> Content.AudioFile(it.path)
            })
        }
        if (text.isNotBlank()) add(Content.Text(text))
    })

    private data class EngineRequest(val modelPath: String, val vision: Boolean, val audio: Boolean) {
        fun supports(request: EngineRequest): Boolean =
            modelPath == request.modelPath && (vision || !request.vision) && (audio || !request.audio)
    }

    private companion object {
        const val TAG = "LiteRtLmServiceImpl"
    }
}
