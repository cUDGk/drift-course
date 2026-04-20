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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driftcourse.app.settings.Background
import com.driftcourse.app.settings.SettingsStore
import java.io.File
import java.nio.ByteBuffer

/**
 * 設定された背景を content の下に敷くラッパー。
 *
 * - [Background.Default] はテーマの surface を透けさせるだけ
 * - [Background.Color] は単色
 * - [Background.Image] はビットマップ (GIF はアニメ) を fillMaxSize/Crop、上に 25% 黒スクリムを重ねる
 */
@Composable
fun AppBackground(
    bg: Background,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (bg) {
            is Background.Default -> Unit
            is Background.Color -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(bg.argb.toInt())),
                )
            }
            is Background.Image -> {
                ImageBackgroundLayer(dataUrl = bg.dataUrl)
            }
        }
        content()
    }
}

@Composable
private fun ImageBackgroundLayer(dataUrl: String) {
    val context = LocalContext.current
    val decoded = remember(dataUrl) { splitBgDataUrl(dataUrl) }
    if (decoded == null) return

    if (decoded.mime == "image/gif") {
        val drawable = remember(dataUrl) { decodeAnimatedGifBytes(context, decoded.bytes) }
        if (drawable != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageDrawable(drawable)
                        if (drawable is AnimatedImageDrawable) drawable.start()
                    }
                },
            )
        }
    } else {
        val bmp = remember(dataUrl) {
            runCatching {
                BitmapFactory.decodeByteArray(decoded.bytes, 0, decoded.bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // テキスト可読性のための半透明スクリム。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f)),
    )
}

/**
 * 設定ストアの bgFlow を購読して current Background を返す小さなヘルパー。
 * 各画面が個別に DataStore を開く重さは許容範囲 (DataStore 自体は application 単位でシングルトン)。
 */
@Composable
fun rememberSettingsBackground(): Background {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext) }
    val bg by store.bgFlow.collectAsStateWithLifecycle(initialValue = Background.Default)
    return bg
}

private data class BgDecodedData(val mime: String, val bytes: ByteArray)

private fun splitBgDataUrl(dataUrl: String): BgDecodedData? {
    val comma = dataUrl.indexOf(',')
    if (comma <= 0) return null
    val header = dataUrl.substring(0, comma)
    if (!header.startsWith("data:image/") || !header.contains(";base64")) return null
    val mime = header.removePrefix("data:").substringBefore(";").lowercase()
    val payload = dataUrl.substring(comma + 1)
    val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull() ?: return null
    return BgDecodedData(mime, bytes)
}

private fun decodeAnimatedGifBytes(
    context: android.content.Context,
    bytes: ByteArray,
): android.graphics.drawable.Drawable? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    return runCatching {
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        } else {
            val tmp = File.createTempFile("bg", ".gif", context.cacheDir).apply { writeBytes(bytes) }
            tmp.deleteOnExit()
            ImageDecoder.createSource(tmp)
        }
        ImageDecoder.decodeDrawable(source)
    }.getOrNull()
}
