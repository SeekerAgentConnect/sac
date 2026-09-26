package io.github.brrenat.seekervault.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions a listener makes, tested without a socket (SEE-91).
 *
 * The continuity cases are the ones that matter most: one of them wrong by a boolean is a phone
 * that believes it missed nothing when it did, which is the only way a shared feed can quietly go
 * stale.
 */
class FeedRecoveryTest {
    private val held = FeedCursor("epoch-1", 41)

    private fun subscription(
        epoch: String = "epoch-1",
        offset: Long = 41,
        recoverable: Boolean = true,
        recovered: Boolean = false,
        wasRecovering: Boolean = true,
    ) = FeedSubscription(epoch, offset, recoverable, recovered, wasRecovering)

    @Test
    fun theBrokerHavingReplayedEverythingIsTheOnlyProofOfContinuity() {
        assertEquals(
            Continuity.Recovered,
            continuity(held, subscription(recovered = true, wasRecovering = true)),
        )
    }

    @Test
    fun everyOtherAnswerSendsTheListenerToTheSnapshot() {
        // Nothing held: a feed just added, or a phone that forgot where it was.
        assertEquals(
            Continuity.Snapshot(Continuity.Why.NothingHeld),
            continuity(null, subscription(recovered = true)),
        )
        // The history was replaced, so the offset counts in a stream that no longer exists. This is
        // the case a listener would get wrong by comparing offsets alone.
        assertEquals(
            Continuity.Snapshot(Continuity.Why.EpochChanged),
            continuity(held, subscription(epoch = "epoch-2", offset = 3, recovered = true)),
        )
        // Further behind than the broker keeps, or than it will replay at once.
        assertEquals(
            Continuity.Snapshot(Continuity.Why.TooFarBehind),
            continuity(held, subscription(offset = 900, recovered = false)),
        )
        // A broker configured without history at all: every reconnect reads the snapshot, which is
        // slower and still correct.
        assertEquals(
            Continuity.Snapshot(Continuity.Why.NotRecoverable),
            continuity(held, subscription(recoverable = false, recovered = true)),
        )
        // The stream opened without the channel, so nothing will ever arrive on it.
        assertEquals(
            Continuity.Snapshot(Continuity.Why.NotSubscribed),
            continuity(held, null),
        )
    }

    /**
     * The code says whether to come back; the `reconnect` field beside it does not. A graceful
     * shutdown carries `reconnect: false` and is the most ordinary reason to reconnect there is, so
     * reading that field would take a phone off a feed every time a node was deployed.
     */
    @Test
    fun aShutdownIsSomethingToComeBackFromAndALimitIsNot() {
        assertEquals(AfterClose.Reconnect(reticket = false), afterClose(3001))
        assertEquals(AfterClose.Reconnect(reticket = false), afterClose(3004))
        assertEquals(AfterClose.Reconnect(reticket = false), afterClose(3013))
        // The grant, or the state behind it, is stale: come back with a new ticket.
        assertEquals(AfterClose.Reconnect(reticket = true), afterClose(3005))
        assertEquals(AfterClose.Reconnect(reticket = true), afterClose(3014))
        assertEquals(AfterClose.Reconnect(reticket = true), afterClose(3500))
        // Terminal: something about this listener is wrong rather than late.
        assertEquals(AfterClose.Stop(3501), afterClose(3501))
        assertEquals(AfterClose.Stop(3502), afterClose(3502))
        assertEquals(AfterClose.Stop(3504), afterClose(3504))
        assertEquals(AfterClose.Stop(3505), afterClose(3505))
    }

    @Test
    fun backoffGrowsToACeilingAndIsSpreadOut() {
        val plain = { delay: Long -> delay }
        assertEquals(1_000, backoff(1, plain))
        assertEquals(2_000, backoff(2, plain))
        assertEquals(16_000, backoff(5, plain))
        assertEquals(30_000, backoff(6, plain))
        assertEquals(30_000, backoff(60, plain))
        // A gateway that is down is down for every phone subscribed to it, so no two come back in
        // the same instant — and jitter never turns into a negative delay or an unbounded one.
        for (attempt in 1..10) {
            assertTrue(backoff(attempt, { (it * 0.75).toLong() }) in 0..MAX_BACKOFF_MILLIS)
            assertTrue(backoff(attempt, { (it * 1.25).toLong() }) in 0..MAX_BACKOFF_MILLIS)
        }
    }
}
