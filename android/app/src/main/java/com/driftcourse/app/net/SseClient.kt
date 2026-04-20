package com.driftcourse.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class SseClient(
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String,
) {
    /**
     * llama-server 由来の OpenAI 互換 SSE をパースして content delta だけ流す。
     * `data: {...}` のバイト境界が行で割れるので BufferedSource.readUtf8Line で 1 行ずつ読む。
     * `data: [DONE]` で終端。choices[0].delta.content が欠けている chunk (role のみ等) は黙って飛ばす。
     */
    fun chat(messages: List<ChatMessage>): Flow<String> = stream(
        path = "/v1/chat",
        jsonBody = driftJson.encodeToString(ChatRequest.serializer(), ChatRequest(messages = messages)),
    )

    fun convMessage(convId: String, body: PostMessage): Flow<String> = stream(
        path = "/conversations/$convId/messages",
        jsonBody = driftJson.encodeToString(PostMessage.serializer(), body),
    )

    private fun stream(path: String, jsonBody: String): Flow<String> = callbackFlow {
        val baseUrl = normalizeBaseUrl(baseUrlProvider())
        val token = tokenProvider()
        if (token.isBlank()) {
            close(IllegalStateException("token not set — open settings to pair with server"))
            return@callbackFlow
        }

        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .post(requestBody)
            .build()

        val call = driftHttpClient.newCall(request)
        val response = try {
            call.execute()
        } catch (t: Throwable) {
            close(t)
            return@callbackFlow
        }

        if (!response.isSuccessful) {
            val errBody = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            response.close()
            close(IllegalStateException("HTTP ${response.code}: $errBody"))
            return@callbackFlow
        }

        val source = response.body?.source()
        if (source == null) {
            response.close()
            close(IllegalStateException("empty response body"))
            return@callbackFlow
        }

        try {
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty()) continue
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload == "[DONE]") break
                if (payload.isEmpty()) continue
                val chunk = runCatching {
                    driftJson.decodeFromString(ChatChunk.serializer(), payload)
                }.getOrNull() ?: continue
                val delta = chunk.choices.firstOrNull()?.delta?.content
                if (!delta.isNullOrEmpty()) {
                    trySend(delta)
                }
            }
            close()
        } catch (t: Throwable) {
            close(t)
        } finally {
            response.close()
        }

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)
}
