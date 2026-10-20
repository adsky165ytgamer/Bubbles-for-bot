package com.multitask.bubble

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.multitask.bubble.core.BubbleManager
import com.multitask.bubble.core.WindowBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleManagerTest {

    private class FakeWindowBackend : WindowBackend {
        var launched = mutableListOf<String>()
        var closed = mutableListOf<String>()
        var moves = mutableListOf<Pair<Int, Rect>>()

        override fun isReady(): Boolean = true
        override fun requestPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun connect(onConnected: () -> Unit) = onConnected()
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

    @Test
    fun testBubbleCapAndEviction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backend = FakeWindowBackend()
        var changedCount = 0
        val manager = BubbleManager(context, backend) { changedCount++ }

        manager.add("app.one")
        manager.add("app.two")
        manager.add("app.three")

        assertEquals(3, manager.bubbles.size)
        assertEquals("app.three", manager.bubbles.last().pkg)

        // Adding 4th bubble should evict the oldest (app.one)
        manager.add("app.four")
        assertEquals(3, manager.bubbles.size)
        assertTrue(manager.bubbles.none { it.pkg == "app.one" })
        assertEquals("app.one", manager.recents.first())
        assertTrue(backend.closed.contains("app.one"))
    }
}
