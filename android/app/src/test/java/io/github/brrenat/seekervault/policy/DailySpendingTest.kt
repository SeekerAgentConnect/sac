package io.github.brrenat.seekervault.policy

import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.request.v1.Network
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daily counters (docs/policy.md#counters): what this app moved today, counted from its own
 * records, with what is settled kept apart from what isn't.
 */
class DailySpendingTest {
    private val utc = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 9, 12)
    private val scope = SpendScope(CONNECTION, WALLET, SOL)

    private fun total(vararg records: io.github.brrenat.seekervault.activity.ActivityRecord) =
        dailyTotal(spendsOf(records.toList()), scope, today, utc)

    @Test
    fun whatTheChainConfirmedAndWhatItHasntAreSeparateNumbers() {
        val totals =
            total(
                record("a", outcome = ActivityOutcome.Confirmed, amount = "700"),
                record("b", outcome = ActivityOutcome.Sent, amount = "20", signature = "sig-b"),
                record("c", outcome = ActivityOutcome.Unknown, amount = "3", signature = "sig-c"),
            )

        assertEquals(700UL, totals.confirmed)
        assertEquals(23UL, totals.unresolved)
        assertEquals(1, totals.confirmedCount)
        assertEquals(2, totals.unresolvedCount)
        // The projected total exists for one purpose, and it never claims the 23 were spent.
        assertEquals(723UL, totals.projected)
        assertTrue(totals.known)
    }

    @Test
    fun aRejectionIsNotATransfer() {
        val totals =
            total(
                record("a", outcome = ActivityOutcome.Rejected, amount = "500"),
                record("b", outcome = ActivityOutcome.DeclinedInWallet, amount = "500"),
                record("c", outcome = ActivityOutcome.NotSigned, amount = "500"),
            )

        assertEquals(DailyTotal.none(scope, today), totals)
    }

    @Test
    fun aTransactionTheChainRanAndFailedMovedNothing() {
        val totals =
            total(
                record("a", outcome = ActivityOutcome.ChainFailed, amount = "500", signature = "s")
            )

        assertEquals(0UL, totals.projected)
        assertEquals(0, totals.unresolvedCount)
    }

    @Test
    fun anApprovalTheWalletHasntAnsweredIsExposure() {
        // The server took the approval and the wallet has the transaction. Nobody can say it didn't
        // go out, and telling the owner they have room they may not have is the one wrong answer.
        val totals = total(record("a", outcome = ActivityOutcome.Waiting, amount = "40"))

        assertEquals(0UL, totals.confirmed)
        assertEquals(40UL, totals.unresolved)
    }

    @Test
    fun anUndeliveredAnswerCountsOnlyOnceTheWalletSigned() {
        val unsigned = total(record("a", outcome = ActivityOutcome.NotDelivered, amount = "40"))
        val signed =
            total(
                record("a", outcome = ActivityOutcome.NotDelivered, amount = "40", signature = "s")
            )

        assertEquals(0UL, unsigned.projected)
        assertEquals(40UL, signed.unresolved)
    }

    @Test
    fun nothingButATransferIsCounted() {
        val totals =
            total(
                record("a", kind = ActivityKind.MessageSignature, signature = "s"),
                record("b", kind = ActivityKind.Acknowledgement),
            )

        assertEquals(DailyTotal.none(scope, today), totals)
    }

    @Test
    fun oneRequestIsOneMovementHoweverManyTimesItWasWritten() {
        // The same request, recorded as sent and then as confirmed. It is one payment.
        val totals =
            total(
                record("a", outcome = ActivityOutcome.Sent, amount = "500"),
                record("a", outcome = ActivityOutcome.Confirmed, amount = "500"),
            )

        assertEquals(500UL, totals.confirmed)
        assertEquals(0UL, totals.unresolved)
        assertEquals(1, totals.confirmedCount)
    }

    @Test
    fun oneSignatureIsOneMovementHoweverManyRequestsCarryIt() {
        val totals =
            total(
                record("a", outcome = ActivityOutcome.Sent, amount = "500", signature = "sig"),
                record("b", outcome = ActivityOutcome.Confirmed, amount = "500", signature = "sig"),
            )

        assertEquals(500UL, totals.projected)
        assertEquals(1, totals.confirmedCount)
        assertEquals(0, totals.unresolvedCount)
    }

    @Test
    fun aDayRunsFromLocalMidnightToLocalMidnight() {
        val kiritimati = ZoneId.of("Pacific/Kiritimati")
        val records =
            listOf(
                // 2026-09-12T09:59:59.999Z is 2026-09-12T23:59:59.999+14:00.
                record("a", answeredAt = Instant.parse("2026-09-12T09:59:59.999Z"), amount = "1"),
                // One millisecond later it is the next day there.
                record("b", answeredAt = Instant.parse("2026-09-12T10:00:00Z"), amount = "10"),
            )
        val spends = spendsOf(records)

        assertEquals(1UL, dailyTotal(spends, scope, today, kiritimati).confirmed)
        assertEquals(
            10UL,
            dailyTotal(spends, scope, LocalDate.of(2026, 9, 13), kiritimati).confirmed,
        )
    }

    @Test
    fun aPhoneCarriedIntoAnotherZoneReadsItsOwnHistoryInTheZoneItIsIn() {
        // The records hold instants and are never rewritten; the day is worked out when they are
        // read. A phone that flies east therefore sees one payment move into the next day, and the
        // counters stay a question about where the phone is now.
        // 2026-09-12T23:00Z is still the 12th in Honolulu and already the 13th in Berlin.
        val spends =
            spendsOf(listOf(record("a", answeredAt = Instant.parse("2026-09-12T23:00:00Z"))))

        assertEquals(
            ONE_SOL,
            dailyTotal(spends, scope, today, ZoneId.of("Pacific/Honolulu")).confirmed,
        )
        assertEquals(0UL, dailyTotal(spends, scope, today, ZoneId.of("Europe/Berlin")).confirmed)
        assertEquals(
            ONE_SOL,
            dailyTotal(spends, scope, LocalDate.of(2026, 9, 13), ZoneId.of("Europe/Berlin"))
                .confirmed,
        )
    }

    @Test
    fun aMovementStaysInTheDayItWasAnsweredOn() {
        // The record is written again every time a status is checked. Counting by when it was last
        // written would walk yesterday's payment into today.
        val answered = Instant.parse("2026-09-11T23:00:00Z")
        val record =
            record("a", answeredAt = answered, outcome = ActivityOutcome.Confirmed)
                .copy(recordedAt = Instant.parse("2026-09-12T08:00:00Z"))

        assertEquals(0UL, dailyTotal(spendsOf(listOf(record)), scope, today, utc).confirmed)
        assertEquals(
            ONE_SOL,
            dailyTotal(spendsOf(listOf(record)), scope, LocalDate.of(2026, 9, 11), utc).confirmed,
        )
    }

    @Test
    fun twoConnectionsUsingOneWalletAreCountedApart() {
        val spends =
            spendsOf(
                listOf(
                    record("a", amount = "100"),
                    record("b", connectionId = OTHER_CONNECTION, amount = "100"),
                )
            )

        assertEquals(100UL, dailyTotal(spends, scope, today, utc).confirmed)
        assertEquals(
            100UL,
            dailyTotal(spends, SpendScope(OTHER_CONNECTION, WALLET, SOL), today, utc).confirmed,
        )
    }

    @Test
    fun twoWalletsSpendingOneMintAreCountedApart() {
        val usdc = SpendScope(CONNECTION, WALLET, USDC)
        val other = SpendScope(CONNECTION, OTHER_WALLET, USDC)
        val spends =
            spendsOf(
                listOf(
                    record("a", mint = MINT, amount = "100"),
                    record("b", mint = MINT, wallet = OTHER_WALLET, amount = "7"),
                )
            )

        assertEquals(100UL, dailyTotal(spends, usdc, today, utc).confirmed)
        assertEquals(7UL, dailyTotal(spends, other, today, utc).confirmed)
    }

    @Test
    fun oneMintOnTwoChainsIsTwoThingsToSpend() {
        val spends =
            spendsOf(
                listOf(
                    record("a", amount = "100"),
                    record("b", network = Network.NETWORK_DEVNET, amount = "999"),
                )
            )

        assertEquals(100UL, dailyTotal(spends, scope, today, utc).confirmed)
        assertEquals(
            999UL,
            dailyTotal(spends, SpendScope(CONNECTION, WALLET, SOL_ON_DEVNET), today, utc).confirmed,
        )
    }

    @Test
    fun oneRecordThatDidNotReadBackMakesTheDayUnknown() {
        val totals =
            total(
                record("a", amount = "500"),
                record("b", amount = "1.5"),
                record("c", amount = ""),
            )

        assertEquals(500UL, totals.confirmed)
        assertEquals(2, totals.unreadable)
        assertFalse(totals.known)
    }

    @Test
    fun aTotalThatWouldOverflowStopsAtTheLargestAmountThereIs() {
        val totals =
            total(
                record("a", amount = ULong.MAX_VALUE.toString()),
                record("b", amount = "1", outcome = ActivityOutcome.Sent, signature = "s"),
                record("c", amount = ULong.MAX_VALUE.toString(), outcome = ActivityOutcome.Sent),
            )

        assertEquals(ULong.MAX_VALUE, totals.confirmed)
        assertEquals(ULong.MAX_VALUE, totals.unresolved)
        // Wrapping round would read as an empty day, which is the one answer that misleads.
        assertEquals(ULong.MAX_VALUE, totals.projected)
    }

    @Test
    fun aScopeWithNoMovementsCountsNothing() {
        assertEquals(DailyTotal.none(scope, today), total())
        assertTrue(DailyTotal.none(scope, today).known)
    }

    @Test
    fun everyRecordedOutcomeHasACountingRule() {
        // A new outcome must be given a rule here rather than falling into a default, because the
        // default that costs the owner money is the one that silently counts nothing.
        val counted =
            ActivityOutcome.entries.map { outcome ->
                spendsOf(listOf(record("a", outcome = outcome, signature = "s"))).single().status
            }

        assertEquals(ActivityOutcome.entries.size, counted.size)
        assertTrue(counted.contains(SpendStatus.Confirmed))
        assertTrue(counted.contains(SpendStatus.Unresolved))
        assertTrue(counted.contains(SpendStatus.NotSpent))
    }
}
