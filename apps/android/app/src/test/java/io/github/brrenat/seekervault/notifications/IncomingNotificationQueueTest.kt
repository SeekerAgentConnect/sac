package io.github.brrenat.seekervault.notifications

import io.github.brrenat.seekervault.ReviewIdentity
import io.github.brrenat.seekervault.designsystem.InAppNotificationKind
import io.github.brrenat.seekervault.designsystem.InAppNotificationMotion
import io.github.brrenat.seekervault.notifications.IncomingNotificationPolicy.COOLDOWN_MS
import io.github.brrenat.seekervault.notifications.IncomingNotificationPolicy.LIFETIME_MS
import io.github.brrenat.seekervault.notifications.IncomingNotificationPolicy.MOST_COUNTED
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The incoming-request half of the queue (SEE-175): one banner for a burst, a fixed lifetime, a
 * cooldown, and nothing that grows without bound. All time is virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncomingNotificationQueueTest {
    private val exit = InAppNotificationMotion.ExitMs.toLong()

    @Test
    fun `the limits are the documented ones`() {
        assertEquals(6_000L, LIFETIME_MS)
        assertEquals(30_000L, COOLDOWN_MS)
        assertEquals(99, MOST_COUNTED)
    }

    @Test
    fun `one isolated request keeps its own words and opens its own review`() = runTest {
        val queue = queue()

        queue.arrive(listOf(request(1)))

        assertEquals(1, queue.notes.value.size)
        assertEquals("single ${request(1).requestId}", queue.visible?.title)
        assertEquals(InAppNotificationTarget.Review(request(1)), queue.visible?.target)
        assertTrue(queue.visible!!.armed)
    }

    @Test
    fun `a burst of twenty unique requests is one banner with the count that opens the Inbox`() =
        runTest {
            val queue = queue()

            queue.arrive((1..20).map(::request))

            assertEquals(1, queue.notes.value.size)
            assertEquals("20 new · 1 source", queue.visible?.title)
            assertEquals(InAppNotificationTarget.Inbox, queue.visible?.target)

            // It goes after its six seconds, and nothing is queued behind it.
            elapse(LIFETIME_MS + exit)
            assertNull(queue.visible)
            assertTrue(queue.notes.value.isEmpty())
        }

    @Test
    fun `arrivals one by one merge into the banner on screen without resetting its lifetime`() =
        runTest {
            val queue = queue()
            queue.arrive(listOf(request(1)))
            val id = queue.visible!!.id

            // One more every half second while it is on screen.
            repeat(11) { n ->
                elapse(500)
                queue.arrive(listOf(request(n + 2)))
            }

            // The same banner the whole time: merged into, never queued behind itself.
            assertEquals(listOf(id), queue.notes.value.map(InAppNote::id))
            assertEquals("12 new · 1 source", queue.visible?.title)
            // Six seconds after it appeared it is going, however much kept arriving.
            elapse(LIFETIME_MS - currentTime)
            assertTrue(queue.visible!!.leaving)
            elapse(exit)
            assertNull(queue.visible)
            // And what comes next waits for the cooldown instead of recreating it.
            queue.arrive(listOf(request(13)))
            assertNull(queue.visible)
            assertTrue(queue.coolingDown)
        }

    @Test
    fun `the same item delivered twice is counted once`() = runTest {
        val queue = queue()

        queue.arrive(listOf(request(1), request(2)))
        queue.arrive(listOf(request(2), request(1), request(2)))
        queue.arrive(listOf(request(3)))

        assertEquals("3 new · 1 source", queue.visible?.title)
    }

    @Test
    fun `a burst over several servers is one banner and never confuses their identifiers`() =
        runTest {
            val queue = queue()

            // The same request ID on two servers, and a request and a signal sharing one: four
            // different things.
            queue.arrive(
                listOf(
                    ReviewIdentity.Private(SERVER_A, SAME_ID),
                    ReviewIdentity.Private(SERVER_B, SAME_ID),
                    ReviewIdentity.Signal(SERVER_B, SAME_ID),
                    ReviewIdentity.Signal(SERVER_C, OTHER_ID),
                )
            )

            assertEquals(1, queue.notes.value.size)
            assertEquals("4 new · 3 sources", queue.visible?.title)
            assertEquals(InAppNotificationTarget.Inbox, queue.visible?.target)
        }

    @Test
    fun `dismissing the banner does not bring the same burst straight back`() = runTest {
        val queue = queue()
        queue.arrive((1..5).map(::request))

        elapse(1_000)
        queue.dismiss(queue.visible!!.id)
        elapse(exit)
        assertNull(queue.visible)
        assertTrue(queue.coolingDown)

        // The burst goes on. Nothing appears for the whole cooldown.
        for (n in 6..30) {
            queue.arrive(listOf(request(n)))
            elapse(1_000)
            if (currentTime < 1_000 + exit + COOLDOWN_MS) assertNull(queue.visible)
        }
        elapse(1_000 + exit + COOLDOWN_MS - currentTime)

        // Then one banner says what arrived meanwhile — and only that, not the five it replaced.
        assertEquals("25 new · 1 source", queue.visible?.title)
        assertEquals(1, queue.notes.value.size)
    }

    @Test
    fun `sustained traffic for a minute and more raises one banner per cooldown and no queue`() =
        runTest {
            val queue = queue()
            val shown = mutableListOf<Pair<Long, String>>() // (first seen at, title then)
            var seen = -1L
            var most = 0

            // Ten new requests a second, for ninety seconds, over three servers.
            var next = 1
            while (currentTime < 90_000) {
                queue.arrive(
                    (0 until 10).map {
                        val n = next++
                        val server = listOf(SERVER_A, SERVER_B, SERVER_C)[n % 3]
                        ReviewIdentity.Private(server, uuid(n))
                    }
                )
                queue.visible?.let {
                    if (it.id != seen) {
                        seen = it.id
                        shown += currentTime to it.title
                    }
                }
                most = maxOf(most, queue.notes.value.size)
                elapse(100)
            }

            // Never more than one banner at a time, visible or waiting.
            assertEquals(1, most)
            // At 0, after the first lifetime, exit and cooldown, and after the second.
            val period = LIFETIME_MS + exit + COOLDOWN_MS
            assertEquals(listOf(0L, period, 2 * period), shown.map { it.first })
            // The first says what arrived with it; each later one what was held for it, bounded
            // however much that was.
            assertEquals(
                listOf(
                    "10 new · 3 sources",
                    "$MOST_COUNTED+ new · 3 sources",
                    "$MOST_COUNTED+ new · 3 sources",
                ),
                shown.map { it.second },
            )
        }

    @Test
    fun `what was answered during the cooldown is not told afterwards`() = runTest {
        val queue = queue()
        queue.arrive(listOf(request(1)))
        elapse(LIFETIME_MS + exit)

        queue.arrive(listOf(request(2), request(3)))
        // The owner answers both before the cooldown ends.
        queue.retainWaiting(setOf(request(1)))
        elapse(COOLDOWN_MS)

        assertNull(queue.visible)
    }

    @Test
    fun `an error or the result of an action is never held behind a waiting burst`() = runTest {
        val queue = queue()
        queue.disconnected()
        queue.arrive((1..3).map(::request))
        // The burst waits behind the disconnection, which has no timer.
        assertEquals(
            listOf(InAppNotificationKind.Disconnected, InAppNotificationKind.Request),
            queue.notes.value.map(InAppNote::kind),
        )

        queue.notice("Rules saved")

        assertEquals(
            listOf(
                InAppNotificationKind.Disconnected,
                InAppNotificationKind.Info,
                InAppNotificationKind.Request,
            ),
            queue.notes.value.map(InAppNote::kind),
        )
    }

    @Test
    fun `a notice raised while the burst banner is on screen is shown after it, not dropped`() =
        runTest {
            val queue = queue()
            queue.arrive((1..3).map(::request))
            queue.notice("Connection added")
            queue.arrive((4..9).map(::request))

            assertEquals(2, queue.notes.value.size)
            assertEquals("9 new · 1 source", queue.visible?.title)

            elapse(LIFETIME_MS + exit)
            assertEquals("Connection added", queue.visible?.title)
            // The cooldown is about request banners; the notice is not throttled by it.
            assertTrue(queue.coolingDown)
        }

    @Test
    fun `an item whose review is open is not counted`() = runTest {
        val queue = queue(open = setOf(request(2)))

        queue.arrive(listOf(request(1), request(2)))

        assertEquals("single ${request(1).requestId}", queue.visible?.title)
    }

    @Test
    fun `a review opened during the cooldown is not announced when it ends`() = runTest {
        val open = mutableSetOf<ReviewIdentity>()
        val queue = queue(open = open)
        queue.arrive(listOf(request(1)))
        elapse(LIFETIME_MS + exit)
        assertTrue(queue.coolingDown)

        queue.arrive(listOf(request(2)))
        // The owner opens it from the Inbox while the cooldown runs; it is still waiting.
        open += request(2)
        elapse(COOLDOWN_MS)

        assertNull(queue.visible)
        assertTrue(queue.notes.value.isEmpty())
    }

    @Test
    fun `a cooldown digest leaves out the one review opened meanwhile`() = runTest {
        val open = mutableSetOf<ReviewIdentity>()
        val queue = queue(open = open)
        queue.arrive(listOf(request(1)))
        elapse(LIFETIME_MS + exit)

        queue.arrive(listOf(request(2), request(3)))
        open += request(2)
        elapse(COOLDOWN_MS)

        assertEquals("single ${request(3).requestId}", queue.visible?.title)
        assertEquals(InAppNotificationTarget.Review(request(3)), queue.visible?.target)
    }

    @Test
    fun `a burst that waited behind a disconnection is checked again when it reaches the front`() =
        runTest {
            val open = mutableSetOf<ReviewIdentity>()
            val queue = queue(open = open)
            queue.disconnected()
            queue.arrive((1..3).map(::request))
            open += listOf(request(1), request(2))

            queue.dismiss(queue.visible!!.id)
            elapse(exit)

            assertEquals("single ${request(3).requestId}", queue.visible?.title)
            assertTrue(queue.visible!!.armed)
        }

    @Test
    fun `a burst whose every review was opened while it waited is never shown`() = runTest {
        val open = mutableSetOf<ReviewIdentity>()
        val queue = queue(open = open)
        queue.disconnected()
        queue.arrive(listOf(request(1)))
        open += request(1)

        queue.dismiss(queue.visible!!.id)
        elapse(exit)

        assertNull(queue.visible)
        // Nothing was shown, so nothing cools down: the next arrival is told at once.
        assertFalse(queue.coolingDown)
        queue.arrive(listOf(request(2)))
        assertEquals("single ${request(2).requestId}", queue.visible?.title)
    }

    @Test
    fun `leaving the foreground forgets the burst, the cooldown and what was held`() = runTest {
        val queue = queue()
        queue.arrive(listOf(request(1)))
        elapse(LIFETIME_MS + exit)
        queue.arrive(listOf(request(2)))
        assertTrue(queue.coolingDown)

        queue.clear()

        assertFalse(queue.coolingDown)
        elapse(COOLDOWN_MS)
        assertNull(queue.visible)
        // And the next arrival is shown at once.
        queue.arrive(listOf(request(3)))
        assertEquals("single ${request(3).requestId}", queue.visible?.title)
    }

    private fun TestScope.queue(open: Set<ReviewIdentity> = emptySet()) =
        InAppNotificationQueue(backgroundScope, describe = ::describe) { it in open }

    private fun InAppNotificationQueue.disconnected() =
        notify(
            kind = InAppNotificationKind.Disconnected,
            target = InAppNotificationTarget.PairAgain(SERVER_A),
            title = "runner-node disconnected",
            subtitle = null,
            openActionLabel = "runner-node disconnected, open",
            dismissActionLabel = "Dismiss",
        )

    private fun InAppNotificationQueue.notice(text: String) =
        notify(
            kind = InAppNotificationKind.Info,
            target = InAppNotificationTarget.Dismiss,
            title = text,
            subtitle = null,
            openActionLabel = "Dismiss",
            dismissActionLabel = "Dismiss",
        )

    private companion object {
        const val SERVER_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val SERVER_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val SERVER_C = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val SAME_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_ID = "22222222-2222-4222-8222-222222222222"

        fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

        fun request(n: Int) = ReviewIdentity.Private(SERVER_A, uuid(n))

        /** Words a test can read: one item by its ID, several by count and sources. */
        fun describe(burst: IncomingBurst): InAppNoteCopy {
            burst.single?.let {
                return InAppNoteCopy(
                    kind = InAppNotificationKind.Request,
                    target = InAppNotificationTarget.Review(it),
                    title = "single ${it.requestId}",
                    subtitle = null,
                    openActionLabel = "open",
                    dismissActionLabel = "Dismiss",
                )
            }
            val sources = burst.items.map { it.connectionId }.distinct().size
            val count = "${burst.items.size}${if (burst.overflow) "+" else ""}"
            return InAppNoteCopy(
                kind = InAppNotificationKind.Request,
                target = InAppNotificationTarget.Inbox,
                title = "$count new · $sources source${if (sources == 1) "" else "s"}",
                subtitle = null,
                openActionLabel = "open inbox",
                dismissActionLabel = "Dismiss",
            )
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.elapse(millis: Long) {
    advanceTimeBy(millis)
    runCurrent()
}
