package com.multitask.bubble

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.multitask.bubble.core.BubbleManager
import com.multitask.bubble.core.WindowBackend
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

    private class FakeWindowBackend : WindowBackend {
        val launched = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val moves = mutableListOf<Pair<Int, Rect>>()

        override fun isReady() = true
        override fun requestPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun connect(onConnected: () -> Unit) = onConnected()
        override fun disconnect() {}
        override fun setupFreeform() {}
        override fun launch(packageName: String, bounds: Rect): Int {
            launched.add(packageName)
            return launched.size
        }
        override fun moveTask(taskId: Int, bounds: Rect) {
            moves.add(taskId to bounds)
        }
        override fun close(packageName: String) {
            closed.add(packageName)
        }
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun capEvictsTheOldestBubble() {
        val backend = FakeWindowBackend()
        val manager = BubbleManager(context, backend) {}

        manager.add("app.one")
        manager.add("app.two")
        manager.add("app.three")
        assertEquals(BubbleManager.MAX_BUBBLES, manager.snapshot().size)

        manager.add("app.four")

        val pkgs = manager.snapshot().map { it.pkg }
        assertEquals(BubbleManager.MAX_BUBBLES, pkgs.size)
        assertFalse(pkgs.contains("app.one"))
        assertEquals("app.one", manager.recentsSnapshot().first())
        assertTrue(backend.closed.contains("app.one"))
    }

    @Test
    fun onlyOneBubbleIsOpenAtATime() {
        val backend = FakeWindowBackend()
        val manager = BubbleManager(context, backend) {}

        manager.add("app.one")
        manager.add("app.two")

        val state = manager.snapshot().associateBy { it.pkg }
        assertFalse(state.getValue("app.one").expanded)
        assertTrue(state.getValue("app.two").expanded)
        assertTrue(backend.moves.any { it.first == 1 }) // app.one was moved out of the way
    }

    @Test
    fun addingAnExistingAppDoesNotDuplicateIt() {
        val manager = BubbleManager(context, FakeWindowBackend()) {}

        manager.add("app.one")
        manager.add("app.one")

        assertEquals(1, manager.snapshot().size)
    }

    @Test
    fun dismissedBubblesMoveToRecentsAndReopen() {
        val backend = FakeWindowBackend()
        val manager = BubbleManager(context, backend) {}

        manager.add("app.one")
        manager.dismiss("app.one")
        assertTrue(manager.snapshot().isEmpty())
        assertEquals(listOf("app.one"), manager.recentsSnapshot())

        manager.add("app.one")
        assertEquals(1, manager.snapshot().size)
        assertTrue(manager.recentsSnapshot().isEmpty())
    }

    @Test
    fun bubblesAreRestoredCollapsedByANewManager() {
        val first = BubbleManager(context, FakeWindowBackend()) {}
        first.add("app.one")

        val restored = BubbleManager(context, FakeWindowBackend()) {}.snapshot()

        assertEquals(listOf("app.one"), restored.map { it.pkg })
        assertFalse(restored.first().expanded)
    }
}
