package com.driftcourse.app.ui

/**
 * 画面用メッセージ。id は サーバ DB の messages.id と 1:1 対応するが、
 * 送信中に UI だけに存在する placeholder (assistant のストリーム受け枠) の分は null とする。
 */
data class UiMessage(
    val role: String,
    val content: String,
    val id: String? = null,
)
