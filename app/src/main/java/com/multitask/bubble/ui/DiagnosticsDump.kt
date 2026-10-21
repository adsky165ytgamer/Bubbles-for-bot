package com.multitask.bubble.ui

import android.view.SurfaceView
import com.multitask.bubble.core.DiagLog
import com.multitask.bubble.core.WindowBackend

/**
 * Collects the small set of state needed to diagnose apps that render black on a virtual display.
 * The privileged task/display portion is supplied by [WindowBackend], while surface state is local.
 */
object DiagnosticsDump {
    private const val TAG = "BubbleDiagnostics"

    fun dump(
        backend: WindowBackend,
        packageName: String,
        displayId: Int,
        surface: SurfaceView?,
        hardwareBufferReady: Boolean
    ) {
        val localState = buildString {
            appendLine("package=$packageName")
            appendLine("displayId=$displayId")
            appendLine("surfacePresent=${surface != null}")
            appendLine("surfaceValid=${surface?.holder?.surface?.isValid == true}")
            appendLine("surfaceSize=${surface?.width ?: 0}x${surface?.height ?: 0}")
            appendLine("hardwareBufferReady=$hardwareBufferReady")
        }
        val privilegedState = runCatching {
            backend.dumpDiagnostics(packageName, displayId)
        }.getOrElse { "backend diagnostics failed: ${it.message}" }

        DiagLog.log(TAG, "Diagnostics dump:\n$localState$privilegedState")
    }
}
