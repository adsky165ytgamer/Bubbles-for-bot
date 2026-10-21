package com.multitask.bubble

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.multitask.bubble.core.BubbleManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleManagerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun capEvictsTheOldestBubble() {
        val manager = BubbleManager(context) {}
        manager.open("app.one")
        manager.open("app.two")
        manager.open("app.three")
        assertEquals(BubbleManager.MAX_BUBBLES, manager.snapshot().size)

        val change = manager.open("app.four")

        assertEquals(listOf("app.one"), change.evicted)
        val pkgs = manager.snapshot().map { it.pkg }
        assertFalse(pkgs.contains("app.one"))
        assertEquals("app.one", manager.recentsSnapshot().first())
    }

    @Test
    fun severalBubblesCanBeOpenByDefault() {
        val manager = BubbleManager(context) {}
        manager.open("app.one")

        val change = manager.open("app.two")

        assertTrue(change.collapsed.isEmpty())
        assertTrue(manager.snapshot().all { it.expanded })
    }

    @Test
    fun singleOpenModeCollapsesTheOthers() {
        val manager = BubbleManager(context, singleOpen = true) {}
        manager.open("app.one")

        val change = manager.open("app.two")

        assertEquals(listOf("app.one"), change.collapsed)
        val state = manager.snapshot().associateBy { it.pkg }
        assertFalse(state.getValue("app.one").expanded)
        assertTrue(state.getValue("app.two").expanded)
    }

    @Test
    fun openingAnExistingAppDoesNotDuplicateIt() {
        val manager = BubbleManager(context) {}
        manager.open("app.one")
        manager.open("app.one")
        assertEquals(1, manager.snapshot().size)
    }

    @Test
    fun collapseKeepsTheBubbleButClosesIt() {
        val manager = BubbleManager(context) {}
        manager.open("app.one")

        manager.collapse("app.one")

        assertEquals(1, manager.snapshot().size)
        assertFalse(manager.snapshot().first().expanded)
    }

    @Test
    fun dismissedBubblesMoveToRecentsAndReopen() {
        val manager = BubbleManager(context) {}
        manager.open("app.one")
        manager.dismiss("app.one")
        assertTrue(manager.snapshot().isEmpty())
        assertEquals(listOf("app.one"), manager.recentsSnapshot())

        manager.open("app.one")
        assertEquals(1, manager.snapshot().size)
        assertTrue(manager.recentsSnapshot().isEmpty())
    }

    @Test
    fun bubblesAreRestoredCollapsedByANewManager() {
        BubbleManager(context) {}.open("app.one")

        val restored = BubbleManager(context) {}.snapshot()

        assertEquals(listOf("app.one"), restored.map { it.pkg })
        assertFalse(restored.first().expanded)
    }

    @Test
    fun moveToPutsTheBubbleAtTheGivenIndex() {
        val manager = BubbleManager(context) {}
        manager.open("app.one")
        manager.open("app.two")
        manager.open("app.three")

        manager.moveTo("app.three", 0)
        assertEquals(listOf("app.three", "app.one", "app.two"), manager.snapshot().map { it.pkg })

        manager.moveTo("app.three", 2)
        assertEquals(listOf("app.one", "app.two", "app.three"), manager.snapshot().map { it.pkg })
    }

    @Test
    fun changesNotifyTheListener() {
        var calls = 0
        val manager = BubbleManager(context) { calls++ }
        manager.open("app.one")
        manager.collapse("app.one")
        manager.dismiss("app.one")
        assertEquals(3, calls)
    }
}
