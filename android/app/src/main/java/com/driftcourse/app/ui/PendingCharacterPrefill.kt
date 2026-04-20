package com.driftcourse.app.ui

import kotlinx.serialization.Serializable

/**
 * AI 対話フローで合意した character 下書きを [CharacterEditScreen] に一度だけ渡すための
 * プロセス内ドロップボックス。ViewModel でも DataStore でもなく、
 * ナビゲーションを跨いで明示的にワンショットで消費するのが唯一の目的。
 *
 * 消費側 (CharacterEditScreen) は prefill を読んだら必ず null に戻すこと。
 */
@Serializable
data class CharacterDraft(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val first_mes: String = "",
    val mes_example: String = "",
    val memory: String = "",
)

object PendingCharacterPrefill {
    var draft: CharacterDraft? = null
}
