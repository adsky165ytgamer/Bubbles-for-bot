package com.multitask.bubble.core

import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.system.exitProcess

/**
 * Runs inside the Shizuku shell process (UID 2000). Hidden APIs are reachable here because this
 * is not a normal app process.
 */
class ShellService : IShellService.Stub() {

    private val inputManager: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "input") as IBinder
            Class.forName("android.hardware.input.IInputManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }.getOrNull()
    }

    private val injectMethod by lazy {
        runCatching {
            Class.forName("android.hardware.input.IInputManager")
                .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    private val setDisplayId by lazy {
        runCatching {
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            "[exception] ${e.message}"
        }
    }

    /**
     * Forwards a whole touch frame. [action] is the raw MotionEvent action, so it already carries
     * the pointer index for POINTER_DOWN/UP. The target app does its own pinch and double-tap
     * detection from these raw pointers.
     */
    override fun injectTouch(
        displayId: Int, action: Int, downTime: Long,
        ids: IntArray, xs: FloatArray, ys: FloatArray
    ): Boolean {
        val count = ids.size
        if (count == 0 || xs.size != count || ys.size != count) return false
        val properties = Array(count) {
            MotionEvent.PointerProperties().apply {
                id = ids[it]
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) {
            MotionEvent.PointerCoords().apply {
                x = xs[it]
                y = ys[it]
                pressure = 1f
                size = 1f
            }
        }
        val now = SystemClock.uptimeMillis()
        val actualDownTime = if (downTime > 0) downTime else now
        val event = MotionEvent.obtain(
            actualDownTime, now, action, count, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        return try {
            inject(event, displayId)
        } finally {
            event.recycle()
        }
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        down.source = InputDevice.SOURCE_KEYBOARD
        up.source = InputDevice.SOURCE_KEYBOARD
        return inject(down, displayId) && inject(up, displayId)
    }

    private fun inject(event: InputEvent, displayId: Int): Boolean = try {
        setDisplayId?.invoke(event, displayId)
        // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
        injectMethod?.invoke(inputManager, event, 0) as? Boolean ?: false
    } catch (e: Exception) {
        false
    }
}
