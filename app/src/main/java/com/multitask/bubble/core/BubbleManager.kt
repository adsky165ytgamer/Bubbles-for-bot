package com.multitask.bubble.core

import android.content.Context
import android.graphics.Rect

/**
 * Owns the bubble list.
 *
 * Mutating methods ([add], [toggle], [dismiss]) block on shell commands, so call them from a single
 * background thread, one at a time. [snapshot] and [recentsSnapshot] never block and are safe to
 * read from the main thread. [onChanged] fires after every change, on the calling thread.
 */
class BubbleManager(
    context: Context,
    private val backend: WindowBackend,
    private val onChanged: () -> Unit
) {
    data class Bubble(val pkg: String, val taskId: Int = -1, val expanded: Boolean = false)

    companion object {
        const val MAX_BUBBLES = 3
        const val MAX_RECENTS = 8
        private const val PREFS = "bubbles"
        private const val KEY_ACTIVE = "active"
        private const val KEY_RECENTS = "recents"
        private val OFFSCREEN = Rect(-3000, -3000, -2000, -2000)
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val bubbles = mutableListOf<Bubble>()
    private val recents = mutableListOf<String>()

    @Volatile
    private var publishedBubbles: List<Bubble> = emptyList()

    @Volatile
    private var publishedRecents: List<String> = emptyList()

    init {
        restore()
        publish()
    }

    fun snapshot(): List<Bubble> = publishedBubbles

    fun recentsSnapshot(): List<String> = publishedRecents

    fun add(pkg: String) {
        if (bubbles.none { it.pkg == pkg }) {
            if (bubbles.size >= MAX_BUBBLES) evict(bubbles.first().pkg)
            bubbles.add(Bubble(pkg))
            recents.remove(pkg)
        }
        toggle(pkg)
    }

    /** Opens the bubble if it is collapsed, collapses it if it is open. Only one is open at a time. */
    fun toggle(pkg: String) {
        val target = bubbles.find { it.pkg == pkg } ?: return
        if (target.expanded) {
            collapse(target)
        } else {
            bubbles.filter { it.expanded }.forEach { collapse(it) }
            expand(target)
        }
        commit()
    }

    fun dismiss(pkg: String) {
        if (bubbles.none { it.pkg == pkg }) return
        evict(pkg)
        commit()
    }

    private fun expand(bubble: Bubble) {
        // Launching again reuses the existing task and brings it to the front.
        val taskId = backend.launch(bubble.pkg, windowBounds())
        replace(bubble.pkg) { it.copy(taskId = if (taskId > 0) taskId else it.taskId, expanded = true) }
    }

    private fun collapse(bubble: Bubble) {
        if (bubble.taskId > 0) backend.moveTask(bubble.taskId, OFFSCREEN)
        replace(bubble.pkg) { it.copy(expanded = false) }
    }

    private fun evict(pkg: String) {
        backend.close(pkg)
        bubbles.removeAll { it.pkg == pkg }
        recents.remove(pkg)
        recents.add(0, pkg)
        while (recents.size > MAX_RECENTS) recents.removeAt(recents.lastIndex)
    }

    private fun replace(pkg: String, change: (Bubble) -> Bubble) {
        val index = bubbles.indexOfFirst { it.pkg == pkg }
        if (index >= 0) bubbles[index] = change(bubbles[index])
    }

    private fun windowBounds(): Rect {
        val metrics = appContext.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        return Rect((w * 0.08).toInt(), (h * 0.12).toInt(), (w * 0.92).toInt(), (h * 0.72).toInt())
    }

    private fun commit() {
        save()
        publish()
        onChanged()
    }

    private fun publish() {
        publishedBubbles = bubbles.toList()
        publishedRecents = recents.toList()
    }

    private fun save() {
        prefs.edit()
            .putString(KEY_ACTIVE, bubbles.joinToString(",") { it.pkg })
            .putString(KEY_RECENTS, recents.joinToString(","))
            .apply()
    }

    /** Restored bubbles start collapsed; their apps are relaunched on first tap. */
    private fun restore() {
        prefs.getString(KEY_ACTIVE, "").orEmpty().split(",").filter { it.isNotBlank() }
            .take(MAX_BUBBLES).forEach { bubbles.add(Bubble(it)) }
        prefs.getString(KEY_RECENTS, "").orEmpty().split(",").filter { it.isNotBlank() }
            .take(MAX_RECENTS).forEach { recents.add(it) }
    }
}
