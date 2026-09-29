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

    @Test
    fun `a feed's first snapshot fills the inbox without a single arrival`() {
        source.accept(snapshot(waiting = listOf(FIRST)))

        // Fifty proposals from a feed that was just connected: read, not delivered as news.
        val backlog = (1..50).map { ReviewIdentity.Signal(OTHER_CONNECTION_ID, uuid(it)) }
        assertEquals(
            emptyList<InAppNotificationArrival>(),
            source.accept(snapshot(waiting = listOf(FIRST) + backlog, live = emptyList())),
        )
        // And they are known now: a later look that happens to carry marks does not bring them
        // back as arrivals.
        assertEquals(
            emptyList<InAppNotificationArrival>(),
            source.accept(snapshot(waiting = listOf(FIRST) + backlog, live = backlog)),
        )
    }

    @Test
    fun `a live item that lands with the snapshot is announced and the backlog around it is not`() {
        source.accept(snapshot())

        val backlog = (1..50).map { ReviewIdentity.Signal(OTHER_CONNECTION_ID, uuid(it)) }
        val live = ReviewIdentity.Signal(OTHER_CONNECTION_ID, uuid(51))
        assertEquals(
            listOf(InAppNotificationArrival.Waiting(live)),
            source.accept(snapshot(waiting = backlog + live, live = listOf(live))),
        )
    }

    @Test
    fun `an item is announced once a session however often it leaves and comes back`() {
        source.accept(snapshot())
        assertEquals(
            listOf(InAppNotificationArrival.Waiting(FIRST)),
            source.accept(snapshot(waiting = listOf(FIRST))),
        )

        // A refresh that briefly lost it, a status update, a duplicate delivery: none is news.
        source.accept(snapshot(waiting = emptyList()))
        assertEquals(
            emptyList<InAppNotificationArrival>(),
            source.accept(snapshot(waiting = listOf(FIRST))),
        )
        assertEquals(
            emptyList<InAppNotificationArrival>(),
            source.accept(snapshot(waiting = listOf(FIRST))),
        )
    }

    @Test
    fun `a mark that arrives before its item is honoured when the item appears`() {
        source.accept(snapshot())

        // The repositories mark before they publish, so the mark can be a look ahead of the item.
        assertEquals(
            emptyList<InAppNotificationArrival>(),
            source.accept(snapshot(waiting = emptyList(), live = listOf(SECOND))),
        )
        assertEquals(
            listOf(InAppNotificationArrival.Waiting(SECOND)),
            source.accept(snapshot(waiting = listOf(SECOND), live = listOf(SECOND))),
        )
    }

    @Test
    fun `an item the snapshot got to before its live event is announced late, once`() {
        source.accept(snapshot())
        // The feed's first read: backlog, and one item published while it ran.
        val backlog = (1..3).map { ReviewIdentity.Signal(OTHER_CONNECTION_ID, uuid(it)) }
        val raced = ReviewIdentity.Signal(OTHER_CONNECTION_ID, uuid(4))
        assertEquals(
            emptyList<InAppNotificationArrival>(),
            source.accept(snapshot(waiting = backlog + raced, live = emptyList())),
        )

        // Its live event follows: known already, never announced, and news.
        assertEquals(
            listOf(InAppNotificationArrival.Waiting(raced)),
            source.accept(
                snapshot(waiting = backlog + raced, live = emptyList(), late = listOf(raced))
            ),
        )
        // Once.
        assertEquals(
            emptyList<InAppNotificationArrival>(),
            source.accept(
                snapshot(waiting = backlog + raced, live = emptyList(), late = listOf(raced))
            ),
        )
    }

    @Test
    fun `a late mark never repeats an item that was already announced, nor one in the baseline`() {
        source.accept(snapshot(waiting = listOf(FIRST), live = emptyList(), late = listOf(FIRST)))
        assertEquals(
            emptyList<InAppNotificationArrival>(),
            source.accept(
                snapshot(waiting = listOf(FIRST), live = emptyList(), late = listOf(FIRST))
            ),
        )

        assertEquals(
            listOf(InAppNotificationArrival.Waiting(SECOND)),
            source.accept(snapshot(waiting = listOf(FIRST, SECOND), late = listOf(FIRST))),
        )
        assertEquals(
            emptyList<InAppNotificationArrival>(),
            source.accept(snapshot(waiting = listOf(FIRST, SECOND), late = listOf(FIRST, SECOND))),
        )
    }

    private fun snapshot(
        ready: Boolean = true,
        waiting: List<ReviewIdentity> = emptyList(),
        disconnected: List<String> = emptyList(),
        // Unless a test says otherwise, everything waiting arrived as news.
        live: List<ReviewIdentity> = waiting,
        late: List<ReviewIdentity> = emptyList(),
    ) =
        InAppNotificationSnapshot(
            ready = ready,
            waiting = LinkedHashSet(waiting),
            disconnected = LinkedHashSet(disconnected),
            live = live.toSet(),
            late = late.toSet(),
        )

    private fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

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
