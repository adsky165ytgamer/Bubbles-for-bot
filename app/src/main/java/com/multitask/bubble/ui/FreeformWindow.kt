package com.multitask.bubble.ui

import android.content.Context
import android.graphics.Rect
import com.multitask.bubble.core.WindowBackend
import java.util.concurrent.Executor

/**
 * Fallback for devices that cannot run apps on a secondary display. The system draws the window,
 * we only place it. Hiding moves it off screen, which some ROMs may ignore.
 */
class FreeformWindow(
    context: Context,
    private val backend: WindowBackend,
    private val worker: Executor,
    private val pkg: String
) : BubbleWindow {

    private val offscreen = Rect(-3000, -3000, -2000, -2000)

    @Volatile
    private var bounds: Rect

    @Volatile
    private var taskId = -1

    init {
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        bounds = Rect((w * 0.08).toInt(), (h * 0.12).toInt(), (w * 0.92).toInt(), (h * 0.72).toInt())
    }

    override fun show() {
        val target = bounds
        // Launching again reuses the existing task and brings it to the front.
        worker.execute {
            val id = backend.launchFreeform(pkg, target)
            if (id > 0) taskId = id
        }
    }

    override fun hide() {
        worker.execute { if (taskId > 0) backend.moveTask(taskId, offscreen) }
    }

    override fun release() {
        worker.execute { backend.close(pkg) }
    }

    override fun setBounds(bounds: Rect, animate: Boolean) {
        this.bounds = Rect(bounds)
        val target = this.bounds
        worker.execute { if (taskId > 0) backend.moveTask(taskId, target) }
    }
}
