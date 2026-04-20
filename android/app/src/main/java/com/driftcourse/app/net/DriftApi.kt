package com.driftcourse.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DriftApi(
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String,
) {
    suspend fun health(): HealthResponse = withContext(Dispatchers.IO) {
        val json = get("/health", requireAuth = false)
        driftJson.decodeFromString(HealthResponse.serializer(), json)
    }

    suspend fun listModels(): ModelsResponse = withContext(Dispatchers.IO) {
        val json = get("/models", requireAuth = true)
        driftJson.decodeFromString(ModelsResponse.serializer(), json)
    }

    private suspend fun get(path: String, requireAuth: Boolean): String {
        val url = normalizeBaseUrl(baseUrlProvider()) + path
        val builder = Request.Builder().url(url).get()
        if (requireAuth) {
            val token = tokenProvider()
            if (token.isBlank()) error("token not set")
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
