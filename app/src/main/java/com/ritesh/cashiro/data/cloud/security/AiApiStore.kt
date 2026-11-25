package com.ritesh.cashiro.data.cloud.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ritesh.cashiro.data.repository.ApiAiModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("DEPRECATION")
@Singleton
class AiApiStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            "cashiro_ai_api",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    suspend fun models(): List<ApiAiModel> = withContext(Dispatchers.IO) {
        if (context.getSharedPreferences("cashiro_ai_api", Context.MODE_PRIVATE).all.isNotEmpty()) {
            readModels()
        } else emptyList()
    }

    suspend fun key(model: ApiAiModel): String? = withContext(Dispatchers.IO) {
        synchronized(prefs) {
            if (model in readModels()) prefs.getString("key_${model.id}", null) else null
        }
    }

    suspend fun savedKey(id: String): String? = withContext(Dispatchers.IO) { prefs.getString("key_$id", null) }

    suspend fun save(models: List<ApiAiModel>, id: String, key: String) = withContext(Dispatchers.IO) {
        synchronized(prefs) {
            check(prefs.edit().putString(MODELS, Json.encodeToString(models)).putString("key_$id", key).commit()) {
                "Could not save API configuration."
            }
        }
    }

    suspend fun delete(models: List<ApiAiModel>, id: String) = withContext(Dispatchers.IO) {
        synchronized(prefs) {
            check(prefs.edit().putString(MODELS, Json.encodeToString(models)).remove("key_$id").commit()) {
                "Could not remove API configuration."
            }
        }
    }

    private fun readModels(): List<ApiAiModel> =
        prefs.getString(MODELS, null)?.let { Json.decodeFromString<List<ApiAiModel>>(it) }.orEmpty()

    private companion object {
        const val MODELS = "models"
    }
}
