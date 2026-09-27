package io.github.brrenat.seekervault.operations

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.jupiter.SOL_MINT
import io.github.brrenat.seekervault.jupiter.SWAP_CAPABILITY
import io.github.brrenat.seekervault.jupiter.USDC_MINT
import io.github.brrenat.seekervault.plugins.ActionInspection
import io.github.brrenat.seekervault.plugins.InspectedAction
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginDestination
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginFact
import io.github.brrenat.seekervault.plugins.PluginFinding
import io.github.brrenat.seekervault.plugins.PreparedOperation
import io.github.brrenat.seekervault.plugins.actions.SwapParameterNames
import io.github.brrenat.seekervault.plugins.actions.SwapPayload
import io.github.brrenat.seekervault.plugins.actions.SwapTermNames
import io.github.brrenat.seekervault.plugins.actions.swapInputs
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.binding
import io.github.brrenat.seekervault.proposals.proposal
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two screens a person actually uses (SEE-93).
 *
 * They are the same layout the transfer review already established, and the things worth asserting
 * are the same: the publisher's words are shown as theirs, the facts this phone read are shown
 * separately, Approve appears only for bytes the phone accounted for whole, and the amount the
 * owner types reaches the app in exact base units.
 */
@RunWith(AndroidJUnit4::class)
class ProposalScreensTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now: Instant = Instant.parse("2026-09-17T10:00:00Z")
    private val chosen = mutableListOf<Pair<ParameterKey, ParameterValue>>()
    private var prepares = 0
    private var approvals = 0
    private var dismissals = 0
    private val opened = mutableListOf<Pair<String, String?>>()

    private val terms =
        SwapPayload(
            inputMint = USDC_MINT,
            inputDecimals = 6,
            outputMint = SOL_MINT,
            outputDecimals = 9,
            maxSlippageBps = 100,
            inputSymbol = "USDC",
            outputSymbol = "SOL",
        )

    private fun record(): ProposalRecord =
        ProposalRecord(connectionId = CONNECTION, proposal = proposal(swapProposal()))

    private fun review(
        prepared: Boolean = false,
        verdict: Verdict = Verdict.Verified,
        findings: List<PluginFinding> = emptyList(),
        standing: ProposalStanding = ProposalStanding.Open,
        served: Boolean = true,
        failure: OperationFailure? = null,
        problem: OperationProblem? = null,
        environment: PluginEnvironment = PluginEnvironment.Production,
    ): OperationReview =
        OperationReview(
            connectionId = CONNECTION,
            proposalId = PROPOSAL,
            generation = 1,
            record = record(),
            standing = standing,
            environment = environment,
            form =
                if (served) swapInputs(terms, SWAP_CAPABILITY)
                else io.github.brrenat.seekervault.plugins.ParameterForm(),
            choice =
                ParameterChoice(
                    mapOf(
                        SwapParameterNames.INPUT_AMOUNT to ParameterValue.Amount(2_500_000UL),
                        SwapParameterNames.SLIPPAGE_BPS to ParameterValue.Count(50U),
                    )
                ),
            served = served,
            prepared =
                if (prepared) PreparedOperation(com.google.protobuf.ByteString.EMPTY, 1) else null,
            inspection =
                if (!prepared) null
                else
                    ActionInspection(
                        verdict = verdict,
                        findings = findings,
                        facts =
                            InspectedAction(
                                wallet = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM",
                                movesValue = true,
                                mint = USDC_MINT,
                                recipient = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM",
                                programs = listOf("11111111111111111111111111111111"),
                                amount = 2_500_000UL,
                                decimals = 6,
                                instructionCount = 4,
                                recognizedInstructions = 4,
                            ),
                        version = 1,
                        details =
                            listOf(PluginFact(R.string.jupiter_fact_minimum_out, "0.0985 SOL")),
                    ),
            failure = failure,
            problem = problem,
        )

    /**
     * Scrolls the review to a node and then asserts it is on screen.
     *
     * The review is one long column on purpose — the publisher's words, then the owner's part, then
     * what the phone read, then the rules — so most of what a test is about starts below the fold.
     */
    private fun shown(tag: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun showReview(review: OperationReview) = compose.setContent {
        SeekerTheme {
            ProposalReviewScreen(
                review = review,
                label = "A trader",
                wallet =
                    SelectedWallet(
                        "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM",
                        WalletNetwork.Mainnet,
                        selectedAt = now,
                    ),
                now = now,
                onChoose = { key, value -> chosen += key to value },
                onPrepare = { prepares++ },
                onApprove = { approvals++ },
                onDismiss = { dismissals++ },
                onAcknowledge = {},
                onBack = {},
                onOpenLink = { url, deepLink -> opened += url to deepLink },
            )
        }
    }

    @Test
    fun anExecutedOperationOffersEachDestinationItsProviderNamed() {
        // What a History entry opens: the operation as it was executed, with somewhere to carry on
        // (SEE-157). The screen is handed two addresses per destination and knows what neither of
        // them is — there is nothing about any provider on it.
        val market =
            PluginDestination(R.string.jupiter_destination_market, "https://example.test/market/1")
        val order =
            PluginDestination(
                R.string.jupiter_destination_order,
                url = "https://example.test/orders",
                deepLink = "venue://orders",
            )
        val execution = executed()
        showReview(
            review(prepared = true, standing = ProposalStanding.Executed(execution.outcome))
                .copy(
                    record = record().copy(execution = execution),
                    destinations = listOf(market, order),
                )
        )

        shown(OperationTags.AFTERWARDS)
        val orderLabel = context.getString(R.string.jupiter_destination_order)
        compose.onNodeWithTag(OperationTags.link(orderLabel)).performClick()

        // The provider's own app link goes with it, so the opening can prefer the app; a
        // destination with none passes null and is simply opened.
        assertEquals(listOf("https://example.test/orders" to "venue://orders"), opened)
        compose
            .onNodeWithTag(
                OperationTags.link(context.getString(R.string.jupiter_destination_market))
            )
            .performClick()
        assertEquals("https://example.test/market/1" to null, opened.last())
    }

    /** One operation, already submitted, which is what a History entry is about. */
    private fun executed(): io.github.brrenat.seekervault.proposals.ProposalExecution =
        io.github.brrenat.seekervault.proposals.ProposalExecution(
            binding =
                binding(
                    proposal = record().proposal,
                    choice =
                        ParameterChoice(
                            mapOf(
                                SwapParameterNames.INPUT_AMOUNT to
                                    ParameterValue.Amount(2_500_000UL)
                            )
                        ),
                ),
            startedAt = now,
            outcome =
                io.github.brrenat.seekervault.proposals.ProposalOutcome.Submitted(
                    com.google.protobuf.ByteString.copyFrom(ByteArray(64) { 7 })
                ),
            settledAt = now,
        )

    @Test
    fun theListShowsWhatWasProposedAndWhereItStands() {
        val opened = mutableListOf<String>()
        compose.setContent {
            SeekerTheme {
                ProposalsScreen(
                    label = "A trader",
                    records = listOf(record()),
                    standings = { ProposalStanding.Open },
                    refreshing = false,
                    now = now,
                    onOpen = { opened += it.key.proposalId },
                    onRefresh = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(OperationTags.row(PROPOSAL)).assertIsDisplayed()
        // The operation at the protocol's own level, and the publisher's own unverified words.
        compose.onNodeWithText("swap").assertIsDisplayed()
        compose.onNodeWithText("Rotating out of the stable leg.").assertIsDisplayed()
        compose
            .onNodeWithText(context.getString(R.string.operation_standing_open))
            .assertIsDisplayed()

        compose.onNodeWithTag(OperationTags.row(PROPOSAL)).performClick()
        assertEquals(listOf(PROPOSAL), opened)
    }

    @Test
    fun anEmptyFeedSaysSoRatherThanLookingRead() {
        compose.setContent {
            SeekerTheme {
                ProposalsScreen(
                    label = "A trader",
                    records = emptyList(),
                    standings = { ProposalStanding.Open },
                    refreshing = false,
                    now = now,
                    onOpen = {},
                    onRefresh = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(OperationTags.EMPTY).assertIsDisplayed()
    }

    @Test
    fun thePublishersWordsAndTheirTermsAreShownAsTheirs() {
        showReview(review())

        compose.onNodeWithTag(OperationTags.NOTE).assertIsDisplayed()
        compose
            .onNodeWithTag(OperationTags.NOTE)
            .assertTextContains("Rotating out of the stable leg.")
        // Their term names and values, carried and not interpreted.
        compose.onNodeWithText(SwapTermNames.INPUT_MINT).assertIsDisplayed()
        compose.onNodeWithText(USDC_MINT).assertIsDisplayed()
    }

    @Test
    fun theAmountIsTypedInTheAssetsOwnUnitsAndReachesTheAppExactly() {
        showReview(review())

        compose.onNodeWithTag(OperationTags.AMOUNT).performTextClearance()
        compose.onNodeWithTag(OperationTags.AMOUNT).performTextInput("1.5")

        // Six decimals, shifted rather than multiplied: nothing rounds, and no floating-point type
        // comes between what was typed and what the transaction will carry.
        assertEquals(
            listOf(SwapParameterNames.INPUT_AMOUNT to ParameterValue.Amount(1_500_000UL)),
            chosen,
        )
    }

    @Test
    fun anAmountWithTooManyPlacesIsRefusedRatherThanRounded() {
        showReview(review())

        compose.onNodeWithTag(OperationTags.AMOUNT).performTextClearance()
        compose.onNodeWithTag(OperationTags.AMOUNT).performTextInput("1.1234567")

        assertEquals(emptyList<Pair<ParameterKey, ParameterValue>>(), chosen)
        compose
            .onNodeWithText(context.getString(R.string.operation_amount_invalid))
            .assertIsDisplayed()
    }

    @Test
    fun approveIsOfferedOnlyForBytesThePhoneAccountedForWhole() {
        // Nothing prepared: there is a Prepare button and no Approve.
        showReview(review(prepared = false))
        shown(OperationTags.PREPARE)
        compose.onNodeWithTag(OperationTags.APPROVE).assertDoesNotExist()
    }

    @Test
    fun anApprovableReviewShowsTheFactsAndOffersTheWallet() {
        showReview(review(prepared = true))

        shown(OperationTags.FACTS)
        // The plugin's own labelled value, shown by a screen that does not know what it means.
        compose
            .onNodeWithText(context.getString(R.string.jupiter_fact_minimum_out))
            .assertIsDisplayed()
        shown(OperationTags.APPROVE)
        compose.onNodeWithTag(OperationTags.APPROVE).performClick()
        assertEquals(1, approvals)
    }

    @Test
    fun aReviewWithAFindingInItOffersNothingToApprove() {
        showReview(
            review(
                prepared = true,
                verdict = Verdict.Invalid,
                findings =
                    listOf(PluginFinding("amount_mismatch", R.string.jupiter_finding_amount)),
            )
        )

        shown(OperationTags.FINDINGS)
        compose
            .onNodeWithText(context.getString(R.string.jupiter_finding_amount))
            .assertIsDisplayed()
        compose.onNodeWithTag(OperationTags.APPROVE).assertDoesNotExist()
    }

    @Test
    fun aProviderThatCouldNotPrepareIsQuotedAsItself() {
        showReview(
            review(
                failure =
                    OperationFailure(
                        "would_fail",
                        R.string.jupiter_failure_would_fail,
                        "Attempt to debit an account but found no record of a prior credit.",
                    )
            )
        )

        shown(OperationTags.FAILURE)
        compose
            .onNodeWithText(context.getString(R.string.jupiter_failure_would_fail))
            .assertIsDisplayed()
        // The provider's words, marked as theirs.
        compose
            .onNodeWithText(
                context.getString(
                    R.string.operation_provider_said,
                    "Attempt to debit an account but found no record of a prior credit.",
                )
            )
            .assertIsDisplayed()
    }

    @Test
    fun aServerThisBuildDoesNotServeIsReadInFullAndOffersNothing() {
        showReview(review(served = false))

        shown(OperationTags.UNSUPPORTED)
        // The publisher's own terms are still there to read.
        shown(OperationTags.TERMS)
        compose.onNodeWithTag(OperationTags.PREPARE).assertDoesNotExist()
        compose.onNodeWithTag(OperationTags.APPROVE).assertDoesNotExist()
    }

    @Test
    fun aProposalThatCannotBeActedOnSaysWhichRuleStoppedIt() {
        showReview(
            review(
                prepared = true,
                problem =
                    OperationProblem.Binding(
                        io.github.brrenat.seekervault.proposals.BindingProblem.PreparationExpired
                    ),
            )
        )

        shown(OperationTags.PROBLEM)
        compose
            .onNodeWithText(context.getString(R.string.operation_binding_preparation_expired))
            .assertIsDisplayed()
    }

    @Test
    fun aSandboxReviewSaysSoAndDoesNotOfferToApproveAnything() {
        // SEE-97's "unmistakable", on the one screen where it matters: the owner is about to press
        // a button, and a rehearsal and a purchase must not look the same.
        showReview(review(prepared = true, environment = PluginEnvironment.Sandbox))

        shown(OperationTags.SANDBOX)
        compose.onNodeWithText(context.getString(R.string.operation_sandbox)).assertIsDisplayed()
        shown(OperationTags.APPROVE)
        compose.onNodeWithText(context.getString(R.string.operation_simulate)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.operation_approve)).assertDoesNotExist()
    }

    @Test
    fun aProductionReviewSaysNothingAboutSandboxAndOffersToApprove() {
        showReview(review(prepared = true))

        compose.onNodeWithTag(OperationTags.SANDBOX).assertDoesNotExist()
        shown(OperationTags.APPROVE)
        compose.onNodeWithText(context.getString(R.string.operation_approve)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.operation_simulate)).assertDoesNotExist()
    }

    @Test
    fun anExpiredProposalOffersNoPreparationAtAll() {
        showReview(review(standing = ProposalStanding.Expired))

        compose
            .onNodeWithText(context.getString(R.string.operation_standing_expired))
            .assertIsDisplayed()
        compose.onNodeWithTag(OperationTags.PREPARE).assertDoesNotExist()
    }
}
