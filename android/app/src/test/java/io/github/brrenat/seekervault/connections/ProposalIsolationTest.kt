package io.github.brrenat.seekervault.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityLog
import io.github.brrenat.seekervault.activity.storage.ActivityStore
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ProposalStore
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.plugins.PluginRegistry
import io.github.brrenat.seekervault.plugins.TestPlugin
import io.github.brrenat.seekervault.proposal.v1.Proposal as WireProposal
import io.github.brrenat.seekervault.proposals.AMOUNT
import io.github.brrenat.seekervault.proposals.PROPOSAL_A
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.WALLET
import io.github.brrenat.seekervault.proposals.binding
import io.github.brrenat.seekervault.proposals.choice
import io.github.brrenat.seekervault.proposals.hash
import io.github.brrenat.seekervault.proposals.wireProposal
import io.github.brrenat.seekervault.server.v1.ServerManifest as WireManifest
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.FeedReference
import io.github.brrenat.seekervault.servers.GATEWAY
import io.github.brrenat.seekervault.servers.PluginRequirement
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.SERVER_PROTOCOL
import io.github.brrenat.seekervault.servers.SWAP_PLUGIN
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.servers.feedManifest
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.io.File
import java.time.Instant
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The acceptance for a shared proposal (SEE-89): one document, two devices, and nothing about
 * either owner going anywhere.
 *
 * A broadcast is the part of Stage 7.1 with a real privacy claim behind it, and this is where it is
 * held to it. Two phones receive the same terms and choose differently; what one of them does is
 * invisible to the other, because there is nothing on the publishing server to be per-subscriber
 * and nothing on this side that is ever sent; and a feed is excluded from every call the phone
 * makes to a server it paired with.
 */
@RunWith(AndroidJUnit4::class)
class ProposalIsolationTest {
    @get:Rule val folder = TemporaryFolder()

    private var clock = Instant.parse("2026-09-17T09:05:00Z")
    private val wallets =
        listOf(
            SelectedWallet(WALLET, WalletNetwork.Mainnet, selectedAt = clock),
            SelectedWallet(OTHER_WALLET, WalletNetwork.Mainnet, selectedAt = clock),
        )

    /** One phone: its own store, its own history, and its own view of the same feed. */
    private inner class Device(name: String, val connectionId: String) {
        val feed = FakeProposalFeed()
        val store = ProposalStore(File(folder.root, "$name/proposals"))
        val history = ActivityLog(ActivityStore(File(folder.root, "$name/activity")))
        val connection =
            Connection(
                id = connectionId,
                label = "Publisher",
                serverUrl = GATEWAY,
                serverId = SERVER_B,
                deviceName = "",
                pairedAt = clock,
                hasCredential = false,
                mode = ConnectionMode.GatewayFeed,
                server = ServerRecord.Known(manifest),
            )
        val proposals =
            ProposalRepository(
                store = store,
                connections = { listOf(connection) },
                plugins = PluginRegistry.of(TestPlugin(id = SWAP_PLUGIN)),
                feed = feed,
                history = history,
                now = { clock },
                io = Dispatchers.Unconfined,
            )
    }

    private class FakeProposalFeed : ProposalFeed {
        var answers: List<WireProposal> = emptyList()
        val asked = mutableListOf<FeedReference>()

        override suspend fun snapshot(
            reference: FeedReference,
            knownSequence: Long,
        ): FeedSnapshot {
            asked += reference
            return FeedSnapshot.Read(1L, answers)
        }
    }

    private class FakeFeedGateway(private val manifest: WireManifest) : FeedGateway {
        val resolved = mutableListOf<FeedReference>()

        override suspend fun resolve(reference: FeedReference, knownRevision: Long): FeedManifest {
            resolved += reference
            return FeedManifest.Held(manifest)
        }
    }

    @Test
    fun twoDevicesReceiveTheSameTermsAndChooseDifferently() = runBlocking {
        val published =
            wireProposal(revision = 2, values = listOf("published_price" to "139420000"))
        val first = Device("first", FEED_A)
        val second = Device("second", FEED_B)
        first.feed.answers = listOf(published)
        second.feed.answers = listOf(published)

        first.proposals.refresh(FEED_A)
        second.proposals.refresh(FEED_B)

        // The publisher's half is identical on both, down to the revision and the terms.
        assertEquals(
            first.proposals.proposalsFor(FEED_A).single().proposal,
            second.proposals.proposalsFor(FEED_B).single().proposal,
        )

        first.proposals.review(FEED_A, PROPOSAL_A, choice(1_000_000u))
        second.proposals.review(FEED_B, PROPOSAL_A, choice(7_500_000u))

        // And each owner's own answer is theirs.
        assertEquals(
            ParameterValue.Amount(1_000_000u),
            first.proposals.proposal(FEED_A, PROPOSAL_A)?.review?.choice?.get(AMOUNT),
        )
        assertEquals(
            ParameterValue.Amount(7_500_000u),
            second.proposals.proposal(FEED_B, PROPOSAL_A)?.review?.choice?.get(AMOUNT),
        )
    }

    @Test
    fun whatOneOwnerDoesIsInvisibleToTheOther() = runBlocking {
        val published = wireProposal(revision = 2)
        val first = Device("first", FEED_A)
        val second = Device("second", FEED_B)
        first.proposals.apply(FEED_A, published)
        second.proposals.apply(FEED_B, published)

        val chose = choice(1_000_000u)
        first.proposals.review(FEED_A, PROPOSAL_A, chose)
        val bound =
            binding(
                checkNotNull(first.proposals.proposal(FEED_A, PROPOSAL_A)).proposal,
                chose,
                wallet = WALLET,
            )
        first.proposals.beginExecution(FEED_A, PROPOSAL_A, bound, wallets[0])
        first.proposals.recordOutcome(FEED_A, PROPOSAL_A, ProposalOutcome.Submitted(hash(7)))
        second.proposals.dismiss(FEED_B, PROPOSAL_A)

        // One owner executed and the other hid it. Neither is "completed" for anyone else: there
        // is no per-subscriber state on the publishing server for one of them to have changed.
        assertEquals(
            ProposalStanding.Executed(ProposalOutcome.Submitted(hash(7))),
            first.proposals.standing(checkNotNull(first.proposals.proposal(FEED_A, PROPOSAL_A))),
        )
        assertEquals(
            ProposalStanding.Dismissed(clock),
            second.proposals.standing(checkNotNull(second.proposals.proposal(FEED_B, PROPOSAL_A))),
        )
        assertNull(second.proposals.proposal(FEED_B, PROPOSAL_A)?.execution)
        assertNull(first.proposals.proposal(FEED_A, PROPOSAL_A)?.dismissed)
        // Each phone's history is its own: the other device did nothing this one can see.
        assertEquals(ActivityKind.Operation, first.history.records.value.single().kind)
        assertEquals(0, second.history.records.value.size)
    }

    @Test
    fun nothingAboutTheOwnerGoesOutToTheGateway() = runBlocking {
        val device = Device("first", FEED_A)
        device.feed.answers = listOf(wireProposal())
        device.proposals.refresh(FEED_A)
        val chose = choice(1_000_000u)
        device.proposals.review(FEED_A, PROPOSAL_A, chose)
        val bound =
            binding(checkNotNull(device.proposals.proposal(FEED_A, PROPOSAL_A)).proposal, chose)
        device.proposals.beginExecution(FEED_A, PROPOSAL_A, bound, wallets[0])
        device.proposals.recordOutcome(FEED_A, PROPOSAL_A, ProposalOutcome.Submitted(hash(7)))

        // Everything that ever went out, and it is one subscription: which gateway, and which
        // publisher's channel. Reading a feed again asks the same question again.
        device.proposals.refresh(FEED_A)
        assertEquals(
            listOf(FeedReference(GATEWAY, SERVER_B), FeedReference(GATEWAY, SERVER_B)),
            device.feed.asked,
        )
        val outbound = device.feed.asked.toString()
        assertFalse(outbound.contains(WALLET))
        assertFalse(outbound.contains("1000000"))
        assertFalse(outbound.contains(PROPOSAL_A))
        // The publisher itself has no address on this phone, so there is nothing to contact.
        assertFalse(outbound.contains("publisher"))
    }

    @Test
    fun aFeedIsExcludedFromEveryCallThePhoneMakesToAServer() = runBlocking {
        val gateway = FakeConnectionGateway()
        val feeds = FakeFeedGateway(feedManifest(serverId = SERVER_B, gateway = GATEWAY))
        val store = ProposalStore(File(folder.root, "files/proposals"))
        val key: () -> SecretKey = softwareKey().let { k -> { k } }
        val repository =
            ConnectionRepository(
                store = ConnectionStore(File(folder.root, "files/connections")),
                vault = CredentialVault(File(folder.root, "no_backup/credentials")) { key() },
                results = ResultStore(File(folder.root, "files/results")),
                gateway = gateway,
                proposals = store,
                feeds = feeds,
                deviceName = "Seeker",
                now = { clock },
                io = Dispatchers.Unconfined,
            )
        val added = repository.addFeed(FeedReference(GATEWAY, SERVER_B)) as FeedOutcome.Added
        val id = added.connection.id

        repository.refresh(id)
        repository.publishWallet(id, null)

        // The mode is the one gate: a feed is not a server this phone calls, so refresh reads
        // nothing from it, no binding is published to it, and generic synchronization cannot reach
        // it either (SEE-88's `usable`).
        assertFalse(added.connection.usable)
        assertEquals(emptyList<Pair<String, String>>(), gateway.sent)
        assertEquals(emptyList<Pair<String, Any?>>(), gateway.published)
        assertEquals(emptyList<Pair<String, Any?>>(), gateway.submits)
        assertNull(repository.access(id))
        // And the gateway that holds the manifest was asked for the feed, and only for it.
        assertEquals(listOf(FeedReference(GATEWAY, SERVER_B)), feeds.resolved)
    }

    @Test
    fun removingAFeedTakesItsProposalsAndLeavesWhatThisPhoneDid() = runBlocking {
        val gateway = FakeConnectionGateway()
        val feeds = FakeFeedGateway(feedManifest(serverId = SERVER_B, gateway = GATEWAY))
        val store = ProposalStore(File(folder.root, "files/proposals"))
        val history = ActivityLog(ActivityStore(File(folder.root, "files/activity")))
        val key: () -> SecretKey = softwareKey().let { k -> { k } }
        var connections = emptyList<Connection>()
        val repository =
            ConnectionRepository(
                store = ConnectionStore(File(folder.root, "files/connections")),
                vault = CredentialVault(File(folder.root, "no_backup/credentials")) { key() },
                results = ResultStore(File(folder.root, "files/results")),
                gateway = gateway,
                history = history,
                proposals = store,
                feeds = feeds,
                deviceName = "Seeker",
                now = { clock },
                io = Dispatchers.Unconfined,
            )
        val id =
            (repository.addFeed(FeedReference(GATEWAY, SERVER_B)) as FeedOutcome.Added)
                .connection
                .id
        connections = repository.connections.value
        val proposals =
            ProposalRepository(
                store = store,
                connections = { connections },
                plugins = PluginRegistry.of(TestPlugin(id = SWAP_PLUGIN)),
                history = history,
                now = { clock },
                io = Dispatchers.Unconfined,
            )
        proposals.apply(id, wireProposal())
        val chose = choice(1_000_000u)
        proposals.review(id, PROPOSAL_A, chose)
        proposals.beginExecution(
            id,
            PROPOSAL_A,
            binding(checkNotNull(proposals.proposal(id, PROPOSAL_A)).proposal, chose),
            wallets[0],
        )
        proposals.recordOutcome(id, PROPOSAL_A, ProposalOutcome.Submitted(hash(7)))
        assertTrue(store.listFor(id).isNotEmpty())

        repository.remove(id)

        // The documented retention: a feed's proposals go with the feed, and the owner's record of
        // what this phone did outlives both.
        assertEquals(emptyList<ProposalRecord>(), store.listFor(id))
        assertEquals(emptySet<String>(), store.connectionIds())
        history.load()
        assertEquals(ActivityKind.Operation, history.records.value.single().kind)
    }

    private companion object {
        const val FEED_A = "11111111-2222-4333-8444-555555555555"
        const val FEED_B = "22222222-3333-4444-8555-666666666666"
        const val OTHER_WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"

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
    }
}
