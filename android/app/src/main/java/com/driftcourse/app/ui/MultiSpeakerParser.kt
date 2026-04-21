package com.driftcourse.app.ui

/**
 * グループチャット用に assistant メッセージ文字列から `【名前】セリフ` 形式の話者マーカーを
 * 抽出する。マーカーが無ければ `[(null, content)]` を返して従来描画にフォールバックする。
 *
 * - マーカーは行頭のみ拾う。本文中に 【】 を含めたい場合は行頭を避ければ通る。
 * - 最初のマーカー前に地の文があればそれは `(null, ...)` として頭に入れる。
 * - マーカーと次のマーカーの間を発言本文として連結する。
 *
 * 冗長な正規表現で書くと re-composition 時に O(n^2) になりがちなので単純走査。
 */
fun parseMultiSpeaker(content: String): List<Pair<String?, String>> {
    if (content.isEmpty()) return listOf(null to content)
    val lines = content.split('\n')
    val out = mutableListOf<Pair<String?, String>>()
    var currentSpeaker: String? = null
    val buf = StringBuilder()
    fun flush() {
        val body = buf.toString().trim('\n')
        if (currentSpeaker != null || body.isNotEmpty()) {
            out.add(currentSpeaker to body)
        }
        buf.setLength(0)
    }
    for (line in lines) {
        val marker = matchLeadingMarker(line)
        if (marker != null) {
            flush()
            currentSpeaker = marker.first
            if (marker.second.isNotEmpty()) buf.append(marker.second)
        } else {
            if (buf.isNotEmpty()) buf.append('\n')
            buf.append(line)
        }
    }
    flush()
    // マーカーが 1 つも無い場合は単一要素にする (呼び出し側が従来描画を選ぶ)。
    if (out.isEmpty()) return listOf(null to content)
    if (out.size == 1 && out[0].first == null) return listOf(null to content)
    return out
}

/**
 * `【名前】残り` の形で始まる行から名前と残りを取り出す。そうでなければ null。
 * 名前内の改行や `】` は許さない。全角/半角の差はプロンプト側で全角指定なので全角 `【】` のみ。
 */
private fun matchLeadingMarker(line: String): Pair<String, String>? {
    if (line.length < 3) return null
    if (line[0] != '【') return null
    val end = line.indexOf('】', 1)
    if (end <= 1) return null
    val name = line.substring(1, end).trim()
    if (name.isEmpty()) return null
    val rest = line.substring(end + 1)
    return name to rest
}
