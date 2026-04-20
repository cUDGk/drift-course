package com.driftcourse.app.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `Character.card` (任意の JSON オブジェクト) から文字列値を安全に読み出すヘルパ。
 * 複数の UI 画面で同じことをしていたのを 1 か所に集約する。
 */
internal fun readCardString(card: JsonObject, key: String): String {
    val el = card[key] ?: return ""
    return runCatching { el.jsonPrimitive.contentOrNull.orEmpty() }.getOrDefault("")
}
