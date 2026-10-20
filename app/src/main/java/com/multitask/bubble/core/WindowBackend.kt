package com.multitask.bubble.core

import android.graphics.Rect

interface WindowBackend {
    fun isReady(): Boolean
    fun requestPermission(onResult: (Boolean) -> Unit)

    /** Binds the privileged shell. [onConnected] is not called if Shizuku is unavailable. */
    fun connect(onConnected: () -> Unit)
    fun disconnect()

    /** Blocking. Enables the developer settings freeform windows need. Call off the main thread. */
    fun setupFreeform()

    /** Blocking. Launches the app freeform at [bounds]. Returns the task id, or -1 if not found. */
    fun launch(packageName: String, bounds: Rect): Int

    /** Blocking. */
    fun moveTask(taskId: Int, bounds: Rect)

    /** Blocking. */
    fun close(packageName: String)
}
