package com.multitask.bubble.core

import android.graphics.Rect

interface WindowBackend {
    fun isReady(): Boolean
    fun requestPermission(onResult: (Boolean) -> Unit)
    fun connect(onConnected: () -> Unit)
    fun setupFreeform()
    /** Launches app freeform at bounds. Returns task id, or -1 if it could not be found. */
    fun launch(packageName: String, bounds: Rect): Int
    fun moveTask(taskId: Int, bounds: Rect)
    fun close(packageName: String)
}
