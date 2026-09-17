package io.github.brrenat.seekervault.plugins

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.policy.Allowlist
import io.github.brrenat.seekervault.policy.AssetLimits
import io.github.brrenat.seekervault.policy.ConnectionPolicy
import io.github.brrenat.seekervault.policy.PolicyAction
import io.github.brrenat.seekervault.policy.PolicyAssessment
import io.github.brrenat.seekervault.policy.PolicyAsset
import io.github.brrenat.seekervault.policy.PolicyCheck
import io.github.brrenat.seekervault.policy.PolicyCheckStatus
import io.github.brrenat.seekervault.policy.PolicyReason
import io.github.brrenat.seekervault.policy.evaluate
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Asset
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.ackAction
import io.github.brrenat.seekervault.request.v1.action
import io.github.brrenat.seekervault.request.v1.actionRequest
import io.github.brrenat.seekervault.request.v1.asset
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.request.v1.signMessageAction
import io.github.brrenat.seekervault.request.v1.swapAction
import io.github.brrenat.seekervault.request.v1.transferAction
import io.github.brrenat.seekervault.transactions.Verdict
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the owner's rules are applied to when a plugin would carry out the action (SEE-86).
 *
 * The rule the whole file is about: nothing a plugin didn't read is established. An operation this
 * build serves with nothing, one whose bytes haven't been prepared, and one whose bytes couldn't be
 * read are all a gap in the review — and a gap is never an allowance
 * (docs/policy.md#what-is-evaluated).
 */
class PluginFactsTest {
    private val production = PluginEnvironment.Production
    private val mainnet = Network.NETWORK_MAINNET

    @Test
    fun theAppStillCarriesOutItsOwnActionsAndAsksNoPluginAboutThem() {
        // Ack, message and transfer are core's, exactly as they were before this stage. Nothing
        // routes them through a registry, so no plugin can change what they mean.
        assertEquals(ActionOwner.Core, actionOwner(ack()))
        assertEquals(ActionOwner.Core, actionOwner(message()))
        assertEquals(ActionOwner.Core, actionOwner(transfer()))
    }

    @Test
    fun anOperationIsNamedAtTheProtocolsOwnLevelAndNeverAfterAProvider() {
        // Core says "swap". Which provider makes a swap work is the plugin's business, and this is
        // what keeps `connections/`, `sync/` and `live/` free of a provider's name.
        assertEquals(ActionOwner.Plugin(OperationId("swap")), actionOwner(swap()))
        assertEquals("swap", SWAP_OPERATION.value)
    }

    @Test
    fun anActionKindThisBuildHasNoNameForIsNeitherCoresNorAPluginsToClaim() {
        assertEquals(ActionOwner.Unnamed, actionOwner(actionRequest { ref = ref() }))
    }

    @Test
    fun anOperationNoPluginServesEstablishesNothingAtAll() {
        val facts =
            pluginFacts(
                CONNECTION,
                swap(),
                mainnet,
                PluginRegistry.of().resolve(SWAP_OPERATION, production),
            )

        assertEquals(PolicyAction.Swap, facts.action)
        // It moves value as far as this app knows, and nothing about it was read.
        assertTrue(facts.movesValue)
        assertFalse(facts.fullyRead)
        assertNull(facts.wallet)
        assertNull(facts.asset)
        assertNull(facts.recipient)
        assertNull(facts.programs)
        assertNull(facts.amount)
        assertNull(facts.scope)
        assertEquals(0, facts.preparedVersion)
    }

    @Test
    fun aPluginThatIsThereButHasPreparedNothingEstablishesNothingEither() {
        val registry = PluginRegistry.of(TestPlugin(inspection = TestPlugin.verified()))

        val facts =
            pluginFacts(
                CONNECTION,
                swap(),
                mainnet,
                registry.resolve(SWAP_OPERATION, production),
                inspection = null,
            )

        assertFalse("nothing has been read yet", facts.fullyRead)
        assertNull(facts.amount)
    }

    @Test
    fun bytesAPluginCouldNotReadEstablishNothing() {
        val facts =
            pluginFacts(
                CONNECTION,
                swap(),
                mainnet,
                PluginRegistry.of(TestPlugin()).resolve(SWAP_OPERATION, production),
                inspection = ActionInspection.nothingEstablished(version = 3),
            )

        assertFalse(facts.fullyRead)
        assertNull(facts.programs)
        // The version of a reading that established nothing is not a version to approve.
        assertEquals(0, facts.preparedVersion)
    }

    @Test
    fun whatAPluginDidReadBecomesTheFactsTheRulesAreAppliedTo() {
        val inspection = TestPlugin.verified(amount = 250uL, version = 2)

        val facts =
            pluginFacts(
                CONNECTION,
                swap(),
                mainnet,
                PluginRegistry.of(TestPlugin()).resolve(SWAP_OPERATION, production),
                inspection,
            )

        assertEquals(TestPlugin.WALLET, facts.wallet)
        assertEquals(TestPlugin.RECIPIENT, facts.recipient)
        assertEquals(250uL, facts.amount)
        assertEquals(listOf(TestPlugin.PROGRAM), facts.programs)
        assertEquals(PolicyAsset.sol(mainnet), facts.asset)
        assertTrue(facts.fullyRead)
        assertEquals(2, facts.preparedVersion)
        // The counter it would count against is the owner's own connection, wallet and asset.
        assertEquals(CONNECTION, facts.scope?.connectionId)
        assertEquals(PolicyAsset.sol(mainnet), facts.scope?.asset)
    }

    @Test
    fun oneInstructionLeftUnreadWithholdsTheReadingHoweverWellTheRestMatched() {
        val facts =
            pluginFacts(
                CONNECTION,
                swap(),
                mainnet,
                PluginRegistry.of(TestPlugin()).resolve(SWAP_OPERATION, production),
                TestPlugin.partlyRead(),
            )

        // The facts it did read stand; the coverage answer is what withholds the verdict.
        assertEquals(TestPlugin.WALLET, facts.wallet)
        assertFalse(facts.fullyRead)
    }

    @Test
    fun theChainIsTheOwnersAndNeverThePluginsToName() {
        // A plugin reports the mint it read. Which chain that mint is on is the network the owner's
        // wallet is selected for, so a wallet that isn't connected leaves the asset unestablished
        // rather than having one guessed for it.
        val registry = PluginRegistry.of(TestPlugin())
        val resolution = registry.resolve(SWAP_OPERATION, production)
        val onDevnet =
            pluginFacts(
                CONNECTION,
                swap(),
                Network.NETWORK_DEVNET,
                resolution,
                TestPlugin.verified(mint = MINT),
            )
        val noWallet =
            pluginFacts(
                CONNECTION,
                swap(),
                Network.NETWORK_UNSPECIFIED,
                resolution,
                TestPlugin.verified(mint = MINT),
            )

        assertEquals(PolicyAsset.token(Network.NETWORK_DEVNET, MINT), onDevnet.asset)
        assertNull(noWallet.asset)
        assertNull(noWallet.scope)
    }

    @Test
    fun anUnservedOperationIsUnderRestrictionsUnderRulesThatAllowEverythingElse() {
        // The acceptance the whole boundary exists for: an unsupported operation must not inherit a
        // verified or allowed state from rules written for something else. These rules name the
        // action, the asset, the recipient, the programs and a generous limit, and every one of
        // them
        // is about a transfer the phone reads for itself. The swap matches none of it because
        // nothing about the swap was read.
        val policy =
            ConnectionPolicy(
                connectionId = CONNECTION,
                actions = Allowlist.of(PolicyAction.Swap, PolicyAction.Transfer),
                assets = Allowlist.of(PolicyAsset.sol(mainnet)),
                recipients = Allowlist.of(TestPlugin.RECIPIENT),
                programs = Allowlist.of(TestPlugin.PROGRAM),
                limits = mapOf(PolicyAsset.sol(mainnet) to AssetLimits(perOperation = 1_000uL)),
                updatedAt = SAVED,
            )
        val unserved =
            pluginFacts(
                CONNECTION,
                swap(),
                mainnet,
                PluginRegistry.of().resolve(SWAP_OPERATION, production),
            )

        val decision = evaluate(policy, unserved)

        assertEquals(PolicyAssessment.UnderRestrictions, decision.assessment)
        assertFalse(decision.allowed)
        // The action kind is established — the request itself says `swap` — and it is allowed.
        // Every
        // other rule needed a fact nobody read, so each one is unverified rather than matched: the
        // operation inherits no allowance from rules it was never checked against.
        assertEquals(
            PolicyCheckStatus.Passed,
            decision.checks.single { it.check == PolicyCheck.Action }.status,
        )
        // The asset, the recipient, the programs and both thresholds: five checks, and not one of
        // them could be applied, because the asset a threshold is written for wasn't established
        // either.
        assertEquals(
            listOf(
                PolicyReason.AssetUnverified,
                PolicyReason.RecipientUnverified,
                PolicyReason.ProgramUnverified,
                PolicyReason.AssetUnverified,
                PolicyReason.AssetUnverified,
            ),
            decision.reasons,
        )
        assertTrue(
            decision.checks
                .filter { it.check != PolicyCheck.Action }
                .all { it.status == PolicyCheckStatus.Unverified }
        )
        // And the owner is warned, so going ahead is a deliberate step rather than a quiet one.
        assertTrue(decision.warns)
    }

    @Test
    fun theSameRulesDoAllowTheSameOperationOnceAPluginHasReadItWhole() {
        // The other half of the pair: the rules are not the thing refusing the swap above, so with
        // a plugin that read every byte the same rules reach ALLOWED. The reading is the
        // difference.
        val policy =
            ConnectionPolicy(
                connectionId = CONNECTION,
                actions = Allowlist.of(PolicyAction.Swap),
                assets = Allowlist.of(PolicyAsset.sol(mainnet)),
                recipients = Allowlist.of(TestPlugin.RECIPIENT),
                programs = Allowlist.of(TestPlugin.PROGRAM),
                limits = mapOf(PolicyAsset.sol(mainnet) to AssetLimits(perOperation = 1_000uL)),
                updatedAt = SAVED,
            )
        val read =
            pluginFacts(
                CONNECTION,
                swap(),
                mainnet,
                PluginRegistry.of(TestPlugin()).resolve(SWAP_OPERATION, production),
                TestPlugin.verified(amount = 250uL),
            )

        val decision = evaluate(policy, read)

        assertEquals(PolicyAssessment.Allowed, decision.assessment)
        // And it still approves nothing: a verdict is read, and the owner's hand on the wallet is
        // what executes anything (docs/policy.md).
        assertTrue(decision.allowed)
    }

    @Test
    fun anInspectionThatReadNothingIsNeverApprovable() {
        assertFalse(ActionInspection.nothingEstablished().approvable)
        assertFalse(TestPlugin.partlyRead().approvable)
        assertTrue(TestPlugin.verified().approvable)
        assertEquals(Verdict.Verified, TestPlugin.verified().verdict)
    }

    @Test
    fun theTwoKindsOfFindingAreKeptApartAsTheTransferPathKeepsThemApart() {
        // Bytes that disagree with the request must not be approved; bytes the plugin simply didn't
        // read leave the review incomplete. They are different things, and a finding says which it
        // is rather than leaving a caller to guess from the verdict.
        val unread = TestPlugin.partlyRead().findings.single()
        assertFalse(unread.invalidates)
        assertEquals("unread", unread.code)
        assertEquals(Verdict.Unverified, TestPlugin.partlyRead().verdict)
    }

    @Test
    fun aPreparationStatesItsOwnDeadlineOrCarriesNone() {
        // Freshness is checked where the wallet is opened, not inside a plugin, so the deadline has
        // to travel with the bytes. A preparation that carries none is taken as fresh, exactly as a
        // transfer's is, which is why a plugin whose bytes expire has to say when.
        val bytes = ByteString.copyFromUtf8("prepared")
        assertNull(PluginPreparation(bytes, version = 1).expiresAtEpochSeconds)
        assertEquals(
            1_789_000_000L,
            PluginPreparation(bytes, version = 2, expiresAtEpochSeconds = 1_789_000_000L)
                .expiresAtEpochSeconds,
        )
    }

    @Test
    fun coverageIsDerivedFromTheCountsAndNotStatedSeparately() {
        // A plugin can't claim it read bytes it didn't finish reading.
        val partial = TestPlugin.partlyRead().facts!!
        assertFalse(partial.fullyRead)
        assertEquals(3, partial.instructionCount)
        assertEquals(2, partial.recognizedInstructions)
    }

    private fun ref() = requestRef {
        connectionId = CONNECTION
        requestId = REQUEST
    }

    private fun request(build: io.github.brrenat.seekervault.request.v1.ActionKt.Dsl.() -> Unit) =
        actionRequest {
            ref = ref()
            action = action(build)
            state = RequestState.REQUEST_STATE_PENDING
        }

    private fun ack(): ActionRequest = request { ack = ackAction { text = "Deploy finished" } }

    private fun message(): ActionRequest = request {
        signMessage = signMessageAction {
            wallet = TestPlugin.WALLET
            text = "Sign in to Example"
        }
    }

    private fun transfer(): ActionRequest = request {
        transfer = transferAction {
            wallet = TestPlugin.WALLET
            network = Network.NETWORK_MAINNET
            recipient = TestPlugin.RECIPIENT
            asset = asset { nativeSol = Asset.NativeSol.getDefaultInstance() }
            amount = "1"
        }
    }

    private fun swap(): ActionRequest = request {
        swap = swapAction {
            wallet = TestPlugin.WALLET
            network = Network.NETWORK_MAINNET
            inputAsset = asset { nativeSol = Asset.NativeSol.getDefaultInstance() }
            outputAsset = asset { tokenMint = MINT }
            inputAmount = "250"
            slippageBps = 50
        }
    }

    private companion object {
        const val CONNECTION = "6a5f0a0e-2f2f-4a07-9a1f-4f0f2b3f9a11"
        const val REQUEST = "9c1d7b3a-8e4f-4a52-b0c6-1d2e3f4a5b6c"
        const val MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        val SAVED: Instant = Instant.parse("2026-09-17T09:00:00Z")
    }
}
