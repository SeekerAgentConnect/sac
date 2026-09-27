package io.github.brrenat.seekervault.notifications

import io.github.brrenat.seekervault.ReviewIdentity
import io.github.brrenat.seekervault.designsystem.InAppNotificationKind
import io.github.brrenat.seekervault.designsystem.InAppNotificationMotion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InAppNotificationQueueTest {
    @Test
    fun `a queued request gets its whole six seconds from the moment it becomes visible`() =
        runTest {
            val queue = InAppNotificationQueue(backgroundScope)

            queue.request(FIRST, "First")
            queue.request(SECOND, "Second")

            assertEquals(listOf("First", "Second"), queue.notes.value.map(InAppNote::title))
            // The head's timer has started. The one behind it has not: it has not been seen yet.
            assertTrue(queue.notes.value[0].armed)
            assertFalse(queue.notes.value[1].armed)

            elapse(InAppNotificationMotion.LifetimeMs - 1)
            assertEquals("First", queue.visible?.title)
            assertFalse(queue.visible!!.leaving)

            elapse(1)
            // Still on screen, but going: the exit has to be allowed to play.
            assertEquals("First", queue.visible?.title)
            assertTrue(queue.visible!!.leaving)

            elapse(InAppNotificationMotion.ExitMs.toLong())
            assertEquals("Second", queue.visible?.title)
            assertTrue(queue.visible!!.armed)

            elapse(InAppNotificationMotion.LifetimeMs - 1)
            assertEquals("Second", queue.visible?.title)

            elapse(1 + InAppNotificationMotion.ExitMs.toLong())
            assertNull(queue.visible)
        }

    @Test
    fun `a disconnected banner has no timer and holds the queue until it is dealt with`() =
        runTest {
            val queue = InAppNotificationQueue(backgroundScope)

            queue.disconnected("runner-node disconnected")
            queue.request(FIRST, "First")

            elapse(TEN_MINUTES)
            assertEquals("runner-node disconnected", queue.visible?.title)
            assertFalse(queue.visible!!.armed)
            assertFalse(queue.visible!!.leaving)
            assertEquals(2, queue.notes.value.size)

            queue.dismiss(queue.visible!!.id)
            elapse(InAppNotificationMotion.ExitMs.toLong())

            assertEquals("First", queue.visible?.title)
            // Only now, with the disconnected banner gone, does the request start counting down.
            assertTrue(queue.visible!!.armed)
            elapse(InAppNotificationMotion.LifetimeMs + InAppNotificationMotion.ExitMs)
            assertNull(queue.visible)
        }

    @Test
    fun `a mixed burst keeps its arrival order whatever the kinds are`() = runTest {
        val queue = InAppNotificationQueue(backgroundScope)

        queue.request(FIRST, "First")
        queue.disconnected("runner-node disconnected")
        queue.request(SECOND, "Second")

        assertEquals(
            listOf("First", "runner-node disconnected", "Second"),
            queue.notes.value.map(InAppNote::title),
        )
        assertEquals(
            listOf(
                InAppNotificationKind.Request,
                InAppNotificationKind.Disconnected,
                InAppNotificationKind.Request,
            ),
            queue.notes.value.map(InAppNote::kind),
        )

        elapse(InAppNotificationMotion.LifetimeMs + InAppNotificationMotion.ExitMs)
        assertEquals("runner-node disconnected", queue.visible?.title)
    }

    @Test
    fun `a request whose review is already open never becomes a banner`() = runTest {
        val open = mutableSetOf<ReviewIdentity>(FIRST)
        val queue = InAppNotificationQueue(backgroundScope) { it in open }

        queue.request(FIRST, "First")
        assertTrue(queue.notes.value.isEmpty())

        queue.request(SECOND, "Second")
        assertEquals(listOf("Second"), queue.notes.value.map(InAppNote::title))

        // The suppression is asked afresh every time, not remembered from the first call.
        open.clear()
        queue.request(FIRST, "First again")
        assertEquals(listOf("Second", "First again"), queue.notes.value.map(InAppNote::title))
    }

    @Test
    fun `a disconnected banner is shown even while a review is open`() = runTest {
        val queue = InAppNotificationQueue(backgroundScope) { true }

        queue.disconnected("runner-node disconnected")

        assertEquals("runner-node disconnected", queue.visible?.title)
    }

    @Test
    fun `dismissing twice neither doubles the exit nor takes the next banner with it`() = runTest {
        val queue = InAppNotificationQueue(backgroundScope)
        queue.request(FIRST, "First")
        queue.request(SECOND, "Second")

        val head = queue.visible!!.id
        queue.dismiss(head)
        queue.dismiss(head)
        elapse(InAppNotificationMotion.ExitMs.toLong())

        assertEquals("Second", queue.visible?.title)
        elapse(InAppNotificationMotion.ExitMs.toLong())
        assertEquals("Second", queue.visible?.title)
    }

    @Test
    fun `clearing takes everything at once and cancels the timers with it`() = runTest {
        val queue = InAppNotificationQueue(backgroundScope)
        queue.request(FIRST, "First")
        queue.request(SECOND, "Second")

        queue.clear()
        assertNull(queue.visible)

        // The cancelled six-second timer cannot come back and dismiss whatever is there later.
        queue.request(THIRD, "Third")
        elapse(InAppNotificationMotion.LifetimeMs - 1)
        assertEquals("Third", queue.visible?.title)
    }

    private fun InAppNotificationQueue.request(identity: ReviewIdentity, title: String) =
        notify(
            kind = InAppNotificationKind.Request,
            target = InAppNotificationTarget.Review(identity),
            title = title,
            subtitle = "New request · studio-mac · funds move",
            openActionLabel = "$title, open request",
            dismissActionLabel = "Dismiss",
        )

    private fun InAppNotificationQueue.disconnected(title: String) =
        notify(
            kind = InAppNotificationKind.Disconnected,
            target = InAppNotificationTarget.PairAgain(CONNECTION_ID),
            title = title,
            subtitle = null,
            openActionLabel = "$title, open",
            dismissActionLabel = "Dismiss",
        )

    private companion object {
        const val TEN_MINUTES = 600_000L
        const val CONNECTION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val FIRST = ReviewIdentity.Private(CONNECTION_ID, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        val SECOND = ReviewIdentity.Private(CONNECTION_ID, "cccccccc-cccc-4ccc-8ccc-cccccccccccc")
        val THIRD = ReviewIdentity.Signal(CONNECTION_ID, "dddddddd-dddd-4ddd-8ddd-dddddddddddd")
    }
}

/**
 * `advanceTimeBy` runs what is scheduled strictly before the new time, so the task scheduled at
 * exactly that instant needs [runCurrent] to run. Every wait here means "and then that moment
 * arrived", which is what the specification's timings are written in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.elapse(millis: Long) {
    advanceTimeBy(millis)
    runCurrent()
}
