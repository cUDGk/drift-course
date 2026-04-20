package com.driftcourse.app.net

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal val driftJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

// SSE は生成が長いので read timeout は実質無制限。接続確立だけは短めで失敗検知。
internal val driftHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

internal fun normalizeBaseUrl(raw: String): String {
    val t = raw.trim()
    return if (t.endsWith("/")) t.dropLast(1) else t
}
