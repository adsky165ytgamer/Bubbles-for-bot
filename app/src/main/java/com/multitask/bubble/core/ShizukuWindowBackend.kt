package com.multitask.bubble.core

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.IBinder
import rikka.shizuku.Shizuku

class ShizukuWindowBackend(private val context: Context) : WindowBackend {

    companion object {
        const val TAG = "ShizukuBackend"
        private const val REQUEST_CODE = 1001
        private const val WINDOWING_MODE_FREEFORM = 5
        private const val TASK_POLL_ATTEMPTS = 8
        private const val TASK_POLL_DELAY_MS = 250L
        private const val LOG_OUTPUT_LIMIT = 300
    }

    @Volatile
    private var shell: IShellService? = null

    @Volatile
    private var freeformConfigured = false

    private val waiting = mutableListOf<() -> Unit>()

    // Bump version() whenever ShellService changes, or Shizuku keeps running the old process.
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShellService::class.java.name)
    ).daemon(false).processNameSuffix("shell").debuggable(false).version(2)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            shell = IShellService.Stub.asInterface(binder)
            DiagLog.log(TAG, "Shell service connected")
            val callbacks = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
            callbacks.forEach { it() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
            freeformConfigured = false
            DiagLog.log(TAG, "Shell service disconnected")
        }
    }

    override fun isReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        if (!Shizuku.pingBinder()) {
            onResult(false)
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
            return
        }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                DiagLog.log(TAG, "Shizuku permission ${if (granted) "granted" else "denied"}")
                onResult(granted)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQUEST_CODE)
    }

    override fun connect(onConnected: () -> Unit) {
        if (shell != null) {
            onConnected()
            return
        }
        if (!isReady()) {
            DiagLog.log(TAG, "Cannot connect: Shizuku is not running or not authorized")
            return
        }
        val firstWaiter = synchronized(waiting) {
            waiting.add(onConnected)
            waiting.size == 1
        }
        if (firstWaiter) {
            DiagLog.log(TAG, "Binding shell service")
            Shizuku.bindUserService(userServiceArgs, connection)
        }
    }

    override fun disconnect() {
        if (shell == null) return
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        shell = null
        freeformConfigured = false
    }

    override fun setupFreeform() {
        if (freeformConfigured) return
        sh("settings put global enable_freeform_support 1")
        sh("settings put global force_resizable_activities 1")
        freeformConfigured = true
    }

    override fun launch(packageName: String, bounds: Rect): Int {
        val component = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.component?.flattenToShortString()
        if (component == null) {
            DiagLog.log(TAG, "No launcher activity found for $packageName")
            return -1
        }
        sh("am start -n $component --windowingMode $WINDOWING_MODE_FREEFORM")

        var taskId = -1
        for (attempt in 0 until TASK_POLL_ATTEMPTS) {
            taskId = findTaskId(packageName)
            if (taskId > 0) break
            Thread.sleep(TASK_POLL_DELAY_MS)
        }
        if (taskId > 0) {
            DiagLog.log(TAG, "$packageName is task $taskId")
            moveTask(taskId, bounds)
        } else {
            DiagLog.log(TAG, "Could not find a task for $packageName, so it cannot be moved")
        }
        return taskId
    }

    override fun moveTask(taskId: Int, bounds: Rect) {
        sh("am task resize $taskId ${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
    }

    override fun close(packageName: String) {
        sh("am force-stop $packageName")
    }

    private fun findTaskId(packageName: String): Int {
        val dump = sh("dumpsys activity recents", quiet = true)
        val pattern = Regex("""Task\{\S+ #(\d+)[^}]*?A=(?:\d+:)?${Regex.escape(packageName)}\b""")
        return pattern.find(dump)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    private fun sh(command: String, quiet: Boolean = false): String {
        val service = shell
        if (service == null) {
            DiagLog.log(TAG, "$command -> no shell connected")
            return ""
        }
        val output = try {
            service.exec(command)
        } catch (e: Exception) {
            "[remote error] ${e.message}"
        }
        if (!quiet) {
            val trimmed = output.trim().take(LOG_OUTPUT_LIMIT)
            DiagLog.log(TAG, if (trimmed.isEmpty()) command else "$command -> $trimmed")
        }
        return output
    }
}
