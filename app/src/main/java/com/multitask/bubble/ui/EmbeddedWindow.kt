package com.multitask.bubble.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.animation.doOnEnd
import com.multitask.bubble.R
import com.multitask.bubble.core.DiagLog
import com.multitask.bubble.core.WindowBackend
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The real bubble: the app runs on a private virtual display and its pixels are drawn into a
 * SurfaceView inside our own overlay window. We own the frame, so minimize, close, move and
 * resize are real. Needs only the public DisplayManager API plus Shizuku for launching the app
 * and injecting input.
 *
 * [onFailed] fires once if the device cannot run apps on a secondary display, so the caller can
 * switch to the freeform fallback. [onFocus] fires when the title bar is tapped. [onDragEnd]
 * fires with the window center when the title bar was dragged and released.
 */
class EmbeddedWindow(
    private val context: Context,
    private val wm: WindowManager,
    private val backend: WindowBackend,
    private val worker: Executor,
    private val pkg: String,
    private val onMinimize: () -> Unit,
    private val onClose: () -> Unit,
    private val onFailed: () -> Unit,
    private val onFocus: () -> Unit,
    private val onDragEnd: (centerX: Int, centerY: Int) -> Unit
) : BubbleWindow, SurfaceHolder.Callback {

    private companion object {
        const val TAG = "EmbeddedWindow"
        const val FLAG_SUPPORTS_TOUCH = 1 shl 6 // hidden DisplayManager flag
        const val WIDTH_FRACTION = 0.8f
        const val HEIGHT_FRACTION = 0.6f
        const val TITLE_DP = 52
        const val MIN_SURFACE_DP = 120
        const val SLIDE_DP = 16
        const val ENTER_MS = 220L
        const val EXIT_MS = 150L
        const val MOVE_MS = 280L
    }

    private val ui = Handler(Looper.getMainLooper())
    private val metrics = context.resources.displayMetrics
    private fun dp(value: Int) = (value * metrics.density).toInt()

    private var surfaceWidth = (metrics.widthPixels * WIDTH_FRACTION).toInt()
    private var surfaceHeight = (metrics.heightPixels * HEIGHT_FRACTION).toInt()
    private val position = Point((metrics.widthPixels * 0.04f).toInt(), (metrics.heightPixels * 0.1f).toInt())
    private val input = Executors.newSingleThreadExecutor()

    private var root: View? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var activeHolder: SurfaceHolder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var displayId = -1
    private var downTime = 0L
    private var released = false
    private var failed = false
    private var enterAnimator: ValueAnimator? = null
    private var moveAnimator: ValueAnimator? = null

    // ---- BubbleWindow

    override fun show() = attach(animated = true)

    override fun hide() = detach(animated = true)

    override fun release() {
        released = true
        detach(animated = true)
        val display = virtualDisplay
        virtualDisplay = null
        displayId = -1
        input.shutdown()
        worker.execute {
            // Stop the app first, otherwise Android moves it to the main screen when the display goes.
            backend.close(pkg)
            Thread.sleep(250)
            display?.release()
        }
    }

    override fun raise() {
        if (root == null || released) return
        detach(animated = false)
        attach(animated = false)
    }

    override fun setBounds(bounds: Rect, animate: Boolean) {
        surfaceWidth = bounds.width().coerceAtLeast(dp(MIN_SURFACE_DP))
        surfaceHeight = (bounds.height() - dp(TITLE_DP)).coerceAtLeast(dp(MIN_SURFACE_DP))
        virtualDisplay?.resize(surfaceWidth, surfaceHeight, metrics.densityDpi)

        val view = root
        val params = windowParams
        if (view == null || params == null) {
            position.set(bounds.left, bounds.top)
            return
        }
        val surface = view.findViewById<SurfaceView>(R.id.surface)
        surface.layoutParams = surface.layoutParams.apply {
            width = surfaceWidth
            height = surfaceHeight
        }
        moveAnimator?.cancel()
        if (!animate) {
            params.x = bounds.left
            params.y = bounds.top
            position.set(params.x, params.y)
            runCatching { wm.updateViewLayout(view, params) }
            return
        }
        val fromX = params.x
        val fromY = params.y
        moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MOVE_MS
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                if (root !== view) return@addUpdateListener
                val t = it.animatedValue as Float
                params.x = (fromX + (bounds.left - fromX) * t).toInt()
                params.y = (fromY + (bounds.top - fromY) * t).toInt()
                position.set(params.x, params.y)
                runCatching { wm.updateViewLayout(view, params) }
            }
            start()
        }
    }

    // ---- window

    private fun attach(animated: Boolean) {
        if (root != null || released) return
        val view = LayoutInflater.from(context).inflate(R.layout.view_embedded_window, null)

        val surface = view.findViewById<SurfaceView>(R.id.surface)
        surface.layoutParams = surface.layoutParams.apply {
            width = surfaceWidth
            height = surfaceHeight
        }
        surface.holder.addCallback(this)
        surface.setOnTouchListener { _, event ->
            forward(event)
            true
        }

        val pm = context.packageManager
        view.findViewById<TextView>(R.id.title_text).text = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        view.findViewById<ImageView>(R.id.title_icon)
            .setImageDrawable(runCatching { pm.getApplicationIcon(pkg) }.getOrNull())
        view.findViewById<View>(R.id.btn_back).setOnClickListener { sendKey(KeyEvent.KEYCODE_BACK) }
        view.findViewById<View>(R.id.btn_minimize).setOnClickListener { onMinimize() }
        view.findViewById<View>(R.id.btn_close).setOnClickListener { onClose() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = position.x
            y = position.y + if (animated) dp(SLIDE_DP) else 0
            alpha = if (animated) 0f else 1f
        }
        attachTitleDrag(view.findViewById(R.id.title_bar), view, params)

        try {
            wm.addView(view, params)
            root = view
            windowParams = params
        } catch (e: Exception) {
            DiagLog.log(TAG, "Could not show window for $pkg: ${e.message}")
            return
        }
        if (animated) animateEnter(view, params)
    }

    private fun animateEnter(view: View, params: WindowManager.LayoutParams) {
        enterAnimator?.cancel()
        enterAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ENTER_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                if (root !== view) return@addUpdateListener
                val t = it.animatedValue as Float
                params.alpha = t
                params.y = position.y + ((1f - t) * dp(SLIDE_DP)).toInt()
                runCatching { wm.updateViewLayout(view, params) }
            }
            start()
        }
    }

    private fun detach(animated: Boolean) {
        val view = root ?: return
        val params = windowParams
        root = null
        windowParams = null
        enterAnimator?.cancel()
        moveAnimator?.cancel()

        fun finish() {
            runCatching { wm.removeView(view) }
            // Pause drawing, unless a new window already took over.
            if (root == null) virtualDisplay?.surface = null
        }
        if (!animated || params == null) {
            finish()
            return
        }
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = EXIT_MS
            addUpdateListener {
                val t = it.animatedValue as Float
                params.alpha = t
                params.y = position.y + ((1f - t) * dp(SLIDE_DP)).toInt()
                runCatching { wm.updateViewLayout(view, params) }
            }
            doOnEnd { finish() }
            start()
        }
    }

    // ---- surface lifecycle

    override fun surfaceCreated(holder: SurfaceHolder) {
        activeHolder = holder
        val existing = virtualDisplay
        if (existing != null) {
            existing.surface = holder.surface
            return
        }
        val manager = context.getSystemService(DisplayManager::class.java)
        val base = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        val created = runCatching {
            manager.createVirtualDisplay(
                "bubble-$pkg", surfaceWidth, surfaceHeight, metrics.densityDpi,
                holder.surface, base or FLAG_SUPPORTS_TOUCH
            )
        }.recoverCatching {
            manager.createVirtualDisplay(
                "bubble-$pkg", surfaceWidth, surfaceHeight, metrics.densityDpi, holder.surface, base
            )
        }.onFailure { DiagLog.log(TAG, "Virtual display failed: ${it.message}") }.getOrNull()

        if (created == null) {
            fail()
            return
        }
        virtualDisplay = created
        displayId = created.display.displayId
        DiagLog.log(TAG, "$pkg runs on virtual display $displayId (${surfaceWidth}x$surfaceHeight)")
        val id = displayId
        worker.execute {
            if (!backend.launchOnDisplay(pkg, id)) ui.post { fail() }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // An old window can be destroyed after a new one took over, so only react to the active one.
        if (holder === activeHolder) {
            activeHolder = null
            virtualDisplay?.surface = null
        }
    }

    private fun fail() {
        if (failed || released) return
        failed = true
        DiagLog.log(TAG, "Embedding is not supported here, switching to freeform windows")
        onFailed()
    }

    // ---- input

    /** Forwards every pointer, so pinch-to-zoom, double tap and flings reach the app unchanged. */
    private fun forward(event: MotionEvent) {
        val id = displayId
        if (id <= 0) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downTime = SystemClock.uptimeMillis()
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> Unit
            else -> return
        }
        val count = event.pointerCount
        val ids = IntArray(count) { event.getPointerId(it) }
        val xs = FloatArray(count) { event.getX(it) }
        val ys = FloatArray(count) { event.getY(it) }
        val action = event.action // keeps the pointer index for POINTER_DOWN/UP
        val time = downTime
        runCatching { input.execute { backend.injectTouch(id, action, time, ids, xs, ys) } }
    }

    private fun sendKey(keyCode: Int) {
        val id = displayId
        if (id <= 0) return
        runCatching { input.execute { backend.injectKey(id, keyCode) } }
    }

    private fun attachTitleDrag(handle: View, window: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moveAnimator?.cancel()
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (moved || abs(dx) > slop || abs(dy) > slop) {
                        moved = true
                        params.x = startX + dx
                        params.y = startY + dy
                        position.set(params.x, params.y)
                        runCatching { wm.updateViewLayout(window, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        onDragEnd(params.x + surfaceWidth / 2, params.y + (surfaceHeight + dp(TITLE_DP)) / 2)
                    } else {
                        onFocus()
                    }
                    true
                }
                else -> true
            }
        }
    }
}
