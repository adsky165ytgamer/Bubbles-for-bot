package com.multitask.bubble.core

import kotlin.system.exitProcess

/** Runs inside the Shizuku shell process (UID 2000). */
class ShellService : IShellService.Stub() {

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
}
