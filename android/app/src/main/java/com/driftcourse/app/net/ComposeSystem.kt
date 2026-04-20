package com.driftcourse.app.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * サーバの `_compose_system` と同じ並び・ラベルで、カード項目を system 文字列に畳み込む。
 * 対話永続化なしのゴーストモードなどで使う。メモリ 3 層はここには含めない。
 */
private val CARD_LABELS: List<Pair<String, String>> = listOf(
    "gender" to "性別",
    "user_address" to "ユーザの呼び方",
    "description" to "人物",
    "personality" to "性格",
    "scenario" to "状況",
    "first_mes" to "初回挨拶",
    "mes_example" to "例会話",
    "memory" to "記憶",
    "response_style" to "応答のスタイル・分量",
)

fun composeCharacterSystem(character: Character): String {
    val parts = mutableListOf<String>()
    val sys = character.systemPrompt.trim()
    if (sys.isNotEmpty()) parts.add(sys)
    val card: JsonObject = character.card
    for ((key, label) in CARD_LABELS) {
        val v = readCardString(card, key)
        if (v.isNotBlank()) parts.add("[$label]\n${v.trim()}")
    }
    return parts.joinToString("\n\n").trim()
}

private fun readCardString(card: JsonObject, key: String): String {
    val el = card[key] ?: return ""
    return runCatching { el.jsonPrimitive.contentOrNull.orEmpty() }.getOrDefault("")
}
