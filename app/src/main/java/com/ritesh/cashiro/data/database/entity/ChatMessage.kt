package com.ritesh.cashiro.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ritesh.cashiro.data.model.ChatAttachment
import java.util.UUID

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val message: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "false")
    val isSystemPrompt: Boolean = false, // Hide from UI but include in context
    @ColumnInfo(name = "session_id", defaultValue = "legacy_session")
    val sessionId: String = "legacy_session",
    val aiHistoryScope: String? = null,
    val modelName: String? = null,
    @ColumnInfo(defaultValue = "'[]'")
    val attachments: List<ChatAttachment> = emptyList()
)