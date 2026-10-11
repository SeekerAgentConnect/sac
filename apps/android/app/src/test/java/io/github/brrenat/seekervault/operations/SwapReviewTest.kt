package io.github.brrenat.seekervault.operations

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.designsystem.NetworkChipNetwork
import io.github.brrenat.seekervault.designsystem.OwnerInputCardKind
import io.github.brrenat.seekervault.designsystem.OwnerInputCardState
import io.github.brrenat.seekervault.designsystem.ReviewSheetHeaderChip
import io.github.brrenat.seekervault.designsystem.ReviewSheetState
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.inbox.RequestAssessment
import io.github.brrenat.seekervault.jupiter.SOL_MINT
import io.github.brrenat.seekervault.jupiter.SWAP_CAPABILITY
import io.github.brrenat.seekervault.jupiter.USDC_MINT
import io.github.brrenat.seekervault.plugins.ActionInspection
import io.github.brrenat.seekervault.plugins.InspectedAction
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginFailureCodes
import io.github.brrenat.seekervault.plugins.PreparedOperation
import io.github.brrenat.seekervault.plugins.actions.ActionPayload
import io.github.brrenat.seekervault.plugins.actions.SwapParameterNames
import io.github.brrenat.seekervault.plugins.actions.SwapPayload
import io.github.brrenat.seekervault.plugins.actions.swapInputs
import io.github.brrenat.seekervault.policy.PolicyAction
import io.github.brrenat.seekervault.policy.PolicyReason
import io.github.brrenat.seekervault.policy.RequestFacts
import io.github.brrenat.seekervault.policy.noPolicy
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.proposal
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A swap signal, reviewed in the same sheet a prediction is: the pair as the headline, the owner's
 * amount and wallet as one card, the same stages, footer and sections.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS-w390dp-h800dp-xxhdpi")
class SwapReviewTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now: Instant = Instant.parse("2026-09-17T10:00:00Z")
    private val wallet =
        SelectedWallet(
            PAYER,
            WalletNetwork.Mainnet,
            selectedAt = Instant.parse("2026-09-01T00:00:00Z"),
        )

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

    private val record =
        ProposalRecord(connectionId = CONNECTION, proposal = proposal(swapProposal()))

    private val chosen =
        ParameterChoice(
            mapOf(
                SwapParameterNames.INPUT_AMOUNT to ParameterValue.Amount(2_500_000UL),
                SwapParameterNames.SLIPPAGE_BPS to ParameterValue.Count(50U),
            )
        )

    private fun review(
        choice: ParameterChoice = ParameterChoice(emptyMap()),
        prepared: Boolean = false,
        expiresAtEpochSeconds: Long? = null,
        preparing: Boolean = false,
        failure: OperationFailure? = null,
        previewed: Boolean = false,
        standing: ProposalStanding = ProposalStanding.Open,
    ): OperationReview =
        OperationReview(
            connectionId = CONNECTION,
            proposalId = PROPOSAL,
            generation = 1,
            record = record,
            standing = standing,
            network = Network.NETWORK_DEVNET,
            previewable = true,
            previewed = previewed,
            payload = ActionPayload.Swap(terms),
            form = swapInputs(terms, SWAP_CAPABILITY),
            choice = choice,
            served = true,
            prepared =
                if (prepared)
                    PreparedOperation(
                        com.google.protobuf.ByteString.EMPTY,
                        1,
                        expiresAtEpochSeconds,
                    )
                else null,
            inspection = if (prepared) inspection() else null,
            assessment =
                RequestAssessment(
                    decision = noPolicy(PolicyReason.NoPolicyConfigured),
                    facts =
                        RequestFacts.movesNothing(CONNECTION, PolicyAction.MessageSignature, "p"),
                    at = now,
                ),
            failure = failure,
            preparing = preparing,
        )

    private fun inspection() =
        ActionInspection(
            verdict = Verdict.Verified,
            findings = emptyList(),
            facts =
                InspectedAction(
                    wallet = PAYER,
                    movesValue = true,
                    mint = USDC_MINT,
                    recipient = PAYER,
                    programs = emptyList(),
                    amount = 2_500_000UL,
                    decimals = 6,
                    instructionCount = 4,
                    recognizedInstructions = 4,
                ),
            version = 1,
        )

    private fun sheet(review: OperationReview = review()): ReviewSheetState =
        review.toSwapSheet(
            context.resources,
            PredictionReviewSource("CopyTrading"),
            wallet,
            now,
            ZoneId.of("UTC"),
            Locale.US,
        )

    @Test
    fun onlyAnUnexecutedSwapIsReviewedInTheSheet() {
        assertTrue(record.reviewedAsSwap)
        assertFalse(record.reviewedAsPrediction)
    }

    @Test
    fun thePairIsTheHeadlineAndTheChipsMatchThePredictionSheet() {
        val state = sheet()
        assertEquals("Swap", state.title)
        assertEquals("USDC → SOL", state.headline)
        assertTrue(state.subline.endsWith("price may move up to 1%"))
        assertEquals(
            listOf(
                ReviewSheetHeaderChip.Signal,
                ReviewSheetHeaderChip.Feed("CopyTrading"),
                ReviewSheetHeaderChip.Network(NetworkChipNetwork.Devnet, flagged = true),
            ),
            state.headerChips,
        )
    }

    @Test
    fun theOwnersPartIsOneCardSayingTheAmountAndTheWallet() {
        val before = sheet()
        assertEquals(OwnerInputCardKind.Swap, before.yourPart?.kind)
        assertEquals(OwnerInputCardState.Unchosen, before.yourPart?.state)
        assertFalse(before.primaryAction.enabled)

        val after = sheet(review(choice = chosen))
        assertEquals(OwnerInputCardState.Chosen, after.yourPart?.state)
        assertEquals("2.5 USDC · ${PAYER.middle()}", after.yourPart?.summary)
        // Chosen and not yet quoted: said where the quote would be, with the way on.
        assertNotNull(after.staleQuote)
    }

    @Test
    fun aQuoteBeingFetchedReplacesTheActionsAndAReadOneEnablesApprove() {
        assertEquals(
            "Getting a quote…",
            sheet(review(choice = chosen, preparing = true)).footerStatus,
        )
        val ready = sheet(review(choice = chosen, prepared = true))
        assertNull(ready.footerStatus)
        assertNull(ready.staleQuote)
        assertTrue(ready.primaryAction.enabled)
        assertEquals("Approve and swap", ready.primaryAction.label)
        assertEquals(true, ready.previewAction?.enabled)
    }

    @Test
    fun anExpiredQuoteIsApprovedWithoutARefreshStep() {
        // Approving a quote that ran out prepares it again first (OperationViewModel), so there is
        // no separate refresh for the owner to tap.
        val state =
            sheet(
                review(
                    choice = chosen,
                    prepared = true,
                    expiresAtEpochSeconds = now.epochSecond - 1,
                )
            )
        assertTrue(state.primaryAction.enabled)
        assertNull(state.staleQuote)
    }

    @Test
    fun insufficientFundsIsSaidWithTheChosenAmount() {
        val state =
            sheet(
                review(
                    choice = chosen,
                    failure = OperationFailure(PluginFailureCodes.INSUFFICIENT_FUNDS, null, null),
                )
            )
        assertEquals("Insufficient funds", state.preparationError?.title)
        assertTrue(state.preparationError?.message.orEmpty().contains("2.5 USDC"))
    }

    @Test
    fun theTermsAreLabelledInTheTechnicalSection() {
        val technical =
            sheet(review(choice = chosen)).sections.single {
                it.key == PredictionReviewSections.TECHNICAL
            }
        val rows = technical.factRows.associate { it.label to it.value }
        assertEquals("USDC", rows["You pay"])
        assertEquals("SOL", rows["You receive"])
        assertEquals("0.5%", rows["Price movement you allow"])
        assertEquals("1%", rows["Most the signal allows"])
        // Every term is read; none is repeated under its wire key.
        assertFalse(technical.factRows.any { it.label == "Max slippage bps" })
    }

    @Test
    fun aSignalNoLongerOpenHasNoDecisionFooter() {
        assertFalse(sheet(review(standing = ProposalStanding.Cancelled)).actionsShown)
    }

    @Test
    fun theSheetOpensTheOwnersPartAndApproves() {
        var opened = 0
        var approved = 0
        compose.setContent {
            SeekerTheme {
                SwapReviewScreen(
                    review = review(choice = chosen, prepared = true),
                    source = PredictionReviewSource("CopyTrading"),
                    wallet = wallet,
                    now = now,
                    onOwnerInput = { opened++ },
                    onPrepare = {},
                    onApprove = { approved++ },
                    onDismiss = {},
                    onAcknowledge = {},
                    onRules = {},
                    onBack = {},
                    onOpenLink = { _, _ -> },
                )
            }
        }
        compose.onNodeWithTag(SwapReviewTags.SHEET).assertIsDisplayed()
        compose.onNodeWithText("2.5 USDC · ${PAYER.middle()}").assertExists()
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Approve and swap").performClick()
        assertEquals(1, opened)
        assertEquals(1, approved)
    }

    private companion object {
        const val PAYER = "D3QxmK1oUkzJ8vsoWgxzuotSFNLLRe1T"
    }
}
