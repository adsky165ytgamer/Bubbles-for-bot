package com.multitask.bubble.core

import android.content.Context

/**
 * Pure bubble state: the list, the cap, recents and persistence. Main thread only, never blocks.
 * The caller reacts to the returned [Change] (hide or release windows).
 *
 * With [singleOpen] true opening a bubble collapses the others, like Android 17. The default lets
 * several windows stay open so they can be tiled.
 */
class BubbleManager(
    context: Context,
    private val singleOpen: Boolean = false,
    private val onChanged: () -> Unit
) {
    data class Bubble(val pkg: String, val expanded: Boolean = false)

    /** [evicted] bubbles were removed to make room. [collapsed] bubbles were open and got hidden. */
    data class Change(
        val evicted: List<String> = emptyList(),
        val collapsed: List<String> = emptyList()
    )

    companion object {
        const val MAX_BUBBLES = 3
        const val MAX_RECENTS = 8
        private const val PREFS = "bubbles"
        private const val KEY_ACTIVE = "active"
        private const val KEY_RECENTS = "recents"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val bubbles = mutableListOf<Bubble>()
    private val recents = mutableListOf<String>()

    init {
        restore()
    }

    fun snapshot(): List<Bubble> = bubbles.toList()

    fun recentsSnapshot(): List<String> = recents.toList()

    /** Adds the app if needed (evicting the oldest at the cap) and makes it the one open bubble. */
    fun open(pkg: String): Change {
        val evicted = mutableListOf<String>()
        if (bubbles.none { it.pkg == pkg }) {
            if (bubbles.size >= MAX_BUBBLES) {
                val oldest = bubbles.first().pkg
                remove(oldest)
                evicted.add(oldest)
            }
            bubbles.add(Bubble(pkg))
            recents.remove(pkg)
        }
        val collapsed = if (singleOpen) {
            bubbles.filter { it.expanded && it.pkg != pkg }.map { it.pkg }
        } else {
            emptyList()
        }
        bubbles.replaceAll {
            when {
                it.pkg == pkg -> it.copy(expanded = true)
                singleOpen -> it.copy(expanded = false)
                else -> it
            }
        }
        commit()
        return Change(evicted, collapsed)
    }

    fun collapse(pkg: String) {
        bubbles.replaceAll { if (it.pkg == pkg) it.copy(expanded = false) else it }
        commit()
    }

    /** Moves the bubble so it ends up at [index] in the list. Order decides the tiling order. */
    fun moveTo(pkg: String, index: Int) {
        val from = bubbles.indexOfFirst { it.pkg == pkg }
        if (from < 0) return
        val item = bubbles.removeAt(from)
        bubbles.add(index.coerceIn(0, bubbles.size), item)
        commit()
    }

    /** Removes the bubble and remembers it in recents. */
    fun dismiss(pkg: String) {
        if (bubbles.none { it.pkg == pkg }) return
        remove(pkg)
        commit()
    }

    private fun remove(pkg: String) {
        bubbles.removeAll { it.pkg == pkg }
        recents.remove(pkg)
        recents.add(0, pkg)
        while (recents.size > MAX_RECENTS) recents.removeAt(recents.lastIndex)
    }

    private fun commit() {
        prefs.edit()
            .putString(KEY_ACTIVE, bubbles.joinToString(",") { it.pkg })
            .putString(KEY_RECENTS, recents.joinToString(","))
            .apply()
        onChanged()
    }

    /** Restored bubbles start collapsed; their apps start fresh on first tap. */
    private fun restore() {
        prefs.getString(KEY_ACTIVE, "").orEmpty().split(",").filter { it.isNotBlank() }
            .take(MAX_BUBBLES).forEach { bubbles.add(Bubble(it)) }
        prefs.getString(KEY_RECENTS, "").orEmpty().split(",").filter { it.isNotBlank() }
            .take(MAX_RECENTS).forEach { recents.add(it) }
    }
}
