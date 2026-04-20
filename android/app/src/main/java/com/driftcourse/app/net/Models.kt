package com.driftcourse.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ChatRequest(
    val messages: List<ChatMessage>,
    val temperature: Float = 0.7f,
    @SerialName("top_p") val topP: Float = 0.9f,
    @SerialName("max_tokens") val maxTokens: Int = 1024,
    val stream: Boolean = true,
    @SerialName("cache_prompt") val cachePrompt: Boolean = true,
)

@Serializable
data class ChatChunk(
    val choices: List<ChatChoice> = emptyList(),
)

@Serializable
data class ChatChoice(
    val delta: ChatDelta = ChatDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatDelta(
    val content: String? = null,
    val role: String? = null,
)

@Serializable
data class HealthResponse(
    val ok: Boolean,
    @SerialName("current_model") val currentModel: String = "",
    @SerialName("draft_model") val draftModel: String = "",
)

@Serializable
data class ModelInfo(
    val name: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("is_current") val isCurrent: Boolean,
    @SerialName("is_draft") val isDraft: Boolean,
)

@Serializable
data class ModelsResponse(
    val current: String,
    val draft: String,
    val available: List<ModelInfo>,
)
