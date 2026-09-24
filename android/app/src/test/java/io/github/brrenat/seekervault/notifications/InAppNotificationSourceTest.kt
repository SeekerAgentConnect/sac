package io.github.brrenat.seekervault.notifications

import io.github.brrenat.seekervault.ReviewIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class InAppNotificationSourceTest {
    private val source = InAppNotificationSource()

    @Test
    fun `nothing is announced until every list a banner reads has been filled`() {
        assertEquals(
            emptyList<InAppNotificationArrival>(),
            source.accept(snapshot(ready = false, waiting = listOf(FIRST))),
        )
        // The first read is the baseline, not an arrival: the owner has been carrying this inbox.
        assertEquals(
            emptyList<InAppNotificationArrival>(),
            source.accept(snapshot(waiting = listOf(FIRST))),
        )
    }

    @Test
    fun `only what appeared since the last look is announced, in the order it is waiting in`() {
        source.accept(snapshot(waiting = listOf(FIRST)))

        assertEquals(
            listOf(
                InAppNotificationArrival.Waiting(SECOND),
                InAppNotificationArrival.Waiting(THIRD),
            ),
            source.accept(snapshot(waiting = listOf(FIRST, SECOND, THIRD))),
        )
        // Answering one is not an event, and nothing left behind is announced a second time.
        assertEquals(
            emptyList<InAppNotificationArrival>(),
            source.accept(snapshot(waiting = listOf(SECOND, THIRD))),
        )
    }

    @Test
    fun `a server that ended the pairing is announced once`() {
        source.accept(snapshot())

        assertEquals(
            listOf(InAppNotificationArrival.Disconnected(CONNECTION_ID)),
            source.accept(snapshot(disconnected = listOf(CONNECTION_ID))),
        )
        assertEquals(
            emptyList<InAppNotificationArrival>(),
            source.accept(snapshot(disconnected = listOf(CONNECTION_ID))),
        )
        // A second server ending its own pairing is its own event.
        assertEquals(
            listOf(InAppNotificationArrival.Disconnected(OTHER_CONNECTION_ID)),
            source.accept(snapshot(disconnected = listOf(CONNECTION_ID, OTHER_CONNECTION_ID))),
        )
    }

    @Test
    fun `what arrived while the app was away is the system notification's, not a banner's`() {
        source.accept(snapshot(waiting = listOf(FIRST)))
        assertEquals(
            listOf(InAppNotificationArrival.Waiting(SECOND)),
            source.accept(snapshot(waiting = listOf(FIRST, SECOND))),
        )

        source.reset()

        // Two more arrived and one server disconnected while the app was in the background.
        assertEquals(
            emptyList<InAppNotificationArrival>(),
            source.accept(
                snapshot(
                    waiting = listOf(FIRST, SECOND, THIRD),
                    disconnected = listOf(CONNECTION_ID),
                )
            ),
        )
        // And the next thing to arrive after the owner came back is a banner again.
        assertEquals(
            listOf(InAppNotificationArrival.Waiting(FOURTH)),
            source.accept(
                snapshot(
                    waiting = listOf(FIRST, SECOND, THIRD, FOURTH),
                    disconnected = listOf(CONNECTION_ID),
                )
            ),
        )
    }

    @Test
    fun `a request and a signal with the same identifiers are different things`() {
        val request = ReviewIdentity.Private(CONNECTION_ID, SHARED_ID)
        val signal = ReviewIdentity.Signal(CONNECTION_ID, SHARED_ID)
        source.accept(snapshot(waiting = listOf(request)))

        assertEquals(
            listOf(InAppNotificationArrival.Waiting(signal)),
            source.accept(snapshot(waiting = listOf(request, signal))),
        )
    }

    private fun snapshot(
        ready: Boolean = true,
        waiting: List<ReviewIdentity> = emptyList(),
        disconnected: List<String> = emptyList(),
    ) =
        InAppNotificationSnapshot(
            ready = ready,
            waiting = LinkedHashSet(waiting),
            disconnected = LinkedHashSet(disconnected),
        )

    private companion object {
        const val CONNECTION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val OTHER_CONNECTION_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val SHARED_ID = "99999999-9999-4999-8999-999999999999"
        val FIRST = ReviewIdentity.Private(CONNECTION_ID, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        val SECOND = ReviewIdentity.Private(CONNECTION_ID, "cccccccc-cccc-4ccc-8ccc-cccccccccccc")
        val THIRD = ReviewIdentity.Signal(CONNECTION_ID, "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
        val FOURTH = ReviewIdentity.Private(CONNECTION_ID, "ffffffff-ffff-4fff-8fff-ffffffffffff")
    }
}
