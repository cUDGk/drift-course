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

private const val TAG = "DriftCapture"

/**
 * Compose の LazyColumn を Android 12+ の「Capture more」機能に接続する Modifier。
 *
 * Xiaomi HyperOS 系は ComposeView (子) に setScrollCaptureCallback しても
 * そこまで降りて来ない事があるので、コールバックは Activity の decorView に一本化し、
 * この Modifier は「今どの LazyColumn がアクティブか」を [ActiveScrollCapture] に
 * 記録するだけに留める。decorView 側の callback は [ActiveScrollCapture.target] を
 * 参照してそのたびに委譲する。
 */
fun Modifier.scrollCaptureProvider(
    lazyListState: LazyListState,
    @Suppress("UNUSED_PARAMETER") itemCount: Int,
): Modifier = composed {
    val view = LocalView.current
    val boundsHolder = remember { ScrollCaptureBounds() }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        DisposableEffect(view, lazyListState) {
            val target = ScrollTarget(
                lazyListState = lazyListState,
                hostView = view,
                bounds = boundsHolder,
            )
            ActiveScrollCapture.target = target
            onDispose {
                // 同じ target が差し替わって居ない時のみクリア (別画面が既に上書きしていたら触らない)。
                if (ActiveScrollCapture.target === target) {
                    ActiveScrollCapture.target = null
                }
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

/**
 * 現在アクティブな LazyColumn を decorView 側の callback に橋渡しするシングルトン。
 * `ConversationScreen` が表示中の間だけ non-null、スクロールキャプチャ要求が来たら
 * この target をそのまま使って search / image / end を処理する。
 */
object ActiveScrollCapture {
    @Volatile var target: ScrollTarget? = null
}

class ScrollTarget internal constructor(
    internal val lazyListState: LazyListState,
    internal val hostView: View,
    internal val bounds: ScrollCaptureBounds,
)

internal class ScrollCaptureBounds {
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

/**
 * Activity 側から `window.decorView.setScrollCaptureCallback(...)` に流し込む callback。
 * 状態 (元のスクロール位置 / 進行中の reportedTop) は [ActiveScrollCapture.target] が
 * 変わらない限り使い回される。一連のキャプチャ中に target が差し替わる事は現実には無い前提。
 */
@RequiresApi(Build.VERSION_CODES.S)
class DecorScrollCaptureCallback(
    private val decorView: View,
) : ScrollCaptureCallback {

    private val mainScope = MainScope()

    private var originalFirstIndex: Int = 0
    private var originalScrollOffset: Int = 0

    @Volatile private var reportedTop: Int = 0
    @Volatile private var ended: Boolean = false

    fun dispose() {
        mainScope.cancel()
    }

    override fun onScrollCaptureSearch(
        signal: CancellationSignal,
        onReady: Consumer<Rect>,
    ) {
        val t = ActiveScrollCapture.target
        if (t == null || t.bounds.isEmpty()) {
            Log.i(TAG, "search called: no active target")
            onReady.accept(Rect())
            return
        }
        // hostView (= ComposeView) の decorView 上の位置を足した矩形を返す。
        val hostLoc = IntArray(2)
        val decorLoc = IntArray(2)
        t.hostView.getLocationInWindow(hostLoc)
        decorView.getLocationInWindow(decorLoc)
        val offX = hostLoc[0] - decorLoc[0]
        val offY = hostLoc[1] - decorLoc[1]
        val r = t.bounds.asRect()
        val result = Rect(r.left + offX, r.top + offY, r.right + offX, r.bottom + offY)
        Log.i(TAG, "search called: returning $result")
        onReady.accept(result)
    }

    override fun onScrollCaptureStart(
        session: ScrollCaptureSession,
        signal: CancellationSignal,
        onReady: Runnable,
    ) {
        val t = ActiveScrollCapture.target
        if (t == null) {
            Log.i(TAG, "start called: no active target")
            onReady.run()
            return
        }
        originalFirstIndex = t.lazyListState.firstVisibleItemIndex
        originalScrollOffset = t.lazyListState.firstVisibleItemScrollOffset
        reportedTop = 0
        ended = false
        Log.i(TAG, "start called: first=$originalFirstIndex off=$originalScrollOffset")
        onReady.run()
    }

    override fun onScrollCaptureImageRequest(
        session: ScrollCaptureSession,
        signal: CancellationSignal,
        captureArea: Rect,
        onComplete: Consumer<Rect>,
    ) {
        val t = ActiveScrollCapture.target
        if (t == null) {
            Log.i(TAG, "image called: no active target")
            onComplete.accept(Rect())
            return
        }
        // captureArea は decorView 座標系ではなく search で返した bounds ローカル系。
        // 従って以降の計算は bounds ローカル座標系で統一して、最後に hostView 上の
        // 描画オフセットを計算する。
        val containerW = t.bounds.width()
        val containerH = t.bounds.height()
        if (containerW <= 0 || containerH <= 0 || captureArea.isEmpty) {
            Log.i(TAG, "image called: empty bounds or capture area=$captureArea")
            onComplete.accept(Rect())
            return
        }

        val targetTop = captureArea.top
        val delta = targetTop - reportedTop

        Log.i(TAG, "image called: area=$captureArea delta=$delta reportedTop=$reportedTop")

        mainScope.launch {
            if (signal.isCanceled) {
                onComplete.accept(Rect())
                return@launch
            }

            val consumed = runCatching {
                withContext(Dispatchers.Main) {
                    val c = if (delta != 0) t.lazyListState.scrollBy(delta.toFloat()) else 0f
                    awaitOneFrame()
                    c
                }
            }.getOrElse {
                Log.w(TAG, "scrollBy failed", it)
                0f
            }

            reportedTop += consumed.toInt()

            val visibleTop = reportedTop
            val visibleBottom = reportedTop + containerH
            val srcTop = captureArea.top.coerceAtLeast(visibleTop)
            val srcBottom = captureArea.bottom.coerceAtMost(visibleBottom)
            if (srcBottom <= srcTop) {
                onComplete.accept(Rect())
                return@launch
            }
            val srcLeft = captureArea.left.coerceAtLeast(0)
            val srcRight = captureArea.right.coerceAtMost(containerW)
            if (srcRight <= srcLeft) {
                onComplete.accept(Rect())
                return@launch
            }

            // hostView 座標系での描画矩形左上。
            val drawLeftInHost = t.bounds.left + srcLeft
            val drawTopInHost = t.bounds.top + (srcTop - reportedTop)

            val destW = srcRight - srcLeft
            val destH = srcBottom - srcTop

            val surface = session.surface
            val drawOk = runCatching {
                val canvas = surface.lockHardwareCanvas()
                try {
                    canvas.save()
                    canvas.clipRect(0, 0, destW, destH)
                    canvas.translate(-drawLeftInHost.toFloat(), -drawTopInHost.toFloat())
                    t.hostView.draw(canvas)
                    canvas.restore()
                    true
                } catch (th: Throwable) {
                    Log.w(TAG, "hardware canvas draw failed", th)
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

            onComplete.accept(Rect(srcLeft, srcTop, srcRight, srcBottom))
        }
    }

    override fun onScrollCaptureEnd(onReady: Runnable) {
        if (ended) {
            onReady.run()
            return
        }
        ended = true
        Log.i(TAG, "end called")
        val t = ActiveScrollCapture.target
        if (t == null) {
            onReady.run()
            return
        }
        runCatching {
            mainScope.launch {
                runCatching {
                    withContext(Dispatchers.Main) {
                        t.lazyListState.scrollToItem(originalFirstIndex, originalScrollOffset)
                        awaitOneFrame()
                    }
                }
                onReady.run()
            }
        }.onFailure { onReady.run() }
    }

    private suspend fun awaitOneFrame() {
        withFrameNanos { /* no-op: 次フレームまで待つだけ */ }
    }
}
