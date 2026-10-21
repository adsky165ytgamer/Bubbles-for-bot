package com.multitask.bubble.ui

/** The window an app lives in while it is a bubble. Main thread only. */
interface BubbleWindow {
    /** Brings the app on screen. */
    fun show()

    /** Takes the app off screen but keeps it running. */
    fun hide()

    /** Closes the app and frees everything. */
    fun release()
}

