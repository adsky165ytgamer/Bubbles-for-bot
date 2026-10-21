package com.multitask.bubble.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory log shared by the activity and the overlay service, mirrored to logcat. */
object DiagLog {
    private const val MAX_CHARS = 6000
    private const val TRIM_CHARS = 2000

    private val buffer = StringBuilder()

    /** Called on whichever thread logged. UI code must post to the main thread itself. */
    @Volatile
    var listener: ((String) -> Unit)? = null

    fun log(tag: String, message: String) {
        Log.d(tag, message)
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        synchronized(buffer) {
            buffer.append('[').append(stamp).append("] ").append(message).append('\n')
            if (buffer.length > MAX_CHARS) buffer.delete(0, TRIM_CHARS)
        }
        listener?.invoke(snapshot())
    }

    fun snapshot(): String = synchronized(buffer) { buffer.toString() }

    fun clear() {
        synchronized(buffer) { buffer.setLength(0) }
        listener?.invoke("")
    }
}

