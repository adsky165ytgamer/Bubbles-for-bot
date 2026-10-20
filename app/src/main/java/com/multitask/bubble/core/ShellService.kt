package com.multitask.bubble.core

import kotlin.system.exitProcess

class ShellService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            if (err.isNotBlank()) "$out\n[err] $err" else out
        } catch (e: Exception) {
            "[exception] ${e.message}"
        }
    }
}
