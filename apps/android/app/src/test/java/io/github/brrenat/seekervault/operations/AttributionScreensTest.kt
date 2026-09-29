package io.github.brrenat.seekervault.operations

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.WalletHandoffScreen
import io.github.brrenat.seekervault.designsystem.HistoryDetailCallbacks
import io.github.brrenat.seekervault.designsystem.HistoryDetailResponse
import io.github.brrenat.seekervault.designsystem.HistoryDetailScreen
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.history.HistoryDetailClock
import io.github.brrenat.seekervault.history.signalHistoryDetail
import io.github.brrenat.seekervault.jupiter.FEE_ACCOUNT_SOL
import io.github.brrenat.seekervault.jupiter.FEE_OWNER
import io.github.brrenat.seekervault.jupiter.PREDICTION_ABOUT
import io.github.brrenat.seekervault.jupiter.PREDICTION_BUY_CAPABILITY
import io.github.brrenat.seekervault.jupiter.PREDICTION_RECEIPT
import io.github.brrenat.seekervault.jupiter.PREDICTION_VENUE_NAME
import io.github.brrenat.seekervault.jupiter.SOL_MINT
import io.github.brrenat.seekervault.jupiter.SWAP_CAPABILITY
import io.github.brrenat.seekervault.jupiter.SWAP_ROUTING_NAME
import io.github.brrenat.seekervault.jupiter.SwapFee
import io.github.brrenat.seekervault.jupiter.SwapFeePolicy
import io.github.brrenat.seekervault.jupiter.USDC_MINT
import io.github.brrenat.seekervault.jupiter.feeQuote
import io.github.brrenat.seekervault.jupiter.inspectSwap
import io.github.brrenat.seekervault.jupiter.swapAbout
import io.github.brrenat.seekervault.jupiter.swapTransaction
import io.github.brrenat.seekervault.jupiter.usdcTerms
import io.github.brrenat.seekervault.plugins.ActionInspection
import io.github.brrenat.seekervault.plugins.InspectedAction
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PreparedOperation
import io.github.brrenat.seekervault.plugins.actions.ActionPayload
import io.github.brrenat.seekervault.plugins.actions.PredictionOutcomes
import io.github.brrenat.seekervault.plugins.actions.PredictionParameterNames
import io.github.brrenat.seekervault.plugins.actions.PredictionPayloadResult
import io.github.brrenat.seekervault.plugins.actions.SwapChoice
import io.github.brrenat.seekervault.plugins.actions.SwapParameterNames
import io.github.brrenat.seekervault.plugins.actions.predictionBuyInputs
import io.github.brrenat.seekervault.plugins.actions.predictionPayloadFrom
import io.github.brrenat.seekervault.plugins.actions.swapInputs
import io.github.brrenat.seekervault.proposals.ProposalExecution
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.binding
import io.github.brrenat.seekervault.proposals.proposal
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where the owner sees who carries a swap or an order out, and what the SAC service fee is
 * (SEE-173): the swap review before signing, the hand-off to the wallet, the executed result, the
 * History item, and the prediction review.
 *
 * Every screen is rendered from the real inspection of a built fee-bearing transaction, not from
 * hand-written rows. Run with `-Dseekervault.screenshots=<dir>` to also save each screen as a PNG
 * (docs/testing/see-173.md); a default run saves nothing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w390dp-h3000dp-xhdpi")
class AttributionScreensTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now: Instant = Instant.parse("2026-09-29T10:00:00Z")
    private val owner = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    private val selected = SelectedWallet(owner, WalletNetwork.Mainnet, selectedAt = now)

    private val terms = usdcTerms(inputMint = USDC_MINT, outputMint = SOL_MINT)
    private val amount = 25_000_000UL
    private val policy = SwapFeePolicy(20, FEE_OWNER, mapOf(SOL_MINT to FEE_ACCOUNT_SOL))
    private val fee = SwapFee.Charged(20, FEE_ACCOUNT_SOL, FEE_OWNER, SOL_MINT)
    private val quote = feeQuote(terms, amount, gross = 124_551_870UL, bps = 20)

    /** The real inspection of a real fee-bearing transaction, as the review would hold it. */
    private val inspection: ActionInspection =
        inspectSwap(
            terms = terms,
            choice = SwapChoice(amount, quote.slippageBps),
            quote = quote,
            wallet = selected,
            transaction =
                swapTransaction(
                    terms,
                    amount,
                    quote,
                    owner = owner,
                    platformFee = FEE_ACCOUNT_SOL,
                    platformFeeBps = 20,
                ),
            version = 1,
            fee = fee,
        )

    private val choice =
        ParameterChoice(
            mapOf(
                SwapParameterNames.INPUT_AMOUNT to ParameterValue.Amount(amount),
                SwapParameterNames.SLIPPAGE_BPS to ParameterValue.Count(50U),
            )
        )

    private val record =
        ProposalRecord(connectionId = CONNECTION, proposal = proposal(swapProposal()))

    private val execution =
        ProposalExecution(
            binding = binding(record.proposal, choice).copy(receipt = inspection.receipt),
            startedAt = now,
            outcome = ProposalOutcome.Submitted(ByteString.copyFrom(ByteArray(64) { 7 })),
            settledAt = now.plusSeconds(4),
        )

    private fun swapReview(executed: Boolean = false): OperationReview =
        OperationReview(
            connectionId = CONNECTION,
            proposalId = PROPOSAL,
            generation = 1,
            record = if (executed) record.copy(execution = execution) else record,
            standing =
                if (executed) ProposalStanding.Executed(execution.outcome)
                else ProposalStanding.Open,
            environment = PluginEnvironment.Production,
            form = swapInputs(terms, SWAP_CAPABILITY),
            choice = choice,
            served = true,
            prepared = if (executed) null else PreparedOperation(ByteString.EMPTY, 1),
            inspection = if (executed) null else inspection,
            about = swapAbout(policy),
            wallet = selected,
        )

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        SeekerTheme(darkTheme = true) { content() }
    }

    private fun showSwap(review: OperationReview) = show {
        ProposalReviewScreen(
            review = review,
            label = "CopyTrading",
            wallet = selected,
            now = now,
            onChoose = { _, _ -> },
            onPrepare = {},
            onApprove = {},
            onDismiss = {},
            onAcknowledge = {},
            onBack = {},
        )
    }

    /** Whether [text] is shown at least once: a name may rightly appear in two places. */
    private fun present(text: String) =
        assertTrue(
            "\"$text\" is not shown",
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty(),
        )

    /** Saves what is on screen when a directory was asked for, and does nothing otherwise. */
    private fun capture(name: String) {
        val directory = System.getProperty("seekervault.screenshots") ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory).mkdirs()
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun theSwapReviewNamesMetisAndBreaksTheFeeOutBeforeSigning() {
        showSwap(swapReview())

        compose.onNodeWithTag(OperationTags.ABOUT).assertExists()
        present(SWAP_ROUTING_NAME)
        present("SAC service fee, taken from what you receive")
        present("0.2%")
        present("0.000249103 SOL")
        present(FEE_ACCOUNT_SOL)
        present("Priority fee (SOL)")
        capture("swap-review-fee")
    }

    @Test
    fun theWalletHandOffRepeatsTheRoutingAndTheFee() {
        val summary = "Swap will continue in Seed Vault" + handoffReceipt(inspection.receipt)
        assertEquals(
            "Swap will continue in Seed Vault\n" +
                "Swap routing: $SWAP_ROUTING_NAME\n" +
                "SAC service fee: 0.2% of what you receive\n" +
                "SAC service fee, estimated at review: 0.000249103 SOL",
            summary,
        )
        show {
            WalletHandoffScreen(
                summary = summary,
                onApprove = {},
                onDecline = {},
                onLeaveWithoutAnswering = {},
                walletApp = "Seed Vault",
            )
        }
        capture("swap-wallet-handoff")
    }

    @Test
    fun anExecutedSwapShowsWhatWasApprovedNotWhatIsTrueToday() {
        showSwap(swapReview(executed = true))

        compose.onNodeWithTag(OperationTags.RECEIPT).assertExists()
        present("SAC service fee, estimated at review")
        present(FEE_ACCOUNT_SOL)
        capture("swap-result-fee")
    }

    @Test
    fun theHistoryItemKeepsRoutingFeeTokenAndRecipient() {
        val model =
            signalHistoryDetail(
                record.copy(execution = execution),
                ProposalStanding.Executed(execution.outcome),
                connection = null,
                clock = HistoryDetailClock(ZoneId.of("UTC"), Locale.US),
            )
        val rows =
            (model.response as HistoryDetailResponse.Sent).rows.associate {
                it.label to it.value
            }
        assertEquals(SWAP_ROUTING_NAME, rows["Swap routing"])
        assertEquals("0.2% of what you receive", rows["SAC service fee"])
        assertEquals("0.000249103 SOL", rows["SAC service fee, estimated at review"])
        assertEquals(SOL_MINT, rows["SAC service fee token"])
        assertEquals(FEE_ACCOUNT_SOL, rows["SAC service fee recipient"])

        show { HistoryDetailScreen(model, HistoryDetailCallbacks(onBack = {})) }
        present("SAC service fee recipient")
        capture("history-swap-fee")
    }

    @Test
    fun thePredictionReviewNamesJupiterPredictionAndItsDisclosures() {
        val prediction =
            ProposalRecord(
                connectionId = CONNECTION,
                proposal = proposal(predictionProposal(expiresAt = now.plusSeconds(86_400))),
            )
        val payload =
            (predictionPayloadFrom(prediction.proposal.terms()) as PredictionPayloadResult.Valid)
                .payload
        val review =
            OperationReview(
                connectionId = CONNECTION,
                proposalId = prediction.proposal.key.proposalId,
                generation = 1,
                record = prediction,
                standing = ProposalStanding.Open,
                environment = PluginEnvironment.Production,
                payload = ActionPayload.PredictionBuy(payload),
                form = predictionBuyInputs(payload, PREDICTION_BUY_CAPABILITY),
                choice =
                    ParameterChoice(
                        mapOf(
                            PredictionParameterNames.OUTCOME to
                                ParameterValue.Selected(PredictionOutcomes.YES),
                            PredictionParameterNames.DEPOSIT to ParameterValue.Amount(5_000_000UL),
                        )
                    ),
                served = true,
                prepared = PreparedOperation(ByteString.EMPTY, 1),
                inspection =
                    ActionInspection(
                        verdict = Verdict.Verified,
                        findings = emptyList(),
                        facts =
                            InspectedAction(
                                wallet = owner,
                                movesValue = true,
                                mint = USDC_MINT,
                                recipient = null,
                                programs = emptyList(),
                                amount = 5_000_000UL,
                                decimals = 6,
                                instructionCount = 4,
                                recognizedInstructions = 4,
                            ),
                        version = 1,
                        receipt = PREDICTION_RECEIPT,
                    ),
                about = PREDICTION_ABOUT,
                wallet = selected,
            )
        val sheet =
            review.toPredictionSheet(
                context.resources,
                PredictionReviewSource("Jupiter Prediction demo"),
                selected,
                now,
                ZoneId.of("UTC"),
                Locale.US,
            )
        assertEquals(
            "Jupiter Prediction",
            sheet.factRows.single { it.label == "Prediction market" }.value,
        )
        val blocks = sheet.infoBlocks.joinToString("\n") { it.body }
        assertTrue(blocks.contains("Solana mainnet, with real funds"))
        assertTrue(blocks.contains("United States and South Korea"))
        assertTrue(blocks.contains("cannot be cancelled for a refund"))
        assertTrue(blocks.contains("SAC adds no fee"))
        assertTrue(
            sheet.factRows.any { it.link == "https://developers.jup.ag/docs/legal/terms-of-use" }
        )

        show {
            PredictionReviewScreen(
                review = review,
                source = PredictionReviewSource("Jupiter Prediction demo"),
                wallet = selected,
                now = now,
                onOwnerInput = {},
                onPrepare = {},
                onApprove = {},
                onDismiss = {},
                onAcknowledge = {},
                onRules = {},
                onBack = {},
                onOpenLink = { _, _ -> },
            )
        }
        present(PREDICTION_VENUE_NAME)
        capture("prediction-review-attribution")
    }
}
