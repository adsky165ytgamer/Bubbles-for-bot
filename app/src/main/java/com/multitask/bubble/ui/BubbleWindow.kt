package com.multitask.bubble.ui

import android.graphics.Rect

/** The window an app lives in while it is a bubble. Main thread only. */
interface BubbleWindow {
    /** Brings the app on screen. */
    fun show()

    /** Takes the app off screen but keeps it running. */
    fun hide()

    /** Closes the app and frees everything. */
    fun release()

    /** Places and sizes the window. Applied on the next [show] if the window is hidden. */
    fun setBounds(bounds: Rect, animate: Boolean) {}

    /** Puts the window above the other windows. */
    fun raise() {}

    /** Captures current task/display/surface state in logcat for debugging. */
    fun dumpDiagnostics() {}
}
