package io.github.brrenat.seekervault.policy

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.ActivityRecord
import io.github.brrenat.seekervault.policy.storage.PolicyStore
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The assessments the phone makes from what is stored right now (docs/policy.md#re-evaluation): the
 * connection's rules as they are on disk, and the counters as the app's own records have them.
 */
@RunWith(AndroidJUnit4::class)
class PolicyEvaluatorTest {
    @get:Rule val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "files/policies").apply { mkdirs() } }
    private var clock = Instant.parse("2026-09-12T12:00:00Z")
    private var zone = ZoneId.of("UTC")
    private var records = emptyList<ActivityRecord>()

    private fun evaluator(store: PolicyStore = PolicyStore(dir)) =
        PolicyEvaluator(store, { records }, { clock }, { zone })

    private fun save(policy: ConnectionPolicy) = PolicyStore(dir).put(policy)

    @Test
    fun aConnectionWithNoRulesIsUnderRestrictions() {
        val decision = evaluator().evaluate(solFacts())

        assertFalse(decision.allowed)
        assertEquals(listOf("no_policy_configured"), decision.reasonCodes)
    }

    @Test
    fun theRulesOnDiskAreTheOnesApplied() {
        save(policy().copy(actions = Allowlist.of(PolicyAction.Transfer)))

        assertTrue(evaluator().evaluate(solFacts()).allowed)
    }

    @Test
    fun theRulesAreReadAgainEveryTimeSoAChangeIsNeverMissed() {
        val evaluator = evaluator()
        save(policy().copy(actions = Allowlist.of(PolicyAction.Transfer)))
        assertTrue(evaluator.evaluate(solFacts()).allowed)

        // The owner edits the policy between the review and the moment they proceed. Asking again
        // is the whole of re-evaluating, because there is no stored verdict to go stale.
        save(policy().copy(actions = Allowlist.of(PolicyAction.Acknowledgement)))

        assertFalse(evaluator.evaluate(solFacts()).allowed)
    }

    @Test
    fun theCountersAreReadAgainEveryTimeToo() {
        save(
            policy()
                .copy(
                    assets = Allowlist.of(SOL),
                    limits = mapOf(SOL to AssetLimits(daily = 2UL * ONE_SOL)),
                )
        )
        val evaluator = evaluator()
        assertTrue(evaluator.evaluate(solFacts()).allowed)

        records = listOf(record("earlier", answeredAt = clock, amount = (2UL * ONE_SOL).toString()))

        assertFalse(evaluator.evaluate(solFacts()).allowed)
    }

    @Test
    fun theRulesAndTheCountersBothSurviveARestart() {
        save(
            policy()
                .copy(
                    assets = Allowlist.of(SOL),
                    limits = mapOf(SOL to AssetLimits(daily = ONE_SOL)),
                )
        )
        records = listOf(record("earlier", answeredAt = clock, amount = ONE_SOL.toString()))
        val before = evaluator().evaluate(solFacts())

        // A new store over the same directory is what the next launch has.
        val after = evaluator(PolicyStore(dir)).evaluate(solFacts())

        assertEquals(before, after)
        assertEquals(listOf("over_daily_limit"), after.reasonCodes)
    }

    @Test
    fun aDayEndsAtLocalMidnightAndTheCountersStartAgain() {
        save(
            policy()
                .copy(
                    assets = Allowlist.of(SOL),
                    limits = mapOf(SOL to AssetLimits(daily = ONE_SOL)),
                )
        )
        records = listOf(record("earlier", answeredAt = clock, amount = ONE_SOL.toString()))
        val evaluator = evaluator()
        assertFalse(evaluator.evaluate(solFacts(amount = 1UL)).allowed)

        clock = Instant.parse("2026-09-13T00:00:00Z")

        assertTrue(evaluator.evaluate(solFacts(amount = 1UL)).allowed)
        assertEquals(LocalDate.of(2026, 9, 13), evaluator.today())
    }

    @Test
    fun theDayIsTheOneThePhoneIsIn() {
        records = listOf(record("earlier", answeredAt = Instant.parse("2026-09-12T12:00:00Z")))
        val evaluator = evaluator()

        zone = ZoneId.of("UTC")
        assertEquals(ONE_SOL, checkNotNull(evaluator.spentToday(scopeOf(solFacts()))).confirmed)

        // Carried west far enough, the same instant is still yesterday, and the phone says so.
        zone = ZoneId.of("Pacific/Honolulu")
        clock = Instant.parse("2026-09-13T05:00:00Z")

        assertEquals(LocalDate.of(2026, 9, 12), evaluator.today())
        assertEquals(ONE_SOL, checkNotNull(evaluator.spentToday(scopeOf(solFacts()))).confirmed)
    }

    @Test
    fun aRequestWithNoScopeIsAssessedWithoutCounters() {
        save(
            policy()
                .copy(
                    assets = Allowlist.of(SOL),
                    limits = mapOf(SOL to AssetLimits(daily = ONE_SOL)),
                )
        )
        // No wallet connected: there is no counter to read, and the threshold can't be applied.
        val decision = evaluator().evaluate(solFacts(wallet = null, asset = null))

        assertFalse(decision.allowed)
        assertTrue(decision.unverified.contains(PolicyCheck.DailyLimit))
    }

    @Test
    fun eachConnectionIsAssessedAgainstItsOwnRules() {
        save(policy().copy(actions = Allowlist.of(PolicyAction.Transfer)))
        save(
            ConnectionPolicy.default(OTHER_CONNECTION, NOW)
                .copy(actions = Allowlist.of(PolicyAction.Acknowledgement))
        )
        val evaluator = evaluator()

        assertTrue(evaluator.evaluate(solFacts()).allowed)
        assertFalse(evaluator.evaluate(solFacts(connectionId = OTHER_CONNECTION)).allowed)
    }

    @Test
    fun oneConnectionsSpendingIsNotCountedAgainstAnothers() {
        val rules = { id: String ->
            ConnectionPolicy.default(id, NOW)
                .copy(
                    assets = Allowlist.of(SOL),
                    limits = mapOf(SOL to AssetLimits(daily = ONE_SOL)),
                )
        }
        save(rules(CONNECTION))
        save(rules(OTHER_CONNECTION))
        // One connection has already spent the day's allowance, on the same wallet.
        records = listOf(record("earlier", answeredAt = clock, amount = ONE_SOL.toString()))
        val evaluator = evaluator()

        assertFalse(evaluator.evaluate(solFacts(amount = 1UL)).allowed)
        assertTrue(
            evaluator.evaluate(solFacts(connectionId = OTHER_CONNECTION, amount = 1UL)).allowed
        )
    }

    @Test
    fun aDayNobodyHasReadYetIsNotADayWithNothingInIt() {
        // The history is read off the disk asynchronously, and a read can fail. Until one has
        // succeeded there is no day's total — which is not the same as a day with nothing in it,
        // and the difference is the whole of whether a daily threshold means anything. Reporting
        // an unread day as empty would pass every request through every daily threshold there is.
        save(
            policy()
                .copy(
                    assets = Allowlist.of(SOL),
                    limits = mapOf(SOL to AssetLimits(daily = ONE_SOL)),
                )
        )
        records = listOf(record("earlier", answeredAt = clock, amount = ONE_SOL.toString()))
        assertEquals(
            listOf("over_daily_limit"),
            evaluator().evaluate(solFacts(amount = 1UL)).reasonCodes,
        )

        val unread = PolicyEvaluator(PolicyStore(dir), { null }, { clock }, { zone })
        val decision = unread.evaluate(solFacts(amount = 1UL))

        assertFalse(decision.allowed)
        assertEquals(listOf("daily_total_unverified"), decision.reasonCodes)
        assertTrue(decision.unverified.contains(PolicyCheck.DailyLimit))
        assertNull(unread.spentToday(scopeOf(solFacts())))
    }

    @Test
    fun aDayThatWasReadAndHoldsNothingIsADayWithNothingInIt() {
        save(
            policy()
                .copy(
                    assets = Allowlist.of(SOL),
                    limits = mapOf(SOL to AssetLimits(daily = ONE_SOL)),
                )
        )
        // An empty list is the app having looked, and it passes on its own terms.
        records = emptyList()

        assertTrue(evaluator().evaluate(solFacts(amount = 1UL)).allowed)
    }

    @Test
    fun assessingChangesNothingOnDisk() {
        save(policy().copy(actions = Allowlist.of(PolicyAction.Transfer)))
        val file = checkNotNull(dir.listFiles()).single()
        val before = file.readText()
        val modified = file.lastModified()
        records = listOf(record("earlier", answeredAt = clock, outcome = ActivityOutcome.Confirmed))
        val kept = records

        repeat(5) { evaluator().evaluate(solFacts()) }

        assertEquals(before, file.readText())
        assertEquals(modified, file.lastModified())
        assertEquals(1, checkNotNull(dir.listFiles()).size)
        // And it read the owner's records without touching them.
        assertEquals(kept, records)
    }
}
