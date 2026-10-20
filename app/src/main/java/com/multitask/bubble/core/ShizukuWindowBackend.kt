package com.multitask.bubble.core

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

class ShizukuWindowBackend(private val context: Context) : WindowBackend {

    companion object {
        const val TAG = "ShizukuBackend"
        const val REQ = 1001
        const val FREEFORM = 5
    }

    private var shell: IShellService? = null
    private var pendingConnected: (() -> Unit)? = null
    val logHistory = StringBuilder()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShellService::class.java.name)
    ).daemon(false).processNameSuffix("shell").debuggable(false).version(1)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "UserService connected")
            recordLog("UserService connected to shell daemon")
            shell = IShellService.Stub.asInterface(binder)
            pendingConnected?.invoke()
            pendingConnected = null
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "UserService disconnected")
            recordLog("UserService disconnected")
            shell = null
        }
    }

    private fun recordLog(msg: String) {
        synchronized(logHistory) {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            logHistory.append("[$ts] $msg\n")
            if (logHistory.length > 4000) {
                logHistory.delete(0, 1500)
            }
        }
    }

    override fun isReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) { false }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        if (!Shizuku.pingBinder()) { onResult(false); return }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { onResult(true); return }
        val l = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQ) return
                Shizuku.removeRequestPermissionResultListener(this)
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                recordLog("Permission request result: $granted")
                onResult(granted)
            }
        }
        Shizuku.addRequestPermissionResultListener(l)
        Shizuku.requestPermission(REQ)
    }

    override fun connect(onConnected: () -> Unit) {
        if (shell != null) { onConnected(); return }
        pendingConnected = onConnected
        recordLog("Binding UserService for ${context.packageName}")
        Shizuku.bindUserService(userServiceArgs, conn)
    }

    fun sh(cmd: String): String {
        val s = shell ?: run {
            val fallback = "[no shell connected - check Shizuku authorization]"
            recordLog("$cmd -> $fallback")
            Log.w(TAG, "$cmd -> $fallback")
            return fallback
        }
        val out = try { s.exec(cmd) } catch (e: Exception) { "[remote error] ${e.message}" }
        Log.d(TAG, "$cmd -> $out")
        recordLog("$cmd -> $out")
        return out
    }

    override fun setupFreeform() {
        recordLog("Enabling freeform support in global settings")
        sh("settings put global enable_freeform_support 1")
        sh("settings put global force_resizable_activities 1")
    }

    override fun launch(packageName: String, bounds: Rect): Int {
        val comp = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.component?.flattenToShortString() ?: run {
            recordLog("No launch component found for $packageName")
            return -1
        }
        recordLog("Launching: am start -n $comp --windowingMode $FREEFORM")
        val startOutput = sh("am start -n $comp --windowingMode $FREEFORM")
        recordLog("am start output: $startOutput")
        Thread.sleep(400) // let the task appear
        val taskId = findTaskId(packageName)
        recordLog("Found taskId for $packageName: $taskId")
        if (taskId > 0) moveTask(taskId, bounds)
        return taskId
    }

    override fun moveTask(taskId: Int, bounds: Rect) {
        sh("am task resize $taskId ${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
    }

    override fun close(packageName: String) {
        recordLog("Closing $packageName via force-stop")
        sh("am force-stop $packageName")
    }

    fun findTaskId(pkg: String): Int {
        val dump = sh("dumpsys activity recents")
        val re = Regex("""Task\{[^ ]+ #(\d+)[^}]*A=\d+:${Regex.escape(pkg)}""")
        val found = re.find(dump)?.groupValues?.get(1)?.toIntOrNull()
        if (found != null) return found

        // Secondary fallback regex for different Android OEM dumpsys formats
        val fallbackRe = Regex("""#(\d+)\s+.*${Regex.escape(pkg)}""")
        return fallbackRe.find(dump)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }
}
