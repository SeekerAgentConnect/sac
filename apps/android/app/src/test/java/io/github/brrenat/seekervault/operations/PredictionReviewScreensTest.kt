package io.github.brrenat.seekervault.operations

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.designsystem.ReviewSheetTags
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.inbox.RequestAssessment
import io.github.brrenat.seekervault.jupiter.PREDICTION_ABOUT
import io.github.brrenat.seekervault.jupiter.PREDICTION_BUY_CAPABILITY
import io.github.brrenat.seekervault.jupiter.USDC_MINT
import io.github.brrenat.seekervault.plugins.ActionInspection
import io.github.brrenat.seekervault.plugins.InspectedAction
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginDestination
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginFact
import io.github.brrenat.seekervault.plugins.PluginReference
import io.github.brrenat.seekervault.plugins.PreparedOperation
import io.github.brrenat.seekervault.plugins.actions.ActionPayload
import io.github.brrenat.seekervault.plugins.actions.PredictionOutcomes
import io.github.brrenat.seekervault.plugins.actions.PredictionParameterNames
import io.github.brrenat.seekervault.plugins.actions.PredictionPayloadResult
import io.github.brrenat.seekervault.plugins.actions.PredictionTermNames
import io.github.brrenat.seekervault.plugins.actions.predictionBuyInputs
import io.github.brrenat.seekervault.plugins.actions.predictionPayloadFrom
import io.github.brrenat.seekervault.policy.Allowlist
import io.github.brrenat.seekervault.policy.GlobalPolicy
import io.github.brrenat.seekervault.policy.PolicyAction
import io.github.brrenat.seekervault.policy.PolicyAsset
import io.github.brrenat.seekervault.policy.RequestFacts
import io.github.brrenat.seekervault.policy.evaluate
import io.github.brrenat.seekervault.policy.resolveEffectivePolicy
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.proposal
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.io.File
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The prediction review in the states SEE-180 is about, saved as PNGs for the pull request when the
 * build is run with `-Dseekervault.screenshots=<dir>` (nothing is written otherwise).
 *
 * The owner has one global rule — predictions are an allowed action — which is the configuration
 * that showed a "Global rule" warning before anything had been prepared.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w393dp-h852dp-xxhdpi")
class PredictionReviewScreensTest {
    @get:Rule val compose = createComposeRule()

    private val now: Instant = Instant.parse("2026-09-25T10:00:00Z")
    private val wallet =
        SelectedWallet(
            PAYER,
            WalletNetwork.Mainnet,
            selectedAt = Instant.parse("2026-09-01T00:00:00Z"),
        )
    private val record =
        ProposalRecord(
            connectionId = CONNECTION,
            proposal =
                proposal(
                    predictionProposal(
                        expiresAt = Instant.parse("2026-10-02T20:05:00Z"),
                        note =
                            "Listed on Jupiter Prediction as \"Baltimore Orioles — Baltimore " +
                                "Orioles\", closing 2026-10-02T20:05:00Z. Its state, prices and " +
                                "rules are read on your own phone; which side to take, and how " +
                                "much, is yours.",
                        extra = mapOf(PredictionTermNames.LEAST_DEPOSIT to "5000000"),
                    )
                ),
        )
    private val terms =
        (predictionPayloadFrom(record.proposal.terms()) as PredictionPayloadResult.Valid).payload
    private val chosen =
        ParameterChoice(
            mapOf(
                PredictionParameterNames.OUTCOME to ParameterValue.Selected(PredictionOutcomes.YES),
                PredictionParameterNames.DEPOSIT to ParameterValue.Amount(5_000_000UL),
            )
        )

    private fun assessment(facts: RequestFacts): RequestAssessment {
        val policy =
            resolveEffectivePolicy(
                CONNECTION,
                GlobalPolicy(actions = Allowlist(setOf(PolicyAction.Prediction)), updatedAt = now),
                null,
            )
        return RequestAssessment(decision = evaluate(policy, facts), facts = facts, at = now)
    }

    private val unread =
        RequestFacts.unread(CONNECTION, PolicyAction.Prediction, PREDICTION_PROPOSAL)

    private val read =
        RequestFacts(
            connectionId = CONNECTION,
            requestId = PREDICTION_PROPOSAL,
            wallet = PAYER,
            action = PolicyAction.Prediction,
            movesValue = true,
            asset = PolicyAsset(Network.NETWORK_MAINNET, USDC_MINT),
            recipient = ORDER,
            programs = emptyList(),
            amount = 5_000_000UL,
            decimals = 6,
            fullyRead = true,
            preparedVersion = 1,
        )

    private val inspection =
        ActionInspection(
            verdict = Verdict.Verified,
            findings = emptyList(),
            facts =
                InspectedAction(
                    wallet = PAYER,
                    movesValue = true,
                    mint = USDC_MINT,
                    recipient = ORDER,
                    programs = emptyList(),
                    amount = 5_000_000UL,
                    decimals = 6,
                    instructionCount = 4,
                    recognizedInstructions = 4,
                ),
            version = 1,
            details =
                listOf(
                    PluginFact(R.string.jupiter_fact_side_yes, "7.35"),
                    PluginFact(R.string.jupiter_fact_stake, "4.99752 USDC"),
                    PluginFact(R.string.jupiter_fact_payout, "7.35 USDC"),
                    PluginFact(R.string.jupiter_fact_max_price, "0.68 USDC", technical = true),
                    PluginFact(R.string.jupiter_fact_provider_fee, "0.07 USDC"),
                    PluginFact(R.string.jupiter_fact_instructions, "4", technical = true),
                    PluginFact(R.string.jupiter_fact_resolved_accounts, "12", technical = true),
                ),
            references =
                listOf(
                    PluginReference("order_account", ORDER),
                    PluginReference("position_account", POSITION),
                ),
        )

    private fun review(
        choice: ParameterChoice = ParameterChoice(emptyMap()),
        prepared: Boolean = false,
        failure: OperationFailure? = null,
    ) =
        OperationReview(
            connectionId = CONNECTION,
            proposalId = record.proposal.key.proposalId,
            generation = 1,
            record = record,
            standing = ProposalStanding.Open,
            environment = PluginEnvironment.Production,
            payload = ActionPayload.PredictionBuy(terms),
            form = predictionBuyInputs(terms, PREDICTION_BUY_CAPABILITY),
            choice = choice,
            served = true,
            details = listOf(PluginFact(R.string.jupiter_fact_market_status, "open")),
            destinations =
                listOf(
                    PluginDestination(
                        R.string.jupiter_destination_market,
                        "https://jup.ag/prediction/${terms.marketId}",
                    )
                ),
            about = PREDICTION_ABOUT,
            prepared =
                if (prepared)
                    // No expiry: the sheet compares one with the real clock, not this fixed one.
                    PreparedOperation(com.google.protobuf.ByteString.EMPTY, 1, null)
                else null,
            inspection = if (prepared) inspection else null,
            failure = failure,
            assessment = assessment(if (prepared) read else unread),
            wallet = wallet,
            preparedFor = if (prepared) wallet else null,
        )

    private fun show(review: OperationReview) {
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                PredictionReviewScreen(
                    review = review,
                    source = PredictionReviewSource("CopyTrading"),
                    wallet = wallet,
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
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val directory = System.getProperty("seekervault.screenshots") ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory).mkdirs()
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private val insufficient =
        OperationFailure(
            "insufficient_funds",
            R.string.jupiter_failure_insufficient_funds,
            "Insufficient funds",
        )

    @Test
    fun initial() {
        show(review())
        capture("prediction-initial")
    }

    @Test
    fun insufficientFunds() {
        show(review(choice = chosen, failure = insufficient))
        capture("prediction-insufficient-funds")
    }

    @Test
    fun prepared() {
        show(review(choice = chosen, prepared = true))
        capture("prediction-prepared")
    }

    @Test
    @Config(qualifiers = "en-rUS-w393dp-h3200dp-xxhdpi")
    fun initialFullLength() {
        show(review())
        capture("prediction-initial-full")
    }

    @Test
    @Config(qualifiers = "en-rUS-w393dp-h3200dp-xxhdpi")
    fun preparedFullLength() {
        show(review(choice = chosen, prepared = true))
        capture("prediction-prepared-full")
    }

    /** At a larger system font the pinned footer still leaves the error and its action readable. */
    @Test
    @Config(fontScale = 1.3f)
    fun insufficientFundsLargeFont() {
        show(review(choice = chosen, failure = insufficient))
        compose.onNodeWithText("Try again").performScrollTo().assertIsDisplayed()
        capture("prediction-insufficient-funds-font-130")
    }

    @Test
    @Config(qualifiers = "en-rUS-w393dp-h4000dp-xxhdpi")
    fun preparedWithDetailsOpen() {
        show(review(choice = chosen, prepared = true))
        compose
            .onNodeWithTag(ReviewSheetTags.section(PredictionReviewSections.PROVIDER))
            .performClick()
        compose
            .onNodeWithTag(ReviewSheetTags.section(PredictionReviewSections.TECHNICAL))
            .performClick()
        compose.waitForIdle()
        capture("prediction-prepared-details-open")
    }

    private companion object {
        const val PAYER = "D3QxmK1oUkzJ8vsoWgxzuotSFNLLRe1T"
        const val ORDER = "Hut593VASqP7mq6w62JaXk2YjTx7wx3n"
        const val POSITION = "8G4K2rceeiTRV6V97zLvnYfMGstuRaR9"
    }
}
