package com.driftcourse.app.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

private const val TAG = "BgImagePicker"
private const val TARGET_LONG_EDGE = 1080
private const val JPEG_QUALITY = 85

// 壁紙用途なのでアイコンより大きめに許容 (4MB)。超えると静止 JPEG にフォールバック。
private const val MAX_GIF_BYTES = 4 * 1024 * 1024

/**
 * 背景壁紙用の画像ピッカ。IconPicker と違い正方クロップせず長辺 1080px で縮小する。
 *
 * - `image/gif` かつ 4MB 以下 → アニメ保持して生バイトを base64 埋め込み
 * - それ以外 → 1080p 長辺基準に縮小した JPEG (q=85)
 */
@Composable
fun rememberBackgroundImagePicker(onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        CoroutineScope(Dispatchers.IO).launch {
            val dataUrl = encodeUriAsBackgroundDataUrl(context, uri)
            if (dataUrl != null) {
                withContext(Dispatchers.Main) { onPicked(dataUrl) }
            }
        }
    }
    return {
        launcher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
    }
}

private fun encodeUriAsBackgroundDataUrl(
    context: android.content.Context,
    uri: Uri,
): String? {
    val mime = context.contentResolver.getType(uri).orEmpty()
    if (mime.equals("image/gif", ignoreCase = true)) {
        return encodeGifAsIsOrFallback(context, uri)
    }
    return encodeStaticAsJpegDownscaled(context, uri)
}

private fun encodeGifAsIsOrFallback(context: android.content.Context, uri: Uri): String? {
    return try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > MAX_GIF_BYTES) {
            Log.w(TAG, "gif too large: ${bytes.size} bytes (max $MAX_GIF_BYTES), falling back to static jpeg")
            return encodeStaticAsJpegDownscaled(context, uri)
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        "data:image/gif;base64,$b64"
    } catch (t: Throwable) {
        Log.w(TAG, "gif encode failed", t)
        null
    }
}

private fun encodeStaticAsJpegDownscaled(context: android.content.Context, uri: Uri): String? {
    return try {
        val bitmap = loadBitmapDownscaled(context, uri) ?: return null
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        bitmap.recycle()
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        "data:image/jpeg;base64,$b64"
    } catch (t: Throwable) {
        Log.w(TAG, "jpeg encode failed", t)
        null
    }
}

private fun loadBitmapDownscaled(context: android.content.Context, uri: Uri): Bitmap? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val srcW = info.size.width
            val srcH = info.size.height
            val longEdge = max(srcW, srcH).coerceAtLeast(1)
            val scale = max(1, longEdge / TARGET_LONG_EDGE)
            decoder.setTargetSampleSize(scale)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    } else {
        @Suppress("DEPRECATION")
        val raw = MediaStore.Images.Media.getBitmap(context.contentResolver, uri) ?: return null
        val longEdge = max(raw.width, raw.height)
        if (longEdge <= TARGET_LONG_EDGE) return raw
        val ratio = TARGET_LONG_EDGE.toFloat() / longEdge
        val w = (raw.width * ratio).toInt().coerceAtLeast(1)
        val h = (raw.height * ratio).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(raw, w, h, true).also {
            if (it !== raw) raw.recycle()
        }
    }
}
