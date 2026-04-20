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

private const val TAG = "IconPicker"
private const val TARGET_SIZE = 256
private const val JPEG_QUALITY = 85

// GIF はアニメを保つため最大バイト数を決めて生バイトを保持する (2MB)。
private const val MAX_GIF_BYTES = 2 * 1024 * 1024

/**
 * システムの画像ピッカを起動し、選択された画像を `data:image/…;base64,…` 形式で返す。
 *
 * - image/gif の場合はアニメを失わないよう生バイトをそのまま (サイズ上限あり) 埋め込む。
 * - それ以外は 256x256 中心正方形の JPEG に正規化する。
 */
@Composable
fun rememberIconPicker(onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        CoroutineScope(Dispatchers.IO).launch {
            val dataUrl = encodeUriAsAvatarDataUrl(context, uri)
            if (dataUrl != null) {
                withContext(Dispatchers.Main) { onPicked(dataUrl) }
            }
        }
    }
    return {
        launcher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
    }
}

private fun encodeUriAsAvatarDataUrl(
    context: android.content.Context,
    uri: Uri,
): String? {
    val mime = context.contentResolver.getType(uri).orEmpty()
    if (mime.equals("image/gif", ignoreCase = true)) {
        return encodeGifAsIs(context, uri)
    }
    return encodeStaticAsJpeg(context, uri)
}

private fun encodeGifAsIs(context: android.content.Context, uri: Uri): String? {
    return try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > MAX_GIF_BYTES) {
            Log.w(TAG, "gif too large: ${bytes.size} bytes (max ${MAX_GIF_BYTES})")
            // サイズオーバーは静止 JPEG にフォールバック (1フレ目)。
            return encodeStaticAsJpeg(context, uri)
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        "data:image/gif;base64,$b64"
    } catch (t: Throwable) {
        Log.w(TAG, "gif encode failed", t)
        null
    }
}

private fun encodeStaticAsJpeg(context: android.content.Context, uri: Uri): String? {
    return try {
        val bitmap = loadBitmap(context, uri) ?: return null
        val squared = centerCropSquare(bitmap)
        val scaled = if (squared.width != TARGET_SIZE || squared.height != TARGET_SIZE) {
            Bitmap.createScaledBitmap(squared, TARGET_SIZE, TARGET_SIZE, true).also {
                if (it !== squared) squared.recycle()
            }
        } else {
            squared
        }
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        if (scaled !== bitmap) scaled.recycle()
        if (bitmap !== scaled && !bitmap.isRecycled) bitmap.recycle()
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        "data:image/jpeg;base64,$b64"
    } catch (t: Throwable) {
        Log.w(TAG, "jpeg encode failed", t)
        null
    }
}

private fun loadBitmap(context: android.content.Context, uri: Uri): Bitmap? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val srcW = info.size.width
            val srcH = info.size.height
            val shortSide = minOf(srcW, srcH).coerceAtLeast(1)
            val scale = max(1, shortSide / TARGET_SIZE)
            decoder.setTargetSampleSize(scale)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
        }
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }
}

private fun centerCropSquare(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    if (w == h) return src
    val side = minOf(w, h)
    val x = (w - side) / 2
    val y = (h - side) / 2
    return Bitmap.createBitmap(src, x, y, side, side)
}
