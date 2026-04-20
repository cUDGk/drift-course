package com.driftcourse.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import android.view.PixelCopy
import android.view.ScrollCaptureCallback
import android.view.ScrollCaptureSession
import android.view.Surface
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.function.Consumer

private const val TAG = "ScrollCapture"

/**
 * Compose の LazyColumn に ScrollCaptureCallback を繋ぐ Modifier。
 *
 * Android 12+ のスクロールスクリーンショットは Android View 階層向けに設計されていて
 * Compose は自動検出されないため、手動で View に callback を登録する必要がある。
 *
 * この実装はベストエフォート:
 * - scroll-capture 検索領域 = Modifier の画面上の矩形
 * - キャプチャ開始 → 元のオフセットを記録
 * - 画像要求 → 指定 rect を画面に来るようスクロールし、PixelCopy で window から抜き取る
 * - キャプチャ終了 → 元のオフセットに戻す
 *
 * 既知の割り切り: 指定 rect が現在 compose 済みの範囲を大きく超えると、
 * LazyColumn のアイテムが事前に生成されていないため取りこぼす可能性がある。
 * その場合はキャプチャ済み領域のみを onComplete に返すことで OS 側が終了判断する。
 */
fun Modifier.scrollCaptureProvider(
    lazyListState: LazyListState,
    @Suppress("UNUSED_PARAMETER") itemCount: Int,
): Modifier = composed {
    val view = LocalView.current
    val boundsHolder = remember { ScrollCaptureBounds() }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        DisposableEffect(view, lazyListState) {
            val cb = ComposeScrollCaptureCallback(view, lazyListState, boundsHolder)
            view.setScrollCaptureCallback(cb)
            onDispose {
                view.setScrollCaptureCallback(null)
            }
        }
    }

    onGloballyPositioned { coords ->
        val win = coords.positionInWindow()
        val size = coords.size
        boundsHolder.update(win, size.width, size.height)
    }
}

private class ScrollCaptureBounds {
    @Volatile var left: Int = 0
    @Volatile var top: Int = 0
    @Volatile var right: Int = 0
    @Volatile var bottom: Int = 0

    fun update(pos: Offset, w: Int, h: Int) {
        left = pos.x.toInt()
        top = pos.y.toInt()
        right = left + w
        bottom = top + h
    }

    fun asRect(): Rect = Rect(left, top, right, bottom)
    fun width(): Int = (right - left).coerceAtLeast(0)
    fun height(): Int = (bottom - top).coerceAtLeast(0)
}

@RequiresApi(Build.VERSION_CODES.S)
private class ComposeScrollCaptureCallback(
    private val hostView: View,
    private val lazyListState: LazyListState,
    private val bounds: ScrollCaptureBounds,
) : ScrollCaptureCallback {

    private var originalFirstIndex: Int = 0
    private var originalScrollOffset: Int = 0

    override fun onScrollCaptureSearch(
        signal: CancellationSignal,
        onReady: Consumer<Rect>,
    ) {
        onReady.accept(bounds.asRect())
    }

    override fun onScrollCaptureStart(
        session: ScrollCaptureSession,
        signal: CancellationSignal,
        onReady: Runnable,
    ) {
        originalFirstIndex = lazyListState.firstVisibleItemIndex
        originalScrollOffset = lazyListState.firstVisibleItemScrollOffset
        onReady.run()
    }

    override fun onScrollCaptureImageRequest(
        session: ScrollCaptureSession,
        signal: CancellationSignal,
        captureArea: Rect,
        onComplete: Consumer<Rect>,
    ) {
        // captureArea は bounds 座標系からの相対 Y オフセット付きの矩形。
        // 単純化: 要求 Y 分だけ LazyColumn を scrollBy してから現在の window を PixelCopy する。
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0) {
            onComplete.accept(Rect())
            return
        }
        val dy = captureArea.top  // bounds top からの相対
        // Compose の scrollBy は Main で動かす必要があるので runBlocking(Main)。
        runCatching {
            runBlocking(Dispatchers.Main) {
                lazyListState.scrollBy(dy.toFloat())
            }
        }

        // PixelCopy で hostView の window からキャプチャ領域を抜き取る。
        val srcRect = Rect(
            bounds.left,
            bounds.top,
            bounds.left + w,
            bounds.top + h.coerceAtMost(captureArea.height()),
        )
        val bmp = Bitmap.createBitmap(srcRect.width(), srcRect.height(), Bitmap.Config.ARGB_8888)
        val window = hostView.rootView
        val win = hostView.context
        val activity = findWindow(hostView)
        if (activity == null) {
            drawFallback(session.surface, bmp)
            onComplete.accept(Rect(0, 0, bmp.width, bmp.height))
            return
        }

        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false
        try {
            PixelCopy.request(
                activity,
                srcRect,
                bmp,
                { result ->
                    success = (result == PixelCopy.SUCCESS)
                    latch.countDown()
                },
                hostView.handler ?: android.os.Handler(hostView.context.mainLooper),
            )
            latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "PixelCopy failed", t)
        }

        if (!success) {
            drawFallback(session.surface, bmp)
            onComplete.accept(Rect(0, 0, bmp.width, bmp.height))
            return
        }

        // Surface に bitmap を転写する。
        val surface = session.surface
        try {
            val canvas = surface.lockCanvas(null)
            try {
                canvas.drawBitmap(bmp, 0f, 0f, Paint())
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "surface draw failed", t)
        } finally {
            bmp.recycle()
        }

        onComplete.accept(Rect(0, 0, srcRect.width(), srcRect.height()))
    }

    override fun onScrollCaptureEnd(onReady: Runnable) {
        // 元のスクロール位置に戻す。
        runCatching {
            MainScope().launch {
                lazyListState.scrollToItem(originalFirstIndex, originalScrollOffset)
                onReady.run()
            }
        }.onFailure { onReady.run() }
    }

    private fun drawFallback(surface: Surface, fallback: Bitmap) {
        try {
            val canvas = surface.lockCanvas(null)
            try {
                canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (_: Throwable) {
        } finally {
            fallback.recycle()
        }
    }
}

private fun findWindow(view: View): android.view.Window? {
    var ctx: android.content.Context? = view.context
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx.window
        ctx = ctx.baseContext
    }
    return null
}
