package io.github.brrenat.seekervault.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityLog
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.storage.ActivityStore
import io.github.brrenat.seekervault.connections.storage.ProposalStore
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.plugins.PluginRegistry
import io.github.brrenat.seekervault.plugins.TestPlugin
import io.github.brrenat.seekervault.proposal.v1.Proposal as WireProposal
import io.github.brrenat.seekervault.proposal.v1.ProposalStatus as WireStatus
import io.github.brrenat.seekervault.proposals.AMOUNT
import io.github.brrenat.seekervault.proposals.BindingProblem
import io.github.brrenat.seekervault.proposals.PROPOSAL_A
import io.github.brrenat.seekervault.proposals.PROPOSAL_B
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalProblem
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.WALLET
import io.github.brrenat.seekervault.proposals.binding
import io.github.brrenat.seekervault.proposals.choice
import io.github.brrenat.seekervault.proposals.hash
import io.github.brrenat.seekervault.proposals.wireProposal
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.FeedReference
import io.github.brrenat.seekervault.servers.GATEWAY
import io.github.brrenat.seekervault.servers.PluginRequirement
import io.github.brrenat.seekervault.servers.SERVER_A
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.SERVER_PROTOCOL
import io.github.brrenat.seekervault.servers.SWAP_PLUGIN
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.ServerSupport
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * What the phone does with a publisher's proposals (SEE-89).
 *
 * The cases here are the ones a broadcast makes possible and a private request never did: the same
 * document arriving twice, arriving older than what is held, arriving with the terms changed under
 * an unchanged revision, and arriving after the owner has already dismissed or executed it. None of
 * them may re-open a dismissal, apply an old decision to new terms, or put a second operation to
 * the wallet.
 */
@RunWith(AndroidJUnit4::class)
class ProposalRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private var clock = Instant.parse("2026-09-17T09:05:00Z")
    private val feed = FakeProposalFeed()
    private val store by lazy { ProposalStore(File(folder.root, "files/proposals")) }
    private val history by lazy { ActivityLog(ActivityStore(File(folder.root, "files/activity"))) }
    private var connections = listOf(publisher, sidecar)
    private val wallet =
        SelectedWallet(address = WALLET, network = WalletNetwork.Mainnet, selectedAt = clock)

    private fun repository(
        withFeed: Boolean = true,
        plugins: PluginRegistry = PluginRegistry.of(TestPlugin(id = SWAP_PLUGIN)),
        store: ProposalStore = this.store,
    ) =
        ProposalRepository(
            store = store,
            connections = { connections },
            plugins = plugins,
            environment = PluginEnvironment.Production,
            feed = if (withFeed) feed else null,
            history = history,
            now = { clock },
            io = Dispatchers.Unconfined,
        )

    private val repository by lazy { repository() }

    /** A gateway that answers with whatever a test published, and records what it was asked. */
    private class FakeProposalFeed : ProposalFeed {
        var answers: List<WireProposal> = emptyList()
        var failure: GatewayException.Kind? = null
        val asked = mutableListOf<FeedReference>()

        override suspend fun proposals(reference: FeedReference): List<WireProposal> {
            asked += reference
            failure?.let { throw GatewayException(it, "fake $it") }
            return answers
        }
    }

    @Test
    fun readsAFeedThroughTheGatewayAndNeverAsksThePublisher() = runBlocking {
        feed.answers = listOf(wireProposal(), wireProposal(proposalId = PROPOSAL_B))

        assertEquals(FeedRefresh.Read(2, emptyList()), repository.refresh(FEED))

        // The gateway was asked for this publisher's channel, and that is the whole of what went
        // out: the publisher's own address is not something this phone has.
        assertEquals(listOf(FeedReference(GATEWAY, SERVER_B)), feed.asked)
        assertEquals(
            listOf(PROPOSAL_A, PROPOSAL_B).sorted(),
            repository.proposalsFor(FEED).map { it.key.proposalId }.sorted(),
        )
        assertEquals(ProposalStanding.Open, repository.standing(repository.proposalsFor(FEED)[0]))
    }

    @Test
    fun withoutAGatewayItSaysSoRatherThanReadingNothing() = runBlocking {
        assertEquals(FeedRefresh.NoFeed, repository(withFeed = false).refresh(FEED))
        assertEquals(emptyList<FeedReference>(), feed.asked)
    }

    @Test
    fun aDirectConnectionIsNeverReadAsAFeed() = runBlocking {
        assertEquals(FeedRefresh.NotAFeed, repository.refresh(DIRECT))
        assertEquals(
            FeedRefresh.NotAFeed,
            repository.refresh("11111111-0000-4000-8000-000000000000"),
        )
        assertEquals(emptyList<FeedReference>(), feed.asked)
    }

    @Test
    fun aGatewayThatCouldNotBeReachedLeavesEverythingAsItWas() = runBlocking {
        repository.apply(FEED, wireProposal(revision = 2))
        feed.failure = GatewayException.Kind.Unreachable

        assertEquals(
            FeedRefresh.Failed(CheckOutcome.Unreachable),
            repository.refresh(FEED),
        )
        assertEquals(2L, held().proposal.revision)
    }

    @Test
    fun theSameDocumentArrivingAgainChangesNothing() = runBlocking {
        val document = wireProposal(revision = 3)
        assertTrue(repository.apply(FEED, document) is ProposalApplied.Stored)
        repository.dismiss(FEED, PROPOSAL_A)

        // A replayed event, a duplicate push and a reloaded snapshot are the same document
        // arriving again, and none of them may touch what the owner decided.
        assertTrue(repository.apply(FEED, document) is ProposalApplied.Unchanged)

        assertEquals(3L, held().dismissed?.revision)
        assertEquals(ProposalStanding.Dismissed(clock), repository.standing(held()))
    }

    @Test
    fun aRevisionBelowTheOneHeldIsRefusedAndTheHeldTermsStand() = runBlocking {
        repository.apply(FEED, wireProposal(revision = 5, values = listOf("price" to "5")))

        assertEquals(
            ProposalApplied.Refused(ProposalProblem.StaleRevision),
            repository.apply(FEED, wireProposal(revision = 4, values = listOf("price" to "4"))),
        )
        assertEquals("5", held().proposal.value("price"))
    }

    @Test
    fun aHigherRevisionMovesTheTermsAndLeavesTheOwnersReviewBehind() = runBlocking {
        repository.apply(FEED, wireProposal(revision = 1, values = listOf("price" to "1")))
        val chose = choice(1_000_000u)
        repository.review(FEED, PROPOSAL_A, chose)
        assertEquals(1L, held().review?.revision)

        repository.apply(FEED, wireProposal(revision = 2, values = listOf("price" to "2")))

        // The publisher's half moved; this device's half stayed exactly where it was, which is what
        // makes the older review detectably stale rather than silently applied to the new terms.
        assertEquals("2", held().proposal.value("price"))
        assertEquals(1L, held().review?.revision)
        assertEquals(
            ExecutionOutcome.Refused(BindingProblem.ProposalChanged),
            repository.beginExecution(
                FEED,
                PROPOSAL_A,
                binding(held().proposal, chose),
                wallet,
            ),
        )
    }

    @Test
    fun termsThatChangedWithoutTheRevisionAreActedOnNoFurther() = runBlocking {
        repository.apply(FEED, wireProposal(revision = 2, values = listOf("price" to "1")))
        repository.review(FEED, PROPOSAL_A, choice(1_000_000u))

        assertEquals(
            ProposalApplied.Refused(ProposalProblem.ChangedWithoutRevision),
            repository.apply(FEED, wireProposal(revision = 2, values = listOf("price" to "2"))),
        )

        // The terms the phone validated are kept — this device's own record must not be thrown
        // away over the publisher's mistake — and nothing further is executed from either version.
        assertEquals("1", held().proposal.value("price"))
        assertEquals(
            ProposalStanding.Refused(ProposalProblem.ChangedWithoutRevision),
            repository.standing(held()),
        )
        assertEquals(
            ExecutionOutcome.Refused(BindingProblem.ProposalRefused),
            repository.beginExecution(
                FEED,
                PROPOSAL_A,
                binding(held().proposal, choice(1_000_000u)),
                wallet,
            ),
        )

        // And a higher revision is the publisher saying something new, judged on its own.
        repository.apply(FEED, wireProposal(revision = 3, values = listOf("price" to "3")))
        assertNull(held().refused)
        assertEquals(ProposalStanding.Open, repository.standing(held()))
    }

    @Test
    fun aProposalFromAnotherPublisherIsNotThisFeeds() = runBlocking {
        assertEquals(
            ProposalApplied.Refused(ProposalProblem.OtherServer),
            repository.apply(
                FEED,
                wireProposal(serverId = SERVER_A, channel = channelFor(SERVER_A)),
            ),
        )
        assertEquals(emptyList<ProposalRecord>(), repository.proposalsFor(FEED))
    }

    @Test
    fun aDismissalIsFinalForTheProposalAndNotJustForItsRevision() = runBlocking {
        repository.apply(FEED, wireProposal(revision = 1))
        repository.dismiss(FEED, PROPOSAL_A)
        val dismissedAt = clock
        clock = clock.plusSeconds(60)

        // A publisher that could re-open a dismissal by changing a number would have a way to keep
        // putting the same proposal in front of someone who said no.
        repository.apply(FEED, wireProposal(revision = 9))
        repository.dismiss(FEED, PROPOSAL_A)

        assertEquals(1L, held().dismissed?.revision)
        assertEquals(dismissedAt, held().dismissed?.at)
        assertEquals(ProposalStanding.Dismissed(dismissedAt), repository.standing(held()))
    }

    @Test
    fun oneExecutionPerProposalHoweverManyTapsThereAre() = runBlocking {
        repository.apply(FEED, wireProposal())
        val chose = choice(1_000_000u)
        repository.review(FEED, PROPOSAL_A, chose)
        val bound = binding(held().proposal, chose)

        val first = repository.beginExecution(FEED, PROPOSAL_A, bound, wallet)
        val second = repository.beginExecution(FEED, PROPOSAL_A, bound, wallet)

        assertTrue(first is ExecutionOutcome.Begun)
        assertEquals(ExecutionOutcome.Refused(BindingProblem.AlreadyExecuted), second)
        assertEquals(bound, held().execution?.binding)
        // The record was written before any wallet could be asked, which is what the second tap
        // ran into.
        assertEquals(ProposalOutcome.Pending, held().execution?.outcome)
    }

    @Test
    fun aReviewAfterAnExecutionCannotContradictWhatWasBound() = runBlocking {
        repository.apply(FEED, wireProposal())
        val chose = choice(1_000_000u)
        repository.review(FEED, PROPOSAL_A, chose)
        repository.beginExecution(FEED, PROPOSAL_A, binding(held().proposal, chose), wallet)

        repository.review(FEED, PROPOSAL_A, choice(9_000_000u))

        assertEquals(chose, held().review?.choice)
    }

    @Test
    fun theWalletsAnswerIsRecordedOnceAndTheFirstWordStands() = runBlocking {
        repository.apply(FEED, wireProposal())
        val chose = choice(1_000_000u)
        repository.review(FEED, PROPOSAL_A, chose)
        repository.beginExecution(FEED, PROPOSAL_A, binding(held().proposal, chose), wallet)

        repository.recordOutcome(FEED, PROPOSAL_A, ProposalOutcome.Submitted(hash(9)))
        repository.recordOutcome(FEED, PROPOSAL_A, ProposalOutcome.Failed("a late second answer"))

        assertEquals(ProposalOutcome.Submitted(hash(9)), held().execution?.outcome)
        assertEquals(ActivityOutcome.Sent, history.records.value.single().outcome)
    }

    @Test
    fun anOperationTheAppClosedOnIsUnresolvedAndNeverAFailure() = runBlocking {
        repository.apply(FEED, wireProposal())
        val chose = choice(1_000_000u)
        repository.review(FEED, PROPOSAL_A, chose)
        repository.beginExecution(FEED, PROPOSAL_A, binding(held().proposal, chose), wallet)

        // A new process over the same disk: the wallet was never asked again, and the outcome
        // nobody knows is not reported as one somebody does.
        val restarted = repository()
        restarted.load()

        val outcome = restarted.proposal(FEED, PROPOSAL_A)?.execution?.outcome
        assertTrue("$outcome", outcome is ProposalOutcome.Unresolved)
        assertEquals(ActivityOutcome.Unknown, history.records.value.single().outcome)
        assertEquals(
            ExecutionOutcome.Refused(BindingProblem.AlreadyExecuted),
            restarted.beginExecution(FEED, PROPOSAL_A, binding(held().proposal, chose), wallet),
        )
    }

    @Test
    fun anExecutionIsWrittenToTheOwnersOwnHistory() = runBlocking {
        repository.apply(FEED, wireProposal(revision = 6))
        val chose = choice(1_000_000u)
        repository.review(FEED, PROPOSAL_A, chose)
        repository.beginExecution(FEED, PROPOSAL_A, binding(held().proposal, chose), wallet)
        repository.recordOutcome(FEED, PROPOSAL_A, ProposalOutcome.Submitted(hash(4)))

        val record = history.records.value.single()

        assertEquals(ActivityKind.Operation, record.kind)
        assertEquals(PROPOSAL_A, record.requestId)
        assertEquals("Publisher", record.source)
        assertEquals("gateway.example.com", record.serverHost)
        assertTrue(record.signatureIsTransaction)
        val operation = checkNotNull(record.operation)
        assertEquals("swap", operation.operation)
        assertEquals(SWAP_PLUGIN, operation.plugin)
        assertEquals(6L, operation.revision)
        assertEquals(WALLET, operation.wallet)
        assertEquals(listOf(AMOUNT.value to "1000000"), operation.values.map { it.key to it.text })
    }

    @Test
    fun aDismissalOrAReviewIsNotSomethingThisPhoneDid() = runBlocking {
        repository.apply(FEED, wireProposal())
        repository.review(FEED, PROPOSAL_A, choice(1_000_000u))
        repository.dismiss(FEED, PROPOSAL_A)

        // The owner's history is of what this phone did. Looking and hiding are neither.
        assertEquals(emptyList<ActivityKind>(), history.records.value.map { it.kind })
    }

    @Test
    fun nothingIsExecutedForAServerThisBuildDoesNotSupport() = runBlocking {
        val bare = repository(plugins = PluginRegistry.of())
        bare.apply(FEED, wireProposal())
        val chose = choice(1_000_000u)
        bare.review(FEED, PROPOSAL_A, chose)

        assertEquals(
            ServerSupport.PluginMissing(listOf(PluginId(SWAP_PLUGIN))),
            (bare.standing(held()) as ProposalStanding.Unsupported).support,
        )
        assertEquals(
            ExecutionOutcome.Refused(BindingProblem.ServerUnsupported),
            bare.beginExecution(FEED, PROPOSAL_A, binding(held().proposal, chose), wallet),
        )
        assertEquals(emptyList<ActivityKind>(), history.records.value.map { it.kind })
    }

    @Test
    fun aWithdrawnProposalIsStillReadAndNoLongerActedOn() = runBlocking {
        repository.apply(FEED, wireProposal(revision = 1))
        val chose = choice(1_000_000u)
        repository.review(FEED, PROPOSAL_A, chose)
        val terms = held().proposal

        repository.apply(
            FEED,
            wireProposal(revision = 2, status = WireStatus.PROPOSAL_STATUS_CANCELLED),
        )

        assertEquals(ProposalStanding.Cancelled, repository.standing(held()))
        assertEquals(
            ExecutionOutcome.Refused(BindingProblem.ProposalCancelled),
            repository.beginExecution(FEED, PROPOSAL_A, binding(terms, chose), wallet),
        )
    }

    @Test
    fun aProposalWhoseFeedIsGoneIsForgottenAndItsActivityIsNot() = runBlocking {
        repository.apply(FEED, wireProposal())
        val chose = choice(1_000_000u)
        repository.review(FEED, PROPOSAL_A, chose)
        repository.beginExecution(FEED, PROPOSAL_A, binding(held().proposal, chose), wallet)
        repository.recordOutcome(FEED, PROPOSAL_A, ProposalOutcome.Submitted(hash(4)))

        // The owner removed the feed while the app was closed. The documented retention: the
        // proposals go with the feed, and what this phone did outlives both.
        connections = listOf(sidecar)
        val restarted = repository()
        restarted.load()

        assertEquals(emptyList<ProposalRecord>(), restarted.proposals.value)
        assertEquals(emptySet<String>(), store.connectionIds())
        history.load()
        assertEquals(ActivityKind.Operation, history.records.value.single().kind)
    }

    @Test
    fun oneFeedsProposalsAreNeverAnothers() = runBlocking {
        val other =
            publisher.copy(
                id = OTHER_FEED,
                serverId = SERVER_A,
                server =
                    ServerRecord.Known(
                        manifest.copy(
                            serverId = SERVER_A,
                            reference = ServerReference.Feed(GATEWAY, channelFor(SERVER_A)),
                        )
                    ),
            )
        connections = listOf(publisher, other, sidecar)
        repository.apply(FEED, wireProposal())
        repository.apply(
            OTHER_FEED,
            wireProposal(serverId = SERVER_A, channel = channelFor(SERVER_A)),
        )

        assertEquals(1, repository.proposalsFor(FEED).size)
        assertEquals(1, repository.proposalsFor(OTHER_FEED).size)
        assertEquals(SERVER_B, repository.proposalsFor(FEED).single().key.serverId)
        assertEquals(SERVER_A, repository.proposalsFor(OTHER_FEED).single().key.serverId)
        // Dismissing one says nothing about the other, even at the same proposal ID.
        repository.dismiss(FEED, PROPOSAL_A)
        assertNull(repository.proposal(OTHER_FEED, PROPOSAL_A)?.dismissed)
    }

    private fun held(): ProposalRecord = checkNotNull(store.get(FEED, PROPOSAL_A))

    private companion object {
        const val FEED = "11111111-2222-4333-8444-555555555555"
        const val OTHER_FEED = "22222222-3333-4444-8555-666666666666"
        const val DIRECT = "33333333-4444-4555-8666-777777777777"
        val AT: Instant = Instant.parse("2026-09-17T08:00:00Z")

        val manifest =
            ServerManifest(
                serverId = SERVER_B,
                protocolVersion = SERVER_PROTOCOL,
                settingsRevision = 1,
                mode = ConnectionMode.GatewayFeed,
                reference = ServerReference.Feed(GATEWAY, channelFor(SERVER_B)),
                required = listOf(PluginRequirement(PluginId(SWAP_PLUGIN), 1..1)),
                environments = setOf(PluginEnvironment.Production),
            )

        val publisher =
            Connection(
                id = FEED,
                label = "Publisher",
                serverUrl = GATEWAY,
                serverId = SERVER_B,
                deviceName = "",
                pairedAt = AT,
                hasCredential = false,
                mode = ConnectionMode.GatewayFeed,
                server = ServerRecord.Known(manifest),
            )

        val sidecar =
            Connection(
                id = DIRECT,
                label = "Home Mac",
                serverUrl = "https://mac.example.com",
                serverId = SERVER_A,
                deviceName = "Seeker",
                pairedAt = AT,
            )
    }
}
