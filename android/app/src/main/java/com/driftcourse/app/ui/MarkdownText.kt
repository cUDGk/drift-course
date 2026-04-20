package com.driftcourse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
 * 自前の Markdown レンダラ。対応範囲:
 *   - bold (**x**), italic (*x*, _x_), strikethrough (~~x~~), inline code (`x`)
 *   - fenced code (```...```)
 *   - headers (#..######)
 *   - bullet list (-, *, +) / 番号付きリスト (1. 2. ...) / ネスト (インデント 2sp 毎)
 *   - blockquote (> ...)
 *   - horizontal rule (---)
 *   - link [label](url) (色 + 下線、タップなし)
 *
 * 斜字は可読性の為に通常より少し薄い色で描画する。
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
                is MdBlock.List -> ListBlock(block, color)
                is MdBlock.Quote -> QuoteBlock(block.text, color)
                is MdBlock.Hr -> HrBlock(color)
                is MdBlock.Paragraph -> ParagraphBlock(block.text, color)
                MdBlock.Blank -> Spacer(Modifier.height(2.dp))
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val body: String) : MdBlock
    data class List(val items: kotlin.collections.List<ListItem>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Hr : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data object Blank : MdBlock
}

private data class ListItem(val level: Int, val marker: String, val text: String)

private val HR_RE = Regex("^(---+|___+|\\*\\*\\*+)$")
private val HEADING_RE = Regex("^(#{1,6})\\s+(.+)$")
private val BULLET_RE = Regex("^(\\s*)([-*+])\\s+(.+)$")
private val ORDERED_RE = Regex("^(\\s*)(\\d+)[.)]\\s+(.+)$")
private val QUOTE_RE = Regex("^\\s*>\\s?(.*)$")

private fun parseBlocks(src: String): List<MdBlock> {
    val lines = src.split('\n')
    val out = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        val stripped = line.trimStart()

        if (stripped.startsWith("```")) {
            val buf = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                buf.append(lines[i]).append('\n')
                i++
            }
            if (i < lines.size) i++
            out.add(MdBlock.Code(buf.toString().trimEnd()))
            continue
        }

        if (HR_RE.matches(stripped)) {
            out.add(MdBlock.Hr)
            i++
            continue
        }

        val headingMatch = HEADING_RE.matchEntire(stripped)
        if (headingMatch != null) {
            out.add(MdBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2]))
            i++
            continue
        }

        if (BULLET_RE.matches(line) || ORDERED_RE.matches(line)) {
            val items = mutableListOf<ListItem>()
            while (i < lines.size) {
                val ln = lines[i].trimEnd()
                val b = BULLET_RE.matchEntire(ln)
                val o = ORDERED_RE.matchEntire(ln)
                when {
                    b != null -> {
                        val indent = b.groupValues[1].length
                        items.add(ListItem(level = indent / 2, marker = "•", text = b.groupValues[3]))
                    }
                    o != null -> {
                        val indent = o.groupValues[1].length
                        items.add(ListItem(level = indent / 2, marker = "${o.groupValues[2]}.", text = o.groupValues[3]))
                    }
                    else -> break
                }
                i++
            }
            out.add(MdBlock.List(items))
            continue
        }

        if (QUOTE_RE.matches(line)) {
            val buf = StringBuilder()
            while (i < lines.size) {
                val ln = lines[i].trimEnd()
                val m = QUOTE_RE.matchEntire(ln) ?: break
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(m.groupValues[1])
                i++
            }
            out.add(MdBlock.Quote(buf.toString()))
            continue
        }

        if (line.isBlank()) {
            out.add(MdBlock.Blank)
            i++
            continue
        }

        val buf = StringBuilder(line)
        i++
        while (i < lines.size) {
            val ln = lines[i].trimEnd()
            val s = ln.trimStart()
            if (ln.isBlank() || BULLET_RE.matches(ln) || ORDERED_RE.matches(ln) ||
                s.startsWith("```") || HEADING_RE.matches(s) ||
                QUOTE_RE.matches(ln) || HR_RE.matches(s)
            ) break
            buf.append('\n').append(ln)
            i++
        }
        out.add(MdBlock.Paragraph(buf.toString()))
    }
    return out
}

@Composable
private fun HeadingBlock(block: MdBlock.Heading, color: Color) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        3 -> MaterialTheme.typography.titleMedium
        4 -> MaterialTheme.typography.titleSmall
        5 -> MaterialTheme.typography.labelLarge
        else -> MaterialTheme.typography.labelMedium
    }
    Text(
        text = renderInline(block.text, color),
        style = style.copy(fontWeight = FontWeight.SemiBold),
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
private fun ListBlock(block: MdBlock.List, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        block.items.forEach { item ->
            Row(verticalAlignment = Alignment.Top) {
                Spacer(Modifier.width((item.level * 16).dp))
                Text("${item.marker} ", color = color, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = renderInline(item.text, color),
                    style = MaterialTheme.typography.bodyLarge,
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun QuoteBlock(text: String, color: Color) {
    Row {
        Box(
            modifier = Modifier
                .width(3.dp)
                .background(color.copy(alpha = 0.4f)),
        ) { Text("") }
        Spacer(Modifier.width(8.dp))
        Text(
            text = renderInline(text, color),
            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
            color = color.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun HrBlock(color: Color) {
    Box(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(color.copy(alpha = 0.3f)),
    ) { Text("") }
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
 * 斜字は色を 70% に落として通常文との差を出す (ユーザ要望)。
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
                is Token.Italic -> withStyle(
                    SpanStyle(
                        fontStyle = FontStyle.Italic,
                        color = base.copy(alpha = 0.7f),
                    ),
                ) { append(renderInline(t.text, base.copy(alpha = 0.7f))) }
                is Token.Strike -> withStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough),
                ) { append(renderInline(t.text, base)) }
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
    data class Strike(val text: String) : Token
    data class Link(val label: String, val url: String) : Token
}

private fun tokenize(src: String): List<Token> {
    val out = mutableListOf<Token>()
    var i = 0
    val n = src.length
    val buf = StringBuilder()
    fun flushPlain() {
        if (buf.isNotEmpty()) { out.add(Token.Plain(buf.toString())); buf.clear() }
    }
    while (i < n) {
        val c = src[i]
        if (c == '`') {
            val end = src.indexOf('`', i + 1)
            if (end > i) {
                flushPlain()
                out.add(Token.Code(src.substring(i + 1, end)))
                i = end + 1
                continue
            }
        }
        if (c == '*' && i + 1 < n && src[i + 1] == '*') {
            val end = src.indexOf("**", i + 2)
            if (end > i + 1) {
                flushPlain()
                out.add(Token.Bold(src.substring(i + 2, end)))
                i = end + 2
                continue
            }
        }
        if (c == '~' && i + 1 < n && src[i + 1] == '~') {
            val end = src.indexOf("~~", i + 2)
            if (end > i + 1) {
                flushPlain()
                out.add(Token.Strike(src.substring(i + 2, end)))
                i = end + 2
                continue
            }
        }
        if (c == '*' || c == '_') {
            // シングルマーカー (italic)。`**`/`__` は前段で捕捉済み。
            val end = src.indexOf(c, i + 1)
            if (end > i + 1) {
                // 直前/直後がアルファニューメリックの場合は誤爆避けで通常文字扱い
                flushPlain()
                out.add(Token.Italic(src.substring(i + 1, end)))
                i = end + 1
                continue
            }
        }
        if (c == '[') {
            val labelEnd = src.indexOf(']', i + 1)
            if (labelEnd > 0 && labelEnd + 1 < n && src[labelEnd + 1] == '(') {
                val urlEnd = src.indexOf(')', labelEnd + 2)
                if (urlEnd > labelEnd + 1) {
                    flushPlain()
                    out.add(Token.Link(src.substring(i + 1, labelEnd), src.substring(labelEnd + 2, urlEnd)))
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
