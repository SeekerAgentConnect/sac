package io.github.brrenat.seekervault.operations

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.plugins.PROVIDER_CONTRACT
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginFact
import io.github.brrenat.seekervault.plugins.PluginFailure
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.plugins.TestExecutionProvider
import io.github.brrenat.seekervault.plugins.testAction
import io.github.brrenat.seekervault.proposal.v1.Proposal as WireProposal
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.GATEWAY
import io.github.brrenat.seekervault.servers.PluginRequirement
import io.github.brrenat.seekervault.servers.SERVER_A
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.SERVER_PROTOCOL
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * An answer a provider gave belongs to the review that asked for it, and to no other (SEE-145).
 *
 * [io.github.brrenat.seekervault.plugins.ExecutionProvider.resolve] is a read over the network: it
 * suspends, and while it is out the owner can move. A proposal ID is the publisher's, not this
 * phone's, so two feeds can hold the same one and mean two different documents; and the same
 * proposal reopened, or moved to a new revision, is a different thing to be reading. None of those
 * may be answered by a read that was started for another one — a closed market could hide a
 * preparation that is fine, and different deposit decimals would change what an entered amount
 * means.
 *
 * The provider here is the test-only one, for the one thing it can do that the bundled one cannot:
 * hold its read open until this test says otherwise. The wait is deliberately uncancellable, so
 * these cover the answer that was already in flight and not only the job that was stopped in time.
 */
@RunWith(AndroidJUnit4::class)
class ResolveScopeTest {
    @get:Rule val folder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @After fun resetMain() = Dispatchers.resetMain()

    private val clock: Instant = Instant.parse("2026-09-17T10:00:00Z")
    private val owner = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    private val examplePlugin = PluginId("example.swap")

    private fun provider() =
        TestExecutionProvider(
            id = "example",
            actions = listOf(testAction()),
            environments = setOf(PluginEnvironment.Production),
            legacyPlugins = setOf(examplePlugin),
            form = TestExecutionProvider.form(),
        )

    /** Two feeds on one phone, each with its own publisher, both requiring the same provider. */
    private fun phone(provider: TestExecutionProvider): Phone =
        Phone(File(folder.root, "phone"), listOf(provider)) { clock }
            .also {
                it.connection = it.connection.copy(server = ServerRecord.Known(manifest(SERVER_B)))
                it.connections.value =
                    listOf(
                        it.connection,
                        Connection(
                            id = OTHER_CONNECTION,
                            label = "Another trader",
                            serverUrl = GATEWAY,
                            serverId = SERVER_A,
                            deviceName = "",
                            pairedAt = Instant.EPOCH,
                            hasCredential = false,
                            mode = ConnectionMode.GatewayFeed,
                            server = ServerRecord.Known(manifest(SERVER_A)),
                        ),
                    )
            }

    private fun manifest(serverId: String) =
        ServerManifest(
            serverId = serverId,
            protocolVersion = SERVER_PROTOCOL,
            settingsRevision = 1,
            mode = ConnectionMode.GatewayFeed,
            reference = ServerReference.Feed(GATEWAY, channelFor(serverId)),
            required =
                listOf(PluginRequirement(examplePlugin, PROVIDER_CONTRACT..PROVIDER_CONTRACT)),
            environments = setOf(PluginEnvironment.Production),
            supportedNetworks = WalletNetwork.entries.toSet(),
        )

    /** The same proposal ID, published by two different servers: two documents, one name. */
    private fun published(serverId: String, revision: Long = 1): WireProposal =
        swapProposal(revision = revision)
            .toBuilder()
            .setServerId(serverId)
            .setChannel(channelFor(serverId))
            .setPluginId(examplePlugin.value)
            .build()

    /** Connects the wallet and reads both feeds, without opening anything. */
    private fun loaded(phone: Phone): OperationViewModel = runBlocking {
        phone.adapter.answerConnected(owner, chains = listOf(WalletNetwork.Mainnet.chain))
        phone.connectWallet(WalletNetwork.Mainnet)
        phone.feed.answersByChannel =
            mapOf(
                channelFor(SERVER_B) to listOf(published(SERVER_B)),
                channelFor(SERVER_A) to listOf(published(SERVER_A)),
            )
        val model = phone.viewModel()
        model.refresh(CONNECTION)
        model.refresh(OTHER_CONNECTION)
        model
    }

    @Test
    fun aReadForOneFeedCannotAnswerAnotherFeedsReviewOfTheSameProposalId() = runBlocking {
        val provider = provider()
        val phone = phone(provider)
        val model = loaded(phone)

        // The first feed's review asks, and its answer is held out on the network.
        val held = CompletableDeferred<Unit>()
        provider.details = listOf(FIRST)
        provider.releases = held
        model.open(CONNECTION, PROPOSAL)
        assertEquals(emptyList<PluginFact>(), checkNotNull(model.review.value).details)

        // The owner moves to the other feed's proposal, which carries the same ID, and that one
        // is answered straight away.
        provider.details = listOf(SECOND)
        provider.releases = null
        model.open(OTHER_CONNECTION, PROPOSAL)
        assertEquals(listOf(SECOND), checkNotNull(model.review.value).details)

        // And now the first feed's answer finally arrives.
        held.complete(Unit)
        scheduler.advanceUntilIdle()

        val review = checkNotNull(model.review.value)
        assertEquals(OTHER_CONNECTION, review.connectionId)
        assertEquals(listOf(SECOND), review.details)
        assertNull(review.failure)
    }

    @Test
    fun aReadThatFailedForOneFeedCannotFailAnotherFeedsReview() = runBlocking {
        val provider = provider()
        val phone = phone(provider)
        val model = loaded(phone)

        val held = CompletableDeferred<Unit>()
        provider.releases = held
        provider.refuses = PluginFailure("market_closed", R.string.app_name)
        model.open(CONNECTION, PROPOSAL)

        provider.releases = null
        provider.refuses = null
        provider.details = listOf(SECOND)
        model.open(OTHER_CONNECTION, PROPOSAL)

        held.complete(Unit)
        scheduler.advanceUntilIdle()

        // A failure is a fact about the read that failed, not about the review on screen.
        val review = checkNotNull(model.review.value)
        assertEquals(OTHER_CONNECTION, review.connectionId)
        assertNull(review.failure)
        assertEquals(listOf(SECOND), review.details)
    }

    @Test
    fun aReadForAClosedReviewCannotAnswerTheSameProposalOpenedAgain() = runBlocking {
        val provider = provider()
        val phone = phone(provider)
        val model = loaded(phone)

        val held = CompletableDeferred<Unit>()
        provider.details = listOf(FIRST)
        provider.releases = held
        model.open(CONNECTION, PROPOSAL)
        model.close()

        // Opened again: the same feed and the same proposal, and a different reading of it.
        provider.details = listOf(SECOND)
        provider.releases = null
        model.open(CONNECTION, PROPOSAL)
        assertEquals(listOf(SECOND), checkNotNull(model.review.value).details)

        held.complete(Unit)
        scheduler.advanceUntilIdle()

        // Nothing distinguishes the two but which review asked, which is the whole point.
        assertEquals(listOf(SECOND), checkNotNull(model.review.value).details)
    }

    @Test
    fun aReadForTheRevisionThatWasReplacedCannotAnswerTheOneThatReplacedIt() = runBlocking {
        val provider = provider()
        val phone = phone(provider)
        val model = loaded(phone)

        val held = CompletableDeferred<Unit>()
        provider.details = listOf(FIRST)
        provider.releases = held
        model.open(CONNECTION, PROPOSAL)

        // The publisher moved the terms while that read was out. The new revision is asked about
        // afresh, and answers for itself.
        provider.details = listOf(SECOND)
        provider.releases = null
        phone.feed.answersByChannel =
            phone.feed.answersByChannel +
                (channelFor(SERVER_B) to listOf(published(SERVER_B, revision = 2)))
        model.refresh(CONNECTION)
        assertEquals(2L, checkNotNull(model.review.value).record.proposal.revision)
        assertEquals(listOf(SECOND), checkNotNull(model.review.value).details)

        held.complete(Unit)
        scheduler.advanceUntilIdle()

        assertEquals(listOf(SECOND), checkNotNull(model.review.value).details)
    }

    private companion object {
        const val OTHER_CONNECTION = "c4e5d1a3-3b5f-4056-8a4f-7c2daa3e5f81"
        val FIRST = PluginFact(R.string.app_name, "the feed that asked first")
        val SECOND = PluginFact(R.string.app_name, "the review on screen")
    }
}
