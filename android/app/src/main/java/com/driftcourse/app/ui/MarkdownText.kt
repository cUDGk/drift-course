package com.driftcourse.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 素の Compose UI だけで書いた軽量 Markdown レンダラ。
 * 対応: bold (**x**), italic (*x*), inline `code`, ```code blocks```,
 *       bullet list (- / * / + で始まる行), headers (#, ##, ###), link [txt](url)。
 * これ以外 (画像・テーブル・番号付きリスト) は素のテキストとして落とす。
 * compose-markdown (JitPack) を避けた理由: ネット依存のプロビジョンを挟まず
 * ビルド系を独立にしたい。要件の許容範囲内のフォールバック実装。
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color = LocalContentColor.current,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> HeadingBlock(block, color)
                is MdBlock.Code -> CodeBlock(block.body)
                is MdBlock.Bullet -> BulletBlock(block, color)
                is MdBlock.Paragraph -> ParagraphBlock(block.text, color)
                MdBlock.Blank -> Spacer(Modifier.width(0.dp))
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val body: String) : MdBlock
    data class Bullet(val items: List<String>) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data object Blank : MdBlock
}

private fun parseBlocks(src: String): List<MdBlock> {
    val lines = src.split('\n')
    val out = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        // code fence
        if (line.trimStart().startsWith("```")) {
            val buf = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                buf.append(lines[i]).append('\n')
                i++
            }
            if (i < lines.size) i++ // skip closing fence
            out.add(MdBlock.Code(buf.toString().trimEnd()))
            continue
        }
        // heading
        val hMatch = Regex("^(#{1,3})\\s+(.+)$").find(line.trimStart())
        if (hMatch != null) {
            val level = hMatch.groupValues[1].length
            out.add(MdBlock.Heading(level, hMatch.groupValues[2]))
            i++
            continue
        }
        // bullets (連続行をまとめる)
        if (isBullet(line)) {
            val items = mutableListOf<String>()
            while (i < lines.size && isBullet(lines[i].trimEnd())) {
                items.add(stripBullet(lines[i].trimEnd()))
                i++
            }
            out.add(MdBlock.Bullet(items))
            continue
        }
        if (line.isBlank()) {
            out.add(MdBlock.Blank)
            i++
            continue
        }
        // paragraph: 空行または特殊行まで詰める
        val buf = StringBuilder(line)
        i++
        while (i < lines.size) {
            val ln = lines[i].trimEnd()
            if (ln.isBlank() || isBullet(ln) || ln.trimStart().startsWith("```") ||
                Regex("^#{1,3}\\s+").containsMatchIn(ln.trimStart())
            ) break
            buf.append('\n').append(ln)
            i++
        }
        out.add(MdBlock.Paragraph(buf.toString()))
    }
    return out
}

private fun isBullet(line: String): Boolean {
    val t = line.trimStart()
    return t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")
}

private fun stripBullet(line: String): String {
    val t = line.trimStart()
    return t.removePrefix("- ").removePrefix("* ").removePrefix("+ ")
}

@Composable
private fun HeadingBlock(block: MdBlock.Heading, color: Color) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    Text(
        text = renderInline(block.text, color),
        style = style,
        color = color,
    )
}

@Composable
private fun CodeBlock(body: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun BulletBlock(block: MdBlock.Bullet, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        block.items.forEach { item ->
            Row {
                Text("・", color = color)
                Text(
                    text = renderInline(item, color),
                    style = MaterialTheme.typography.bodyLarge,
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun ParagraphBlock(text: String, color: Color) {
    Text(
        text = renderInline(text, color),
        style = MaterialTheme.typography.bodyLarge,
        color = color,
    )
}

/**
 * インライン装飾を AnnotatedString に変換する。
 * 処理順: バックティック (inline code) → bold → italic → link。
 * リンクは色＋下線だけで、Text ではタップハンドリングを持たない
 * (bubble 内での誤タップを避ける割り切り)。
 */
private fun renderInline(src: String, base: Color): AnnotatedString {
    val tokens = tokenize(src)
    return buildAnnotatedString {
        for (t in tokens) {
            when (t) {
                is Token.Plain -> append(t.text)
                is Token.Code -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0x22888888),
                    ),
                ) { append(t.text) }
                is Token.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(renderInline(t.text, base))
                }
                is Token.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(renderInline(t.text, base))
                }
                is Token.Link -> withStyle(
                    SpanStyle(
                        color = base.copy(alpha = 0.95f),
                        textDecoration = TextDecoration.Underline,
                    ),
                ) { append(t.label) }
            }
        }
    }
}

private sealed interface Token {
    data class Plain(val text: String) : Token
    data class Code(val text: String) : Token
    data class Bold(val text: String) : Token
    data class Italic(val text: String) : Token
    data class Link(val label: String, val url: String) : Token
}

private fun tokenize(src: String): List<Token> {
    val out = mutableListOf<Token>()
    var i = 0
    val n = src.length
    val buf = StringBuilder()
    fun flushPlain() {
        if (buf.isNotEmpty()) {
            out.add(Token.Plain(buf.toString()))
            buf.clear()
        }
    }
    while (i < n) {
        val c = src[i]
        // inline code `x`
        if (c == '`') {
            val end = src.indexOf('`', i + 1)
            if (end > i) {
                flushPlain()
                out.add(Token.Code(src.substring(i + 1, end)))
                i = end + 1
                continue
            }
        }
        // bold **x**
        if (c == '*' && i + 1 < n && src[i + 1] == '*') {
            val end = src.indexOf("**", i + 2)
            if (end > i + 1) {
                flushPlain()
                out.add(Token.Bold(src.substring(i + 2, end)))
                i = end + 2
                continue
            }
        }
        // italic *x*
        if (c == '*') {
            val end = src.indexOf('*', i + 1)
            if (end > i + 1) {
                flushPlain()
                out.add(Token.Italic(src.substring(i + 1, end)))
                i = end + 1
                continue
            }
        }
        // link [label](url)
        if (c == '[') {
            val labelEnd = src.indexOf(']', i + 1)
            if (labelEnd > 0 && labelEnd + 1 < n && src[labelEnd + 1] == '(') {
                val urlEnd = src.indexOf(')', labelEnd + 2)
                if (urlEnd > labelEnd + 1) {
                    flushPlain()
                    out.add(
                        Token.Link(
                            label = src.substring(i + 1, labelEnd),
                            url = src.substring(labelEnd + 2, urlEnd),
                        ),
                    )
                    i = urlEnd + 1
                    continue
                }
            }
        }
        buf.append(c)
        i++
    }
    flushPlain()
    return out
}
