package io.github.brrenat.seekervault.connections.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.plugins.JUPITER_PROVIDER
import io.github.brrenat.seekervault.plugins.JUPITER_SWAP
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginReference
import io.github.brrenat.seekervault.plugins.SWAP_ACTION
import io.github.brrenat.seekervault.proposals.PROPOSAL_A
import io.github.brrenat.seekervault.proposals.PROPOSAL_B
import io.github.brrenat.seekervault.proposals.ProposalDismissal
import io.github.brrenat.seekervault.proposals.ProposalExecution
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalProblem
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalReview
import io.github.brrenat.seekervault.proposals.SWAP
import io.github.brrenat.seekervault.proposals.binding
import io.github.brrenat.seekervault.proposals.choice
import io.github.brrenat.seekervault.proposals.hash
import io.github.brrenat.seekervault.proposals.proposal
import io.github.brrenat.seekervault.proposals.wireProposal
import java.io.File
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * What survives a restart (SEE-89): the publisher's document, and this device's decisions about it,
 * in one file per proposal under the feed it was read on.
 *
 * The round trip matters because a proposal's local half is the only copy there is. Nothing about
 * it is on a server to be fetched again: an execution nobody could read back would be an operation
 * this phone attempted and then forgot.
 */
@RunWith(AndroidJUnit4::class)
class ProposalStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "files/proposals") }
    private val store by lazy { ProposalStore(dir) }

    @Test
    fun aProposalAndEveryDecisionAboutItComeBackWhole() {
        val chose =
            ParameterChoice(
                mapOf(
                    ParameterKey("input_amount") to ParameterValue.Amount(ULong.MAX_VALUE),
                    ParameterKey("outcome") to ParameterValue.Selected(ParameterKey("yes")),
                    ParameterKey("slippage_bps") to ParameterValue.Count(50u),
                )
            )
        val record =
            ProposalRecord(
                connectionId = CONNECTION,
                proposal =
                    proposal(
                        wireProposal(
                            revision = 7,
                            note = "Ротация 📉",
                            values = listOf("published_price" to "139420000"),
                        )
                    ),
                dismissed = null,
                review = ProposalReview(revision = 7, choice = chose, at = AT),
                execution =
                    ProposalExecution(
                        binding =
                            binding(
                                proposal(wireProposal(revision = 7)),
                                chose,
                                expiresAtEpochSeconds = AT.epochSecond + 90,
                            ),
                        startedAt = AT,
                        outcome = ProposalOutcome.Submitted(hash(3)),
                        settledAt = AT.plusSeconds(5),
                    ),
            )

        store.put(record)

        // The largest quantity a transaction can carry survives as itself: base units are written
        // as decimal text, not as a JSON number.
        assertEquals(record, store.get(CONNECTION, PROPOSAL_A))
    }

    @Test
    fun theRoutingAndServiceFeeTheOwnerApprovedSurviveARestart() {
        // What History says about who routed a swap and what fee it carried is what was approved,
        // so it is kept with the binding and comes back exactly (SEE-173).
        val receipt =
            listOf(
                PluginReference("swap_routing", "Metis · Powered by Jupiter"),
                PluginReference("service_fee_status", "charged"),
                PluginReference("service_fee_bps", "20"),
                PluginReference("service_fee_estimate", "0.000249103 SOL"),
            )
        val chose =
            ParameterChoice(mapOf(ParameterKey("input_amount") to ParameterValue.Amount(5uL)))
        val record =
            ProposalRecord(
                connectionId = CONNECTION,
                proposal = proposal(wireProposal(revision = 2)),
                execution =
                    ProposalExecution(
                        binding =
                            binding(proposal(wireProposal(revision = 2)), chose)
                                .copy(receipt = receipt),
                        startedAt = AT,
                        outcome = ProposalOutcome.Submitted(hash(3)),
                    ),
            )

        store.put(record)

        assertEquals(receipt, store.get(CONNECTION, PROPOSAL_A)?.execution?.binding?.receipt)
    }

    @Test
    fun aDismissalAndItsRevisionSurvive() {
        val record =
            ProposalRecord(
                connectionId = CONNECTION,
                proposal = proposal(wireProposal(revision = 3)),
                dismissed = ProposalDismissal(revision = 3, at = AT),
            )

        store.put(record)

        assertEquals(record, store.get(CONNECTION, PROPOSAL_A))
    }

    @Test
    fun aVersionTwoProposalMigratesWithoutLosingItsLocalDecision() {
        val record =
            ProposalRecord(
                connectionId = CONNECTION,
                proposal = proposal(wireProposal(revision = 3)),
                dismissed = ProposalDismissal(revision = 3, at = AT),
            )
        store.put(record)
        val file = File(dir, "$CONNECTION/$PROPOSAL_A.json")
        val old = JSONObject(file.readText())
        old.put("version", 2)
        old.getJSONObject("proposal").apply {
            remove("contractVersion")
            remove("capabilityVersion")
            remove("title")
            remove("ownerInputs")
        }
        file.writeText(old.toString())

        val migrated = checkNotNull(store.get(CONNECTION, PROPOSAL_A))
        assertEquals(SWAP, migrated.proposal.title)
        assertEquals(1, migrated.proposal.capabilityVersion)
        assertEquals(emptyList<Any>(), migrated.proposal.ownerInputs)
        assertEquals(record.dismissed, migrated.dismissed)
    }

    @Test
    fun aVersionThreeRowKeepsItsPendingItemsAndItsOneAttemptAcrossTheUpgrade() {
        // What SEE-145 must not break: a proposal reviewed and executed by a build that knew only
        // `swap` and `jupiter.swap` reads back naming the action, the provider and the schema —
        // through the compatibility table, never by splitting the plugin name on its dot — and it
        // still blocks a second attempt, because that is the whole point of writing it down.
        val record =
            ProposalRecord(
                connectionId = CONNECTION,
                proposal = proposal(),
                review = ProposalReview(1, choice(1_000_000uL), AT),
                execution =
                    ProposalExecution(
                        binding = binding(proposal(), choice(1_000_000uL)),
                        startedAt = AT,
                        outcome = ProposalOutcome.Declined,
                        settledAt = AT,
                    ),
            )
        store.put(record)
        val file = File(dir, "$CONNECTION/$PROPOSAL_A.json")
        val old = JSONObject(file.readText())
        old.put("version", 3)
        // A version-3 row said `operation` and `plugin`, and nothing else about who would execute.
        old.getJSONObject("proposal").apply {
            remove("action")
            remove("provider")
        }
        old.getJSONObject("execution").getJSONObject("binding").apply {
            remove("provider")
            remove("action")
            remove("schemaVersion")
            remove("instrument")
            remove("marketProvider")
        }
        file.writeText(old.toString())

        val migrated = checkNotNull(store.get(CONNECTION, PROPOSAL_A))

        assertEquals(SWAP_ACTION, migrated.proposal.action)
        assertEquals(JUPITER_PROVIDER, migrated.proposal.provider)
        assertEquals(JUPITER_PROVIDER, migrated.execution?.binding?.provider)
        assertEquals(SWAP_ACTION, migrated.execution?.binding?.action)
        assertEquals(1, migrated.execution?.binding?.schemaVersion)
        // The owner's own decision, and the one attempt this device made, both exactly as written.
        assertEquals(record.review, migrated.review)
        assertEquals(ProposalOutcome.Declined, migrated.execution?.outcome)
        assertEquals(
            record.execution?.binding?.contentHash,
            migrated.execution?.binding?.contentHash,
        )
    }

    @Test
    fun aRowThisBuildWritesIsStillReadableByOneThatOnlyKnowsTheOldNames() {
        // The other direction, which is what keeps a downgrade honest: a row written now carries
        // the action and the provider *and* the operation and plugin names an older build reads.
        store.put(ProposalRecord(connectionId = CONNECTION, proposal = proposal()))

        val row = JSONObject(File(dir, "$CONNECTION/$PROPOSAL_A.json").readText())
        val written = row.getJSONObject("proposal")

        // And carries a version number that build will accept. Its `decode` refuses anything
        // outside `1..3` before it reads a single field, so raising the number would have thrown
        // away every row this build had rewritten — the owner's review and the record of the one
        // attempt with it, leaving a refreshed proposal looking unexecuted and actionable again.
        // Writing keys a reader does not know is what "additive" means; renumbering is not.
        assertTrue(
            "a row written now must pass the version-3 gate, not just carry the old fields",
            row.getInt("version") in 1..3,
        )
        assertEquals(3, row.getInt("version"))
        assertEquals(SWAP_ACTION.value, written.getString("action"))
        assertEquals(JUPITER_PROVIDER.value, written.getString("provider"))
        assertEquals(SWAP, written.getString("operation"))
        assertEquals(JUPITER_SWAP.value, written.getString("plugin"))
    }

    @Test
    fun aRowThisBuildRewroteStillBlocksTheSecondAttemptAfterADowngrade() {
        // The consequence spelled out, because the number alone reads like bookkeeping. A proposal
        // reviewed and executed here, rewritten by this build, and then read back by the decoder
        // the previous build had: the attempt is still on record, so nothing becomes spendable a
        // second time (SEE-145).
        val record =
            ProposalRecord(
                connectionId = CONNECTION,
                proposal = proposal(),
                review = ProposalReview(1, choice(1_000_000uL), AT),
                execution =
                    ProposalExecution(
                        binding = binding(proposal(), choice(1_000_000uL)),
                        startedAt = AT,
                        outcome = ProposalOutcome.Declined,
                        settledAt = AT,
                    ),
            )
        store.put(record)

        val row = JSONObject(File(dir, "$CONNECTION/$PROPOSAL_A.json").readText())

        // Exactly what the previous build's `decode` did before it looked at anything else.
        assertTrue("the row would be dropped whole on a downgrade", row.getInt("version") in 1..3)
        // What it reads once past that gate: the operation and the plugin, never an action or a
        // provider it has no field for, and the owner's review and one attempt intact.
        val proposal = row.getJSONObject("proposal")
        assertEquals(SWAP, proposal.getString("operation"))
        assertEquals(JUPITER_SWAP.value, proposal.getString("plugin"))
        assertEquals(1L, row.getJSONObject("review").getLong("revision"))
        assertEquals(
            "Declined",
            row.getJSONObject("execution").getJSONObject("outcome").getString("outcome"),
        )
    }

    @Test
    fun aPublishersContradictionSurvivesARestart() {
        // Otherwise a restart would start acting on a proposal this phone had stopped acting on.
        val record =
            ProposalRecord(
                connectionId = CONNECTION,
                proposal = proposal(),
                refused = ProposalProblem.ChangedWithoutRevision,
            )

        store.put(record)

        assertEquals(
            ProposalProblem.ChangedWithoutRevision,
            store.get(CONNECTION, PROPOSAL_A)?.refused,
        )
    }

    @Test
    fun eachFeedsProposalsAreItsOwnAndGoWhenItDoes() {
        store.put(ProposalRecord(CONNECTION, proposal()))
        store.put(ProposalRecord(CONNECTION, proposal(wireProposal(proposalId = PROPOSAL_B))))
        store.put(ProposalRecord(OTHER, proposal()))

        assertEquals(2, store.listFor(CONNECTION).size)
        assertEquals(setOf(CONNECTION, OTHER), store.connectionIds())

        store.deleteConnection(CONNECTION)

        assertEquals(emptyList<ProposalRecord>(), store.listFor(CONNECTION))
        assertEquals(1, store.listFor(OTHER).size)
    }

    @Test
    fun aDamagedFileIsSkippedRatherThanReadAsSomethingElse() {
        store.put(ProposalRecord(CONNECTION, proposal()))
        store.put(ProposalRecord(CONNECTION, proposal(wireProposal(proposalId = PROPOSAL_B))))
        File(dir, "$CONNECTION/$PROPOSAL_A.json").writeText("{\"version\":1,")

        assertNull(store.get(CONNECTION, PROPOSAL_A))
        assertEquals(listOf(PROPOSAL_B), store.listFor(CONNECTION).map { it.key.proposalId })
    }

    @Test
    fun aFileWhoseChannelWasTamperedWithIsNotAProposal() {
        // Every rule the model holds itself to is applied again to what came off the disk: a
        // channel a publisher doesn't own could not have come from that publisher, whatever
        // directory the file is in.
        store.put(ProposalRecord(CONNECTION, proposal()))
        val file = File(dir, "$CONNECTION/$PROPOSAL_A.json")
        file.writeText(file.readText().replace("\"channel\"", "\"chanel\""))

        assertNull(store.get(CONNECTION, PROPOSAL_A))
    }

    @Test
    fun aRecordFiledUnderAnotherConnectionIsNotRead() {
        store.put(ProposalRecord(CONNECTION, proposal()))
        val moved = File(dir, "$OTHER/$PROPOSAL_A.json")
        moved.parentFile?.mkdirs()
        moved.writeText(File(dir, "$CONNECTION/$PROPOSAL_A.json").readText())

        assertTrue(store.get(CONNECTION, PROPOSAL_A) != null)
        assertNull(store.get(OTHER, PROPOSAL_A))
    }

    @Test
    fun anIdThatIsNotAnIdNamesNoFile() {
        assertNull(store.get(CONNECTION, "../../../etc/passwd"))
        assertNull(store.get("..", PROPOSAL_A))
    }

    private companion object {
        const val CONNECTION = "11111111-2222-4333-8444-555555555555"
        const val OTHER = "22222222-3333-4444-8555-666666666666"
        val AT: Instant = Instant.parse("2026-09-17T09:05:00Z")
    }
}
