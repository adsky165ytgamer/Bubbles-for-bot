package com.multitask.bubble.core

import android.content.Context
import android.graphics.Rect
import android.util.DisplayMetrics

class BubbleManager(
    private val context: Context,
    private val backend: WindowBackend,
    private val onChanged: () -> Unit
) {
    data class Bubble(val pkg: String, var taskId: Int = -1, var expanded: Boolean = false)

    companion object {
        const val MAX_BUBBLES = 3
        const val MAX_RECENTS = 8
        private const val PREFS = "bubbles"
    }

    val bubbles = mutableListOf<Bubble>()
    val recents = mutableListOf<String>()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val screen: DisplayMetrics get() = context.resources.displayMetrics
    val windowBounds: Rect get() {
        val w = screen.widthPixels
        val h = screen.heightPixels
        return Rect((w * 0.08).toInt(), (h * 0.12).toInt(), (w * 0.92).toInt(), (h * 0.72).toInt())
    }
    private val offscreen: Rect get() = Rect(-3000, -3000, -2000, -2000)

    init { restore() }

    fun add(pkg: String) {
        if (bubbles.any { it.pkg == pkg }) { toggle(pkg); return }
        if (bubbles.size >= MAX_BUBBLES) evict(bubbles.first())
        bubbles.add(Bubble(pkg))
        recents.remove(pkg)
        toggle(pkg) // open immediately like Android 17
    }

    fun toggle(pkg: String) {
        val b = bubbles.find { it.pkg == pkg } ?: return
        if (b.expanded) { collapse(b) } else {
            bubbles.filter { it.expanded && it !== b }.forEach { collapse(it) }
            expand(b)
        }
        save(); onChanged()
    }

    fun dismiss(pkg: String) {
        val b = bubbles.find { it.pkg == pkg } ?: return
        evict(b)
        save(); onChanged()
    }

    private fun expand(b: Bubble) {
        // launch again: reuses the existing task and brings it to front
        val id = backend.launch(b.pkg, windowBounds)
        if (id > 0) b.taskId = id
        b.expanded = true
    }

    private fun collapse(b: Bubble) {
        if (b.taskId > 0) backend.moveTask(b.taskId, offscreen)
        b.expanded = false
    }

    private fun evict(b: Bubble) {
        backend.close(b.pkg)
        bubbles.remove(b)
        recents.remove(b.pkg)
        recents.add(0, b.pkg)
        while (recents.size > MAX_RECENTS) recents.removeLast()
    }

    private fun save() {
        prefs.edit()
            .putString("active", bubbles.joinToString(",") { it.pkg })
            .putString("recents", recents.joinToString(","))
            .apply()
    }

    private fun restore() {
        prefs.getString("active", "")?.split(",")?.filter { it.isNotBlank() }
            ?.forEach { bubbles.add(Bubble(it)) } // restored collapsed, relaunch on tap
        prefs.getString("recents", "")?.split(",")?.filter { it.isNotBlank() }
            ?.let { recents.addAll(it) }
    }
}
