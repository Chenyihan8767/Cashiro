package com.ritesh.cashiro.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ChatAttachmentKind(val extension: String) {
    IMAGE("jpg"),
    AUDIO("wav")
}

@Serializable
data class ChatAttachment(val id: String, val kind: ChatAttachmentKind, val name: String)
