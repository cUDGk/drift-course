package com.driftcourse.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(JSON_MEDIA)

class DriftApi(
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String,
) {
    suspend fun health(): HealthResponse = withContext(Dispatchers.IO) {
        val json = request("GET", "/health", null, requireAuth = false)
        driftJson.decodeFromString(HealthResponse.serializer(), json)
    }

    suspend fun listModels(): ModelsResponse = withContext(Dispatchers.IO) {
        val json = request("GET", "/models", null, requireAuth = true)
        driftJson.decodeFromString(ModelsResponse.serializer(), json)
    }

    suspend fun listCharacters(): List<Character> = withContext(Dispatchers.IO) {
        val json = request("GET", "/characters", null)
        driftJson.decodeFromString(ListSerializer(Character.serializer()), json)
    }

    suspend fun createCharacter(body: CharacterIn): Character = withContext(Dispatchers.IO) {
        val json = request("POST", "/characters", driftJson.encodeToString(CharacterIn.serializer(), body))
        driftJson.decodeFromString(Character.serializer(), json)
    }

    suspend fun getCharacter(id: String): Character = withContext(Dispatchers.IO) {
        val json = request("GET", "/characters/$id", null)
        driftJson.decodeFromString(Character.serializer(), json)
    }

    suspend fun patchCharacter(id: String, body: CharacterPatch): Character = withContext(Dispatchers.IO) {
        val json = request("PATCH", "/characters/$id", driftJson.encodeToString(CharacterPatch.serializer(), body))
        driftJson.decodeFromString(Character.serializer(), json)
    }

    suspend fun deleteCharacter(id: String) {
        withContext(Dispatchers.IO) { request("DELETE", "/characters/$id", null) }
    }

    suspend fun listConversations(characterId: String? = null): List<Conversation> = withContext(Dispatchers.IO) {
        val path = if (characterId != null) "/conversations?character_id=$characterId" else "/conversations"
        val json = request("GET", path, null)
        driftJson.decodeFromString(ListSerializer(Conversation.serializer()), json)
    }

    suspend fun createConversation(characterId: String, title: String = ""): Conversation = withContext(Dispatchers.IO) {
        val json = request(
            "POST",
            "/conversations",
            driftJson.encodeToString(NewConversation.serializer(), NewConversation(characterId, title)),
        )
        driftJson.decodeFromString(Conversation.serializer(), json)
    }

    suspend fun getConversation(id: String): Conversation = withContext(Dispatchers.IO) {
        val json = request("GET", "/conversations/$id", null)
        driftJson.decodeFromString(Conversation.serializer(), json)
    }

    suspend fun deleteConversation(id: String) {
        withContext(Dispatchers.IO) { request("DELETE", "/conversations/$id", null) }
    }

    suspend fun listMessages(convId: String): List<Message> = withContext(Dispatchers.IO) {
        val json = request("GET", "/conversations/$convId/messages", null)
        driftJson.decodeFromString(ListSerializer(Message.serializer()), json)
    }

    suspend fun getMemory(convId: String): MemorySnapshot = withContext(Dispatchers.IO) {
        val json = request("GET", "/conversations/$convId/memory", null)
        driftJson.decodeFromString(MemorySnapshot.serializer(), json)
    }

    suspend fun patchMemory(convId: String, layer: String, content: String) {
        withContext(Dispatchers.IO) {
            request(
                "PATCH",
                "/conversations/$convId/memory/$layer",
                driftJson.encodeToString(MemoryPatch.serializer(), MemoryPatch(content)),
            )
        }
    }

    private suspend fun request(method: String, path: String, jsonBody: String?, requireAuth: Boolean = true): String {
        val url = normalizeBaseUrl(baseUrlProvider()) + path
        val builder = Request.Builder().url(url)
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "POST" -> builder.post(jsonBody?.toRequestBody(JSON_MEDIA) ?: EMPTY_BODY)
            "PATCH" -> builder.patch(jsonBody?.toRequestBody(JSON_MEDIA) ?: EMPTY_BODY)
            else -> error("unsupported method: $method")
        }
        if (requireAuth) {
            val token = tokenProvider()
            if (token.isBlank()) error("トークンが未設定です")
            builder.header("Authorization", "Bearer $token")
        }
        val response = driftHttpClient.newCall(builder.build()).await()
        response.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code}: $body")
            return body
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response)
            else response.close()
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
