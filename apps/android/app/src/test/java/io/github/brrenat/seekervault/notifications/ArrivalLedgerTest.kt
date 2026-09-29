package io.github.brrenat.seekervault.notifications

import io.github.brrenat.seekervault.ReviewIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalLedgerTest {
    @Test
    fun `a connection counts as read once a whole read of it completes, until the app leaves`() {
        val ledger = ArrivalLedger()
        assertFalse(ledger.wasRead(CONNECTION))

        ledger.markRead(CONNECTION)
        assertTrue(ledger.wasRead(CONNECTION))
        assertFalse(ledger.wasRead(OTHER_CONNECTION))

        ledger.onBackground()
        assertFalse(ledger.wasRead(CONNECTION))
    }

    @Test
    fun `marks are kept in order, bounded, and forgotten when the app leaves`() {
        val ledger = ArrivalLedger(capacity = 3)
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
        val ledger = ArrivalLedger()

        ledger.markLive(listOf(ReviewIdentity.Private(CONNECTION, SHARED)))

        assertTrue(ReviewIdentity.Private(CONNECTION, SHARED) in ledger.live.value)
        assertFalse(ReviewIdentity.Signal(CONNECTION, SHARED) in ledger.live.value)
        assertFalse(ReviewIdentity.Private(OTHER_CONNECTION, SHARED) in ledger.live.value)
    }

    private companion object {
        const val CONNECTION = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val OTHER_CONNECTION = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val SHARED = "99999999-9999-4999-8999-999999999999"
    }
}
