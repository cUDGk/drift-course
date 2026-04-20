package com.driftcourse.app.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.driftcourse.app.ui.theme.DriftCourseTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "DriftExport"

/**
 * 選択された messages を縦に並べ、ComposeView に描かせた結果をビットマップに焼いて PNG 保存する。
 *
 * 方針:
 * - ComposeView を透明・alpha=0 で Activity の content フレームに一瞬だけ attach する。
 *   こうするとその ComposeView は Activity 自身の LifecycleOwner / SavedStateRegistryOwner /
 *   ViewModelStoreOwner を自動的に継承する (AppCompat/AndroidX の既定)。
 *   従って手動で set*Owner を呼ばなくて良い。
 * - attach 後、`View.measure` + `layout` で指定幅で高さを測り、Bitmap#createBitmap + Canvas へ draw。
 * - 終わったら detach して captured bitmap を返す。
 */
suspend fun renderMessagesToBitmap(
    context: Context,
    messages: List<UiMessage>,
    widthPx: Int,
    darkTheme: Boolean,
): Bitmap {
    val activity = context.findActivity()
        ?: error("renderMessagesToBitmap requires an Activity context")
    val content = activity.findViewById<ViewGroup>(android.R.id.content)
        ?: error("no android.R.id.content found")

    return withContext(Dispatchers.Main) {
        val composeView = androidx.compose.ui.platform.ComposeView(context).apply {
            // LazyColumn の中身じゃなく普通の Column なので描画は一回で確定する。
            setContent {
                DriftCourseTheme(darkTheme = darkTheme) {
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.background)
                            .padding(12.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            messages.forEach { msg ->
                                MessageBubbleExportable(msg)
                            }
                        }
                    }
                }
            }
        }

        // alpha 0 の 1x1 フレームに入れる。実際の描画は measure/layout で強制。
        val host = FrameLayout(context).apply {
            alpha = 0f
            layoutParams = ViewGroup.LayoutParams(1, 1)
            addView(
                composeView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        content.addView(host)

        try {
            // ComposeView は attach 後に初めて setContent の内容を構築するので、
            // 最初のフレームが通るまで 1 回待つ。
            awaitOneFrame(composeView)

            composeView.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            composeView.layout(0, 0, composeView.measuredWidth, composeView.measuredHeight)

            // measure だけでは Compose 内部の layout が完全に確定しない事があるので
            // 再度フレーム送り + 再 measure/layout で安定させる。
            awaitOneFrame(composeView)
            composeView.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            composeView.layout(0, 0, composeView.measuredWidth, composeView.measuredHeight)

            val w = composeView.measuredWidth.coerceAtLeast(1)
            val h = composeView.measuredHeight.coerceAtLeast(1)
            Log.i(TAG, "render size=${w}x${h} messages=${messages.size}")
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            composeView.draw(canvas)
            bitmap
        } finally {
            content.removeView(host)
        }
    }
}

/**
 * bitmap を Pictures/DriftCourse/drift-YYYYMMDD-HHMMSS.png に書き出す。
 * 返り値は MediaStore の Uri (書き込みに成功していれば)。
 */
suspend fun saveBitmapToPictures(
    context: Context,
    bitmap: Bitmap,
): Uri? = withContext(Dispatchers.IO) {
    val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val fileName = "drift-$ts.png"
    val relPath = Environment.DIRECTORY_PICTURES + "/DriftCourse"

    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, relPath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return@withContext null

    try {
        resolver.openOutputStream(uri).use { os: OutputStream? ->
            if (os == null) {
                resolver.delete(uri, null, null)
                return@withContext null
            }
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                resolver.delete(uri, null, null)
                return@withContext null
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        Log.i(TAG, "saved png to $uri")
        uri
    } catch (t: Throwable) {
        Log.w(TAG, "saveBitmapToPictures failed", t)
        runCatching { resolver.delete(uri, null, null) }
        null
    }
}

/**
 * オフスクリーン描画用に使う、吹き出し風の MessageBubble。
 * 本物の `MessageBubbleLocal` と違って combinedClickable / streaming 表示を持たない。
 * 並列実装は嫌だが、画面本体の private 版を公開したくないのでここに小さく複製する。
 */
@Composable
private fun MessageBubbleExportable(msg: UiMessage) {
    val isUser = msg.role == "user"
    val shape = if (isUser) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
    }
    val bg = if (isUser) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimary
             else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = shape,
            color = bg,
            contentColor = fg,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            MarkdownText(
                text = msg.content.ifEmpty { " " },
                color = fg,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier)
    }
}

private suspend fun awaitOneFrame(view: View) {
    suspendCancellableCoroutine<Unit> { cont ->
        view.postOnAnimation {
            if (cont.isActive) cont.resume(Unit)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
