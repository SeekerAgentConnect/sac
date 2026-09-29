package io.github.brrenat.seekervault.notifications

import io.github.brrenat.seekervault.ReviewIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalLedgerTest {
    @Test
    fun `a connection counts as read once a whole read of it completes, until the app leaves`() {
        val ledger = ArrivalLedger().apply { onForeground() }
        assertFalse(ledger.wasRead(CONNECTION))

        ledger.markRead(CONNECTION)
        assertTrue(ledger.wasRead(CONNECTION))
        assertFalse(ledger.wasRead(OTHER_CONNECTION))

        ledger.onBackground()
        assertFalse(ledger.wasRead(CONNECTION))
    }

    @Test
    fun `marks are kept in order, bounded, and forgotten when the app leaves`() {
        val ledger = ArrivalLedger(capacity = 3).apply { onForeground() }
        val items = (1..5).map { ReviewIdentity.Private(CONNECTION, "request-$it") }

        ledger.markLive(items.take(2))
        ledger.markLive(items.drop(2))

        // Only the newest are held: a mark has to outlive the next look, not the session.
        assertEquals(items.drop(2).toSet(), ledger.live.value)
        assertEquals(items.drop(2), ledger.live.value.toList())

        ledger.onBackground()
        assertEquals(emptySet<ReviewIdentity>(), ledger.live.value)
    }

    @Test
    fun `a request and a signal with the same identifiers are marked apart`() {
        val ledger = ArrivalLedger().apply { onForeground() }

        ledger.markLive(listOf(ReviewIdentity.Private(CONNECTION, SHARED)))

        assertTrue(ReviewIdentity.Private(CONNECTION, SHARED) in ledger.live.value)
        assertFalse(ReviewIdentity.Signal(CONNECTION, SHARED) in ledger.live.value)
        assertFalse(ReviewIdentity.Private(OTHER_CONNECTION, SHARED) in ledger.live.value)
    }

    @Test
    fun `nothing read or marked outside a foreground session counts`() {
        val ledger = ArrivalLedger()
        val item = ReviewIdentity.Private(CONNECTION, SHARED)

        // A process a worker started, before any activity: there is no session yet.
        assertNull(ledger.session())
        ledger.markRead(CONNECTION)
        ledger.markLive(listOf(item))
        assertFalse(ledger.wasRead(CONNECTION))
        assertEquals(emptySet<ReviewIdentity>(), ledger.live.value)

        ledger.onForeground()
        ledger.markRead(CONNECTION)
        ledger.onBackground()
        // A worker's read while the app is away.
        ledger.markRead(CONNECTION)
        ledger.markLive(listOf(item))

        // Coming back starts from nothing, whatever happened while it was away.
        ledger.onForeground()
        assertFalse(ledger.wasRead(CONNECTION))
        assertEquals(emptySet<ReviewIdentity>(), ledger.live.value)
    }

    @Test
    fun `a read that started in an earlier session says nothing in this one`() {
        val ledger = ArrivalLedger().apply { onForeground() }
        val started = ledger.session()
        val item = ReviewIdentity.Private(CONNECTION, SHARED)

        ledger.onBackground()
        ledger.onForeground()
        ledger.markRead(CONNECTION, started)
        ledger.markLive(listOf(item), started)
        ledger.markQuiet(listOf(item), started)
        ledger.handOver(item)

        assertFalse(ledger.wasRead(CONNECTION))
        assertEquals(emptySet<ReviewIdentity>(), ledger.live.value)
        assertEquals(emptySet<ReviewIdentity>(), ledger.late.value)
    }

    @Test
    fun `coming back from a rotation is the same session`() {
        val ledger = ArrivalLedger().apply { onForeground() }
        val session = ledger.session()
        ledger.markRead(CONNECTION)

        ledger.onForeground()

        assertEquals(session, ledger.session())
        assertTrue(ledger.wasRead(CONNECTION))
    }

    @Test
    fun `only an item a catching-up read stored is handed over, and only once`() {
        val ledger = ArrivalLedger().apply { onForeground() }
        val quiet = ReviewIdentity.Signal(CONNECTION, SHARED)
        val other = ReviewIdentity.Signal(OTHER_CONNECTION, SHARED)
        ledger.markQuiet(listOf(quiet))

        ledger.handOver(other)
        assertEquals(emptySet<ReviewIdentity>(), ledger.late.value)

        ledger.handOver(quiet)
        assertEquals(setOf(quiet), ledger.late.value)

        // Once: the next session does not inherit it, and a second event is a duplicate.
        ledger.onBackground()
        ledger.onForeground()
        ledger.handOver(quiet)
        assertEquals(emptySet<ReviewIdentity>(), ledger.late.value)
    }

    private companion object {
        const val CONNECTION = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val OTHER_CONNECTION = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val SHARED = "99999999-9999-4999-8999-999999999999"
    }
}
