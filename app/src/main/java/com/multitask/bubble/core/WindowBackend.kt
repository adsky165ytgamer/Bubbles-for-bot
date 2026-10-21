package com.multitask.bubble.core

import android.graphics.Rect

interface WindowBackend {
    fun isReady(): Boolean
    fun requestPermission(onResult: (Boolean) -> Unit)

    /** Binds the privileged shell. [onConnected] is not called if Shizuku is unavailable. */
    fun connect(onConnected: () -> Unit)
    fun disconnect()

    /** Blocking. One-time device setup so apps can run on secondary displays or freeform windows. */
    fun prepare()

    // ---- Embedded mode: the app runs on our own virtual display

    /** Blocking. Starts the app's launcher activity on the given display. */
    fun launchOnDisplay(packageName: String, displayId: Int): Boolean

    /** Forwards one touch event to a display. Single binder call. */
    fun injectMotion(displayId: Int, action: Int, x: Float, y: Float, downTime: Long)

    fun injectKey(displayId: Int, keyCode: Int)

    // ---- Fallback mode: the system's own freeform window

    /** Blocking. Launches the app freeform at [bounds]. Returns the task id, or -1 if not found. */
    fun launchFreeform(packageName: String, bounds: Rect): Int

    /** Blocking. */
    fun moveTask(taskId: Int, bounds: Rect)

    // ---- Both modes

    /** Blocking. Force-stops the app. */
    fun close(packageName: String)
}

