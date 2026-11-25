package com.ritesh.cashiro.data.repository

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import com.ritesh.cashiro.core.Constants
import com.ritesh.cashiro.data.cloud.security.AiApiStore
import com.ritesh.cashiro.core.Constants.Ai.QWEN_MODEL_ID
import com.ritesh.cashiro.data.preferences.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: UserPreferencesRepository,
    private val apiStore: AiApiStore
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Mutex()
    private val imports = File(context.filesDir, "ai-models")
    private val models = MutableStateFlow<List<LocalAiModel>>(emptyList())
    private val apiModels = MutableStateFlow<List<ApiAiModel>>(emptyList())
    val selection = combine(models, apiModels, preferences.aiModelId) { local, remote, selectedId ->
        val available = local + remote
        AiModelSelection(available, available.firstOrNull { it.id == selectedId })
    }.stateIn(scope, SharingStarted.Eagerly, AiModelSelection())

    init {
        scope.launch {
            lock.withLock {
                imports.listFiles()?.forEach { directory ->
                    File(directory, IMPORT_TEMP_FILE).delete()
                    if (directory.list().isNullOrEmpty()) directory.delete()
                }
                preferences.getActiveDownloadId()?.let { id ->
                    val manager = context.getSystemService(DownloadManager::class.java)
                    val completed = manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                        cursor.moveToFirst() &&
                            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
                    } == true
                    if (!completed) manager.remove(id)
                    preferences.clearActiveDownloadId()
                }
                preferences.clearLegacyAiPrompt()
                if (Constants.Device.is64Bit) refreshModels()
                apiModels.value = try {
                    apiStore.models()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
    }

    suspend fun requireSelectedModel(): AiModel {
        check(preferences.isAiEnabled.first()) { "The assistant is disabled." }
        val selectedId = preferences.aiModelId.first()
        return checkNotNull(models.value.firstOrNull { it.id == selectedId && it.file.isFile } ?: apiModels.value.firstOrNull { it.id == selectedId }) {
            "Select an AI model in Settings."
        }
    }

    suspend fun selectModel(id: String) = lock.withLock {
        if ((models.value + apiModels.value).any { it.id == id }) preferences.setAiModelId(id)
    }

    suspend fun saveApiModel(
        previous: ApiAiModel?,
        provider: AiApiProvider,
        baseUrl: String,
        name: String,
        apiKey: String,
        shareFinancialData: Boolean
    ) = lock.withLock {
        val url = requireNotNull(apiChatUrl(provider.baseUrl.ifEmpty { baseUrl }))
        val current = previous?.let { old -> apiModels.value.firstOrNull { it.id == old.id } }
        val id = current?.id ?: UUID.randomUUID().toString()
        val key = apiKey.trim().ifEmpty {
            require(current != null && current.provider == provider && current.baseUrl == url.toString())
            requireNotNull(apiStore.savedKey(id))
        }
        require(key.isNotBlank() && key.all { it in '!'..'~' } && name.isNotBlank())
        val compatible = current?.takeIf {
            it.provider == provider && it.baseUrl == url.toString() && it.name == name.trim() &&
                it.shareFinancialData == shareFinancialData && apiKey.isBlank()
        }
        val updated = ApiAiModel(
            id, provider, url.toString(), name.trim(), shareFinancialData,
            compatible?.historyId ?: UUID.randomUUID().toString()
        )
        val saved = (apiModels.value.filterNot { it.id == id } + updated).sortedBy { it.name.lowercase() }
        apiStore.save(saved, id, key)
        apiModels.value = saved
        preferences.setAiModelId(id)
    }

    suspend fun deleteApiModel(model: ApiAiModel) = lock.withLock {
        val saved = apiModels.value.filterNot { it.id == model.id }
        apiStore.delete(saved, model.id)
        apiModels.value = saved
        if (preferences.aiModelId.first() == model.id) {
            preferences.setAiModelId((models.value + saved).firstOrNull()?.id ?: QWEN_MODEL_ID)
        }
    }

    suspend fun importModel(uri: Uri, onProgress: (Int?) -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            val resolver = context.contentResolver
            val (name, size) = resolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to cursor.getLong(1) else null
            } ?: throw IOException("Model file details unavailable")
            if (name.isNullOrBlank() || File(name).name != name ||
                !name.endsWith(".litertlm", ignoreCase = true)
            ) {
                throw ModelImportException(ModelImportError.UNSUPPORTED)
            }

            val directory = File(imports, UUID.randomUUID().toString())
            val destination = File(directory, name)
            val temporary = File(directory, IMPORT_TEMP_FILE)
            try {
                if (!directory.mkdirs()) throw IOException("Cannot create model directory")
                if (size > directory.usableSpace) {
                    throw ModelImportException(ModelImportError.NO_SPACE)
                }
                resolver.openInputStream(uri)?.buffered()?.use { input ->
                    val header = ByteArray(MODEL_MAGIC.length)
                    if (input.readNBytesCompat(header) != header.size ||
                        !header.contentEquals(MODEL_MAGIC.toByteArray(Charsets.US_ASCII))
                    ) {
                        throw ModelImportException(ModelImportError.UNSUPPORTED)
                    }
                    temporary.outputStream().buffered().use { output ->
                        output.write(header)
                        var copied = header.size.toLong()
                        var progress: Int? = null
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            output.write(buffer, 0, count)
                            copied += count
                            val percent = if (size > 0) (copied * 100 / size).toInt().coerceAtMost(99) else null
                            if (percent != progress) {
                                onProgress(percent)
                                progress = percent
                            }
                        }
                        if (size > 0 && copied != size) throw IOException("Incomplete model file")
                    }
                } ?: throw IOException("Cannot open the selected model")
                currentCoroutineContext().ensureActive()
                if (!temporary.renameTo(destination)) throw IOException("Cannot save the imported model")
                refreshModels()
                preferences.setAiModelId(directory.name)
            } finally {
                temporary.delete()
                if (!destination.exists()) directory.delete()
            }
        }
    }

    suspend fun deleteModel(model: LocalAiModel) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (models.value.none { it == model }) return@withLock
            if (!model.file.delete() && model.file.exists()) throw IOException("Cannot delete model")
            if (model.id != QWEN_MODEL_ID) model.file.parentFile?.delete()
            refreshModels()
            if (preferences.aiModelId.first() == model.id) {
                preferences.setAiModelId((models.value + apiModels.value).firstOrNull()?.id ?: QWEN_MODEL_ID)
            }
        }
    }

    private fun refreshModels() {
        val qwen = qwenFile()?.takeIf { it.isFile && it.length() == Constants.Ai.MODEL_SIZE_BYTES }
        val legacyQwen = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve(Constants.Ai.LEGACY_MODEL_FILE_NAME)
            ?.takeIf { it.isFile && it.length() == Constants.Ai.LEGACY_MODEL_SIZE_BYTES }
        models.value = buildList {
            (qwen ?: legacyQwen)?.let { add(LocalAiModel(QWEN_MODEL_ID, QWEN_NAME, it)) }
            imports.listFiles()?.forEach { directory ->
                directory.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".litertlm", ignoreCase = true) }?.let {
                    val gemmaSize = gemma4Mobile.matchEntire(it.nameWithoutExtension)?.groupValues?.get(1)
                    val name = gemmaSize?.let { size -> "Gemma 4 ${size.uppercase()}" } ?: it.nameWithoutExtension
                    add(LocalAiModel(directory.name, name, it, gemmaSize != null))
                }
            }
        }.sortedBy { it.name.lowercase() }
    }

    private fun qwenFile(): File? = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?.resolve(Constants.Ai.MODEL_FILE_NAME)

    private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var count = 0
        while (count < buffer.size) {
            val read = read(buffer, count, buffer.size - count)
            if (read == -1) break
            count += read
        }
        return count
    }

    private companion object {
        val gemma4Mobile = Regex("gemma[-_. ]*4[-_. ]*(e[24]b)(?:[-_. ].*)?", RegexOption.IGNORE_CASE)
        const val QWEN_NAME = "Qwen 2.5"
        const val MODEL_MAGIC = "LITERTLM"
        const val IMPORT_TEMP_FILE = "import.part"
    }
}

data class LocalAiModel(
    override val id: String,
    override val name: String,
    val file: File,
    override val supportsMedia: Boolean = false
) : AiModel
data class AiModelSelection(val models: List<AiModel> = emptyList(), val selected: AiModel? = null)

enum class ModelImportError { UNSUPPORTED, NO_SPACE, IO, DELETE }
class ModelImportException(val reason: ModelImportError) : IOException()
