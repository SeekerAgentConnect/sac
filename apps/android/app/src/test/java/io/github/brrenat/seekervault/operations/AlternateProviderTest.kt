package io.github.brrenat.seekervault.operations

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.plugins.ExecutionProviderId
import io.github.brrenat.seekervault.plugins.PROVIDER_CONTRACT
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.plugins.SWAP_ACTION
import io.github.brrenat.seekervault.plugins.TestExecutionProvider
import io.github.brrenat.seekervault.plugins.UnsupportedReason
import io.github.brrenat.seekervault.plugins.actions.SwapTermNames
import io.github.brrenat.seekervault.plugins.testAction
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.GATEWAY
import io.github.brrenat.seekervault.servers.PluginRequirement
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.SERVER_PROTOCOL
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * A second execution provider, registered beside Jupiter and driven by the same core (SEE-145).
 *
 * This is the acceptance criterion that the whole refactor exists for: another compatible Solana
 * provider can be added, resolved for a provider-neutral action, reviewed under the owner's rules,
 * bound, and put to the wallet — **without one line of core dispatch knowing it exists**. Nothing
 * below is a special case: the review runs through `OperationViewModel`, the gate through
 * `bindingProblem`, the record through `ProposalRepository`, and each of them is the same code the
 * Jupiter tests exercise.
 *
 * It is also the demonstration that it *cannot ship*. This provider lives in `src/test`, so it is
 * compiled into no APK — not the release one and not the debug one. There is no flag to get wrong
 * and nothing to strip: what ships is the list in `SeekerVaultApplication`, which
 * [io.github.brrenat.seekervault.BundledProvidersTest] holds to Jupiter alone.
 */
@RunWith(AndroidJUnit4::class)
class AlternateProviderTest {
    @get:Rule val folder = TemporaryFolder()

    private val clock: Instant = Instant.parse("2026-09-17T10:00:00Z")
    private val owner = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"

    /** The name a publisher writing for this provider would put in its documents. */
    private val examplePlugin = PluginId("example.swap")

    private fun alternate(
        inspection: io.github.brrenat.seekervault.plugins.ActionInspection =
            TestExecutionProvider.verified(wallet = OWNER_SIGNER)
    ) =
        TestExecutionProvider(
            id = "example",
            actions = listOf(testAction()),
            environments = setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox),
            legacyPlugins = setOf(examplePlugin),
            inspection = inspection,
            form = TestExecutionProvider.form(),
        )

    private fun phone(provider: TestExecutionProvider) =
        Phone(File(folder.root, "phone"), listOf(provider)) { clock }
            .also {
                // The publisher requires the *new* provider at the boundary contract this build
                // calls.
                // A name published before SEE-145 keeps its own published number; one that never
                // was
                // has nothing to be compatible with but the interface itself.
                it.connection =
                    it.connection.copy(
                        server =
                            ServerRecord.Known(
                                ServerManifest(
                                    serverId = SERVER_B,
                                    protocolVersion = SERVER_PROTOCOL,
                                    settingsRevision = 1,
                                    mode = ConnectionMode.GatewayFeed,
                                    reference = ServerReference.Feed(GATEWAY, channelFor(SERVER_B)),
                                    required =
                                        listOf(
                                            PluginRequirement(
                                                examplePlugin,
                                                PROVIDER_CONTRACT..PROVIDER_CONTRACT,
                                            )
                                        ),
                                    environments = setOf(PluginEnvironment.Production),
                                    supportedNetworks = WalletNetwork.entries.toSet(),
                                )
                            )
                    )
                it.connections.value = listOf(it.connection)
            }

    private fun opened(phone: Phone, plugin: String = examplePlugin.value) = runBlocking {
        phone.adapter.answerConnected(owner, chains = listOf(WalletNetwork.Mainnet.chain))
        phone.connectWallet(WalletNetwork.Mainnet)
        phone.feed.answers =
            listOf(swapProposal(extra = emptyMap()).toBuilder().setPluginId(plugin).build())
        val model = phone.viewModel()
        model.refresh(CONNECTION)
        model.open(CONNECTION, PROPOSAL)
        model
    }

    @Test
    fun anAlternateProviderIsRegisteredResolvedReviewedAndSignedByTheSameCore() = runBlocking {
        val provider = alternate()
        val phone = phone(provider)
        phone.adapter.sendWith(SIGNATURE)

        val model = opened(phone)

        // Core asked the provider what to collect, and asked it what it says now — the two reads
        // every provider gets, in the order every provider gets them.
        assertEquals(listOf("inputs", "resolve"), provider.calls)
        val review = checkNotNull(model.review.value)
        assertTrue(review.served)
        assertEquals(
            listOf("input_amount", "outcome", "slippage_bps"),
            review.form.fields.map { it.key.value },
        )
        // The owner's own choices, on their own phone, exactly as for the bundled provider.
        model.choose(ParameterKey("input_amount"), ParameterValue.Amount(2_000_000uL))
        model.choose(ParameterKey("outcome"), ParameterValue.Selected(ParameterKey("yes")))
        model.prepare()
        assertEquals(listOf("inputs", "resolve", "prepare", "inspect"), provider.calls)
        val prepared = checkNotNull(checkNotNull(model.review.value).prepared)

        model.approve(phone.wallet.walletFor(CONNECTION))

        // One wallet interaction, with exactly the bytes that were reviewed.
        assertEquals(1, phone.adapter.sendings.size)
        assertEquals(prepared.transaction, phone.adapter.sendings.single().first)
        val record = checkNotNull(phone.proposals.proposal(CONNECTION, PROPOSAL))
        val execution = checkNotNull(record.execution)
        // And the record binds the provider that actually prepared it, by name.
        assertEquals(ExecutionProviderId("example"), execution.binding.provider)
        assertEquals(SWAP_ACTION, execution.binding.action)
        assertEquals(ProposalOutcome.Submitted(SIGNATURE), execution.outcome)
        assertTrue(phone.proposals.standing(record) is ProposalStanding.Executed)
        // Jupiter was never reached: there is no routing, and a document names one venue.
        assertEquals(emptyList<String>(), phone.provider.asked)
        assertEquals(emptyList<String>(), phone.markets.asked)
    }

    @Test
    fun aDocumentNamingAProviderThisBuildDoesNotCarryIsRefusedBeforeAnythingIsPrepared() {
        val provider = alternate()
        val phone = phone(provider)

        val model = opened(phone, plugin = "somebody.else")
        val review = checkNotNull(model.review.value)

        assertEquals(false, review.served)
        model.choose(ParameterKey("input_amount"), ParameterValue.Amount(2_000_000uL))
        model.prepare()
        // Said as itself, before a wallet could be opened, and with nothing prepared.
        assertEquals(
            UnsupportedReason.NoProvider.code,
            checkNotNull(model.review.value).failure?.code,
        )
        assertNull(checkNotNull(model.review.value).prepared)
        assertEquals(emptyList<String>(), provider.calls)
        assertEquals(emptyList<ByteString>(), phone.adapter.sendings.map { it.first })
    }

    @Test
    fun aSandboxFeedOnTheAlternateProviderOpensNoWalletEither() = runBlocking {
        val provider = alternate()
        val phone = phone(provider)
        phone.keeps(
            PluginEnvironment.Sandbox,
            served = setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox),
        )

        val model = opened(phone)
        model.choose(ParameterKey("input_amount"), ParameterValue.Amount(2_000_000uL))
        model.prepare()
        model.approve(phone.wallet.walletFor(CONNECTION))

        // The same rehearsal the bundled provider gets, from the same place: core holds the wallet,
        // so core is what stops (SEE-97). The provider still did all of its own work.
        assertEquals(listOf("inputs", "resolve", "prepare", "inspect"), provider.calls)
        assertEquals(emptyList<ByteString>(), phone.adapter.sendings.map { it.first })
        assertEquals(emptyList<ByteString>(), phone.adapter.signings.map { it.first })
        val execution = checkNotNull(phone.proposals.proposal(CONNECTION, PROPOSAL)?.execution)
        assertEquals(ProposalOutcome.Simulated, execution.outcome)
        assertEquals(PluginEnvironment.Sandbox, execution.binding.environment)
        assertNotNull(phone.history.records.value.singleOrNull())
    }

    private companion object {
        const val OWNER_SIGNER = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        val SIGNATURE: ByteString = ByteString.copyFrom(ByteArray(64) { 11 })

        @Suppress("unused") val UNUSED = SwapTermNames.INPUT_MINT
    }
}
