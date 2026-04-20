package com.driftcourse.app.ui

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.util.Base64
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.nio.ByteBuffer

/**
 * モデルのアイコン表示。
 * - `data:image/gif;base64,…` ならアニメーションを保って再生 (AnimatedImageDrawable / API 28+)
 * - `data:image/jpeg;…` 他の静止画はビットマップとして描画
 * - 無効 / 空なら名前頭文字フォールバック
 */
@Composable
fun ModelAvatar(
    iconDataUrl: String?,
    fallbackName: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val decoded = remember(iconDataUrl) { splitDataUrl(iconDataUrl) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        when {
            decoded == null -> FallbackInitial(fallbackName, size)
            decoded.mime == "image/gif" -> {
                val drawable = remember(iconDataUrl) { decodeAnimatedGif(context, decoded.bytes) }
                if (drawable != null) {
                    AndroidView(
                        modifier = Modifier.size(size).clip(CircleShape),
                        factory = { ctx ->
                            ImageView(ctx).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                setImageDrawable(drawable)
                                if (drawable is AnimatedImageDrawable) drawable.start()
                            }
                        },
                    )
                } else {
                    FallbackInitial(fallbackName, size)
                }
            }
            else -> {
                val bmp = remember(iconDataUrl) {
                    runCatching {
                        BitmapFactory.decodeByteArray(decoded.bytes, 0, decoded.bytes.size)?.asImageBitmap()
                    }.getOrNull()
                }
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(size).clip(CircleShape),
                    )
                } else {
                    FallbackInitial(fallbackName, size)
                }
            }
        }
    }
}

@Composable
private fun FallbackInitial(name: String, size: Dp) {
    val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
    val style = when {
        size >= 72.dp -> MaterialTheme.typography.headlineMedium
        size >= 48.dp -> MaterialTheme.typography.titleLarge
        else -> MaterialTheme.typography.titleMedium
    }
    Text(
        initial,
        style = style,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        fontWeight = FontWeight.Bold,
    )
}

private data class DecodedData(val mime: String, val bytes: ByteArray)

private fun splitDataUrl(dataUrl: String?): DecodedData? {
    if (dataUrl.isNullOrBlank()) return null
    val comma = dataUrl.indexOf(',')
    if (comma <= 0) return null
    val header = dataUrl.substring(0, comma)
    if (!header.startsWith("data:image/") || !header.contains(";base64")) return null
    // "data:image/gif;base64" → "image/gif"
    val mime = header.removePrefix("data:").substringBefore(";").lowercase()
    val payload = dataUrl.substring(comma + 1)
    val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull() ?: return null
    return DecodedData(mime, bytes)
}

private fun decodeAnimatedGif(
    context: android.content.Context,
    bytes: ByteArray,
): android.graphics.drawable.Drawable? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    return runCatching {
        // API 30+ は ByteBuffer から直接読める。API 28-29 は一時ファイル経由。
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        } else {
            val tmp = File.createTempFile("avatar", ".gif", context.cacheDir).apply { writeBytes(bytes) }
            tmp.deleteOnExit()
            ImageDecoder.createSource(tmp)
        }
        ImageDecoder.decodeDrawable(source)
    }.getOrNull()
}
