package com.driftcourse.app.ui

import android.graphics.Rect
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import android.view.ScrollCaptureCallback
import android.view.ScrollCaptureSession
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.function.Consumer

private const val TAG = "ScrollCapture"

/**
 * Compose の LazyColumn を Android 12+ の「Capture more」機能に接続する Modifier。
 *
 * 仕組み:
 * - `onScrollCaptureSearch` で hostView のローカル座標系 (= rootView 座標系) における
 *   スクロールコンテナ矩形を返す。
 * - `onScrollCaptureImageRequest` では captureArea の top から「今 y=0 (= container の top) に
 *   何を載せたいか」を逆算し、その分 `lazyListState.scrollBy` して 1 フレーム描画してから
 *   `surface.lockHardwareCanvas()` に hostView をそのまま描く。
 * - captureArea は負の top / container 高さを超える bottom を持ち得る。
 *   前後端を超えた部分は `canScrollForward/Backward` を見てクリップし、
 *   実際に描けた部分矩形を `onComplete` に返す事で OS 側が終点を検出できる。
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
            // Compose 内部スクロールは OS からは見えないので「この View はキャプチャ対象」と明示する。
            // 明示しないとヒューリスティックで候補から外れて「キャプチャを拡大」ボタンが出ない。
            val prevHint = view.scrollCaptureHint
            view.scrollCaptureHint = View.SCROLL_CAPTURE_HINT_INCLUDE
            onDispose {
                view.setScrollCaptureCallback(null)
                view.scrollCaptureHint = prevHint
                cb.dispose()
            }
        }
    }

    onGloballyPositioned { coords ->
        // hostView は同じ ComposeView 配下なので positionInRoot() == hostView 内ローカル座標。
        val root = coords.positionInRoot()
        val size = coords.size
        boundsHolder.update(root, size.width, size.height)
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
    fun isEmpty(): Boolean = width() <= 0 || height() <= 0
}

@RequiresApi(Build.VERSION_CODES.S)
private class ComposeScrollCaptureCallback(
    private val hostView: View,
    private val lazyListState: LazyListState,
    private val bounds: ScrollCaptureBounds,
) : ScrollCaptureCallback {

    // 背景コルーチンは scope 使い回し。runBlocking(Main) はデッドロック要因なので禁止。
    private val mainScope = MainScope()

    private var originalFirstIndex: Int = 0
    private var originalScrollOffset: Int = 0

    /**
     * 「現在 y=0 に何が載っているか」を bounds-local 座標で追跡する。
     * 初期値 0 = キャプチャ開始時点の表示領域 top が bounds-local の 0 に一致する。
     * scrollBy(delta) すると「上方向に delta 動く」= 載っているコンテンツ top が +delta ずれる。
     */
    @Volatile private var reportedTop: Int = 0
    @Volatile private var ended: Boolean = false

    fun dispose() {
        mainScope.cancel()
    }

    override fun onScrollCaptureSearch(
        signal: CancellationSignal,
        onReady: Consumer<Rect>,
    ) {
        if (bounds.isEmpty()) {
            onReady.accept(Rect())
        } else {
            // hostView (rootView) ローカル座標系のスクロールコンテナ矩形。
            onReady.accept(bounds.asRect())
        }
    }

    override fun onScrollCaptureStart(
        session: ScrollCaptureSession,
        signal: CancellationSignal,
        onReady: Runnable,
    ) {
        originalFirstIndex = lazyListState.firstVisibleItemIndex
        originalScrollOffset = lazyListState.firstVisibleItemScrollOffset
        reportedTop = 0
        ended = false
        onReady.run()
    }

    override fun onScrollCaptureImageRequest(
        session: ScrollCaptureSession,
        signal: CancellationSignal,
        captureArea: Rect,
        onComplete: Consumer<Rect>,
    ) {
        val containerW = bounds.width()
        val containerH = bounds.height()
        if (containerW <= 0 || containerH <= 0 || captureArea.isEmpty) {
            onComplete.accept(Rect())
            return
        }

        // 想定済みの見える window は [reportedTop, reportedTop + containerH)。
        // captureArea.top を y=0 に揃える為に scrollBy(captureArea.top - reportedTop) 実行する。
        val targetTop = captureArea.top
        val delta = targetTop - reportedTop

        mainScope.launch {
            if (signal.isCanceled) {
                onComplete.accept(Rect())
                return@launch
            }

            val consumed = runCatching {
                withContext(Dispatchers.Main) {
                    val c = if (delta != 0) lazyListState.scrollBy(delta.toFloat()) else 0f
                    awaitOneFrame()
                    c
                }
            }.getOrElse {
                Log.w(TAG, "scrollBy failed", it)
                0f
            }

            // scrollBy の戻り値は実際に消費した px (端に当たると |consumed| < |delta|)。
            // この値で reportedTop を進める事で、端を超えた要求でも描画は「動いた分だけ」で揃う。
            reportedTop += consumed.toInt()

            // この時点で y=0 には「bounds-local 座標で reportedTop のコンテンツ」が載っている。
            // captureArea が載っている領域の [reportedTop, reportedTop + containerH) と交差する部分だけ描画する。
            val visibleTop = reportedTop
            val visibleBottom = reportedTop + containerH
            val srcTop = captureArea.top.coerceAtLeast(visibleTop)
            val srcBottom = captureArea.bottom.coerceAtMost(visibleBottom)
            if (srcBottom <= srcTop) {
                // 何も描けない (end/start 突き抜け)。
                onComplete.accept(Rect())
                return@launch
            }
            val srcLeft = captureArea.left.coerceAtLeast(0)
            val srcRight = captureArea.right.coerceAtMost(containerW)
            if (srcRight <= srcLeft) {
                onComplete.accept(Rect())
                return@launch
            }

            // hostView 上で描くべき矩形の左上 (hostView ローカル):
            //   x: bounds.left + srcLeft
            //   y: bounds.top  + (srcTop - reportedTop)
            val drawLeftInHost = bounds.left + srcLeft
            val drawTopInHost = bounds.top + (srcTop - reportedTop)

            val destW = srcRight - srcLeft
            val destH = srcBottom - srcTop

            val surface = session.surface
            val drawOk = runCatching {
                val canvas = surface.lockHardwareCanvas()
                try {
                    canvas.save()
                    // session.surface の左上 (0,0) に要求矩形の (srcLeft, srcTop) を載せる。
                    // hostView を丸ごと draw してから不要部分は clip で抑える:
                    //   canvas を (-drawLeftInHost, -drawTopInHost) 平行移動し、
                    //   見せたい [0, destW) x [0, destH) だけ clip する。
                    canvas.clipRect(0, 0, destW, destH)
                    canvas.translate(-drawLeftInHost.toFloat(), -drawTopInHost.toFloat())
                    hostView.draw(canvas)
                    canvas.restore()
                    true
                } catch (t: Throwable) {
                    Log.w(TAG, "hardware canvas draw failed", t)
                    false
                } finally {
                    runCatching { surface.unlockCanvasAndPost(canvas) }
                }
            }.getOrElse {
                Log.w(TAG, "lockHardwareCanvas failed", it)
                false
            }

            if (!drawOk) {
                onComplete.accept(Rect())
                return@launch
            }

            // 返り rect は「captureArea 座標系で、実際に埋めた領域」。
            // captureArea 原点 (= captureArea.left, captureArea.top) を基準にした内部矩形ではなく、
            // captureArea と同じ座標系 (scroll-capture 座標系 = bounds ローカル) で返す。
            onComplete.accept(Rect(srcLeft, srcTop, srcRight, srcBottom))
        }
    }

    override fun onScrollCaptureEnd(onReady: Runnable) {
        if (ended) {
            // 再入防止 (idempotency)。
            onReady.run()
            return
        }
        ended = true
        // 元のスクロール位置へ戻してから onReady。
        runCatching {
            mainScope.launch {
                runCatching {
                    withContext(Dispatchers.Main) {
                        lazyListState.scrollToItem(originalFirstIndex, originalScrollOffset)
                        awaitOneFrame()
                    }
                }
                onReady.run()
            }
        }.onFailure { onReady.run() }
    }

    /**
     * Compose の次フレーム (recompose + draw) を 1 回待つ。
     * withFrameNanos はそれ自体 suspend で、フレームコールバック登録後に再開する。
     */
    private suspend fun awaitOneFrame() {
        withFrameNanos { /* no-op: 次フレームまで待つだけ */ }
    }
}
