package com.multitask.bubble.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
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
import android.widget.ImageView
import android.widget.TextView
import com.multitask.bubble.R
import com.multitask.bubble.core.DiagLog
import com.multitask.bubble.core.WindowBackend
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The real bubble: the app runs on a private virtual display and its pixels are drawn into a
 * SurfaceView inside our own overlay window. We own the frame, so minimize and close are real.
 * Needs only the public DisplayManager API plus Shizuku for launching the app and injecting input.
 *
 * [onFailed] fires once if the device cannot run apps on a secondary display, so the caller can
 * switch to the freeform fallback.
 */
class EmbeddedWindow(
    private val context: Context,
    private val wm: WindowManager,
    private val backend: WindowBackend,
    private val worker: Executor,
    private val pkg: String,
    private val onMinimize: () -> Unit,
    private val onClose: () -> Unit,
    private val onFailed: () -> Unit
) : BubbleWindow, SurfaceHolder.Callback {

    private companion object {
        const val TAG = "EmbeddedWindow"
        const val FLAG_SUPPORTS_TOUCH = 1 shl 6 // hidden DisplayManager flag
        const val WIDTH_FRACTION = 0.8f
        const val HEIGHT_FRACTION = 0.6f
    }

    private val ui = Handler(Looper.getMainLooper())
    private val metrics = context.resources.displayMetrics
    private val surfaceWidth = (metrics.widthPixels * WIDTH_FRACTION).toInt()
    private val surfaceHeight = (metrics.heightPixels * HEIGHT_FRACTION).toInt()
    private val input = Executors.newSingleThreadExecutor()
    private val position = Point((metrics.widthPixels * 0.04f).toInt(), (metrics.heightPixels * 0.1f).toInt())

    private var root: View? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var displayId = -1
    private var downTime = 0L
    private var released = false
    private var failed = false

    override fun show() {
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
            y = position.y
        }
        attachTitleDrag(view.findViewById(R.id.title_bar), view, params)

        try {
            wm.addView(view, params)
            root = view
        } catch (e: Exception) {
            DiagLog.log(TAG, "Could not show window for $pkg: ${e.message}")
        }
    }

    override fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        virtualDisplay?.surface = null // pauses drawing; the app keeps running
    }

    override fun release() {
        released = true
        hide()
        val display = virtualDisplay
        virtualDisplay = null
        displayId = -1
        input.shutdown()
        worker.execute {
            // Stop the app first, otherwise Android moves it to the main screen when the display goes.
            backend.close(pkg)
            Thread.sleep(200)
            display?.release()
        }
    }

    // ---- surface lifecycle

    override fun surfaceCreated(holder: SurfaceHolder) {
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
        virtualDisplay?.surface = null
    }

    private fun fail() {
        if (failed || released) return
        failed = true
        DiagLog.log(TAG, "Embedding is not supported here, switching to freeform windows")
        onFailed()
    }

    // ---- input

    private fun forward(event: MotionEvent) {
        val id = displayId
        if (id <= 0) return
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = SystemClock.uptimeMillis()
                MotionEvent.ACTION_DOWN
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> event.actionMasked
            else -> return // single pointer for now
        }
        val x = event.x
        val y = event.y
        val time = downTime
        runCatching { input.execute { backend.injectMotion(id, action, x, y, time) } }
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
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        params.x = startX + dx
                        params.y = startY + dy
                        position.set(params.x, params.y)
                        wm.updateViewLayout(window, params)
                    }
                    true
                }
                else -> true
            }
        }
    }
}

