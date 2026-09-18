package io.github.brrenat.seekervault.connections.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterValue
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
