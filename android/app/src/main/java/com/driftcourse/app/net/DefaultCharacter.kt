package com.driftcourse.app.net

import kotlinx.serialization.json.JsonObject

const val DEFAULT_CHARACTER_NAME = "デフォルト"

suspend fun DriftApi.ensureDefaultCharacter(): Character {
    val existing = listCharacters().firstOrNull { it.name == DEFAULT_CHARACTER_NAME }
    if (existing != null) return existing
    return createCharacter(
        CharacterIn(
            name = DEFAULT_CHARACTER_NAME,
            systemPrompt = "",
            card = JsonObject(emptyMap()),
        ),
    )
}
