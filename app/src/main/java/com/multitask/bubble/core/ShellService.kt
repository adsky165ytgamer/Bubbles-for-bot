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

    override fun injectMotion(displayId: Int, action: Int, x: Float, y: Float, downTime: Long): Boolean {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
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

