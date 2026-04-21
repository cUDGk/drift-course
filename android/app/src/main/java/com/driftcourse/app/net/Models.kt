package com.driftcourse.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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

@Serializable
data class LoadModelRequest(
    val model: String,
    val draft: String? = null,
)

@Serializable
data class ModelLoadResponse(
    val current: String,
    val draft: String,
)

@Serializable
data class Character(
    val id: String,
    val name: String,
    @SerialName("system_prompt") val systemPrompt: String,
    val card: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class CharacterIn(
    val name: String,
    @SerialName("system_prompt") val systemPrompt: String = "",
    val card: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class CharacterPatch(
    val name: String? = null,
    @SerialName("system_prompt") val systemPrompt: String? = null,
    val card: JsonObject? = null,
)

@Serializable
data class Conversation(
    val id: String,
    @SerialName("character_id") val characterId: String,
    val title: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    // primary character 以外の追加参加者。空なら従来どおり 1 対 1 のソロ会話。
    val members: List<String> = emptyList(),
)

@Serializable
data class NewConversation(
    @SerialName("character_id") val characterId: String,
    val title: String = "",
)

@Serializable
data class Message(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    val role: String,
    val content: String,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class PostMessage(
    val content: String,
    val temperature: Float = 0.7f,
    @SerialName("top_p") val topP: Float = 0.9f,
    @SerialName("max_tokens") val maxTokens: Int = 1024,
)

@Serializable
data class MemorySnapshot(
    val char: String = "",
    val recent: String = "",
    val mid: String = "",
    val long: String = "",
)

@Serializable
data class MemoryPatch(
    val content: String,
)

@Serializable
data class ForkRequest(
    @SerialName("from_message_id") val fromMessageId: String,
    val inclusive: Boolean = false,
    val title: String = "",
)

@Serializable
data class ConversationPatch(
    val title: String,
)

@Serializable
data class MembersPatch(
    val members: List<String>,
)

@Serializable
data class MessagePatch(
    val content: String,
)

@Serializable
data class CharacterCopyRequest(
    val name: String? = null,
)
