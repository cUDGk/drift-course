package com.driftcourse.app.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 「素」モデル — ペルソナ無しで生モデルと話すための隠しキャラクター。
 * 判別は `card["bare"] == true` のみ。名前や system_prompt は空。
 * モデル一覧には出さず、対話一覧の + FAB だけが利用する。
 */
suspend fun DriftApi.ensureBareCharacter(): Character {
    val existing = listCharacters().firstOrNull { it.isBare() }
    if (existing != null) return existing
    return createCharacter(
        CharacterIn(
            name = "",
            systemPrompt = "",
            card = JsonObject(mapOf("bare" to JsonPrimitive(true))),
        ),
    )
}

fun Character.isBare(): Boolean = card.isBare()

fun JsonObject.isBare(): Boolean {
    val el = this["bare"] ?: return false
    return runCatching { el.jsonPrimitive.booleanOrNull }.getOrNull() == true
}
