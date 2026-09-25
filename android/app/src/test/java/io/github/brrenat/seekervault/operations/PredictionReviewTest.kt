package io.github.brrenat.seekervault.operations

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.designsystem.FactRowValueStyle
import io.github.brrenat.seekervault.designsystem.NetworkChipNetwork
import io.github.brrenat.seekervault.designsystem.OwnerInputCardState
import io.github.brrenat.seekervault.designsystem.ReviewSheetHeaderChip
import io.github.brrenat.seekervault.designsystem.ReviewSheetState
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.inbox.RequestAssessment
import io.github.brrenat.seekervault.jupiter.EVENT_ID
import io.github.brrenat.seekervault.jupiter.MARKET_ID
import io.github.brrenat.seekervault.jupiter.PREDICTION_BUY_CAPABILITY
import io.github.brrenat.seekervault.jupiter.USDC_MINT
import io.github.brrenat.seekervault.plugins.ActionInspection
import io.github.brrenat.seekervault.plugins.InspectedAction
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterValue
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
import io.github.brrenat.seekervault.policy.PolicyAction
import io.github.brrenat.seekervault.policy.PolicyReason
import io.github.brrenat.seekervault.policy.RequestFacts
import io.github.brrenat.seekervault.policy.noPolicy
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.proposal
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The prediction review, read the way the design draws it (SEE-158): each assertion is one line of
 * the ticket's acceptance checklist.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS-w390dp-h800dp-xxhdpi")
class PredictionReviewTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now: Instant = Instant.parse("2026-09-25T10:00:00Z")
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
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

    private fun review(
        choice: ParameterChoice = ParameterChoice(emptyMap()),
        prepared: Boolean = false,
        expiresAtEpochSeconds: Long? = null,
        environment: PluginEnvironment = PluginEnvironment.Production,
        assessment: RequestAssessment? = noRules(),
        acknowledged: Boolean = false,
    ): OperationReview =
        OperationReview(
            connectionId = CONNECTION,
            proposalId = record.proposal.key.proposalId,
            generation = 1,
            record = record,
            standing = ProposalStanding.Open,
            environment = environment,
            payload = ActionPayload.PredictionBuy(terms),
            form = predictionBuyInputs(terms, PREDICTION_BUY_CAPABILITY),
            choice = choice,
            served = true,
            details =
                listOf(
                    PluginFact(
                        io.github.brrenat.seekervault.R.string.jupiter_fact_market_status,
                        "open",
                    )
                ),
            prepared =
                if (prepared)
                    PreparedOperation(
                        com.google.protobuf.ByteString.EMPTY,
                        1,
                        expiresAtEpochSeconds,
                    )
                else null,
            inspection = if (prepared) inspection() else null,
            assessment = assessment,
            acknowledged = acknowledged,
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
                    PluginFact(
                        io.github.brrenat.seekervault.R.string.jupiter_fact_side_yes,
                        "4.75",
                    ),
                    PluginFact(
                        io.github.brrenat.seekervault.R.string.jupiter_fact_stake,
                        "4.99752 USDC",
                    ),
                    PluginFact(
                        io.github.brrenat.seekervault.R.string.jupiter_fact_payout,
                        "4.75 USDC",
                    ),
                ),
            references =
                listOf(
                    PluginReference("order_account", ORDER),
                    PluginReference("position_account", POSITION),
                    PluginReference(PredictionTermNames.MARKET_ID, MARKET_ID),
                ),
        )

    private fun noRules() =
        RequestAssessment(
            decision = noPolicy(PolicyReason.NoPolicyConfigured),
            facts = RequestFacts.movesNothing(CONNECTION, PolicyAction.MessageSignature, "p"),
            at = now,
        )

    private fun sheet(review: OperationReview = review()): ReviewSheetState =
        review.toPredictionSheet(
            context.resources,
            PredictionReviewSource("CopyTrading"),
            wallet,
            now,
            zone,
            Locale.US,
        )

    @Test
    fun theTitleIsTheKindAndTheSourceIsAChipNotALine() {
        val state = sheet()
        assertEquals("Prediction", state.title)
        assertEquals(ReviewSheetHeaderChip.Signal, state.headerChips[0])
        assertEquals(ReviewSheetHeaderChip.Feed("CopyTrading"), state.headerChips[1])
        // Live mainnet: no environment chip, and the network is the neutral mainnet chip.
        assertEquals(
            listOf(ReviewSheetHeaderChip.Network(NetworkChipNetwork.Mainnet)),
            state.headerChips.drop(2),
        )
        assertNull(state.sandboxNotice)
    }

    @Test
    fun theHeadlineIsTheMarketUntilASideAndStakeAreChosen() {
        val before = sheet()
        assertEquals(record.proposal.title, before.headline)
        assertEquals(OwnerInputCardState.Unchosen, before.yourPart?.state)

        val after = sheet(review(choice = chosen))
        assertEquals("5 USDC on Yes", after.headline)
        assertEquals("5 USDC on Yes", after.yourPart?.summary)
    }

    @Test
    fun theSublineAndTheNoteSayTimesInLocalTimeAndNeverAsIso() {
        val state = sheet()
        // Whichever space the platform's pattern puts before AM/PM, the words are these.
        assertEquals(
            "Jupiter Prediction, market $MARKET_ID, closing Oct 2, 10:05 PM. " +
                "The side and the stake are yours.",
            state.subline.replace('\u202F', ' '),
        )
        val note = checkNotNull(state.note).body
        assertFalse(note, Regex("""\d{4}-\d{2}-\d{2}T""").containsMatchIn(note))
        assertTrue(note, note.replace('\u202F', ' ').contains("closing Oct 2, 10:05 PM."))
        assertEquals("The publisher’s note · not verified", state.note?.label)
        assertEquals("Oct 2, 2026, 10:05 PM", state.expiry.replace('\u202F', ' '))
    }

    @Test
    fun noLabelIsASnakeCaseKeyAndNoAmountIsInBaseUnits() {
        val state = sheet(review(choice = chosen, prepared = true))
        val labels = state.factRows.map { it.label } + state.terms!!.rows.map { it.label }
        labels.forEach { assertFalse(it, it.contains('_')) }
        val values = state.factRows.map { it.value } + state.terms!!.rows.map { it.value }
        assertFalse(values.toString(), "5000000" in values)
        assertEquals("5 USDC", state.factRows.single { it.label == "Least deposit" }.value)
        assertEquals("Polymarket", state.factRows.single { it.label == "Provider" }.value)
        assertEquals("prediction.buy", state.factRows.single { it.label == "Action" }.value)
        assertEquals(EVENT_ID, state.factRows.single { it.label == "Event" }.value)
        assertEquals("6", state.factRows.single { it.label == "Asset decimals" }.value)
        // The market appears once, although the transaction names it again as a reference.
        assertEquals(1, state.factRows.count { it.value == MARKET_ID })
        // Two roles, one account: both are shown.
        assertEquals(ORDER.middle(), state.factRows.single { it.label == "Received by" }.value)
        assertEquals(ORDER.middle(), state.factRows.single { it.label == "Order account" }.value)
    }

    @Test
    fun everyMoneyValueInTheQuoteHasItsUnit() {
        val rows = sheet(review(choice = chosen, prepared = true)).terms!!.rows
        assertEquals("open", rows.single { it.label == "Market status" }.value)
        assertEquals("5 USDC", rows.single { it.label == "You spend" }.value)
        assertEquals("4.99752 USDC", rows.single { it.label == "This order costs" }.value)
        assertEquals("4.75", rows.single { it.label == "Contracts bought on Yes" }.value)
    }

    @Test
    fun beforeAQuoteTheTermsCardSaysWhatOneWillShow() {
        val terms = sheet().terms!!
        assertTrue(terms.rows.isEmpty())
        assertTrue(terms.emptyText!!.startsWith("Choose a side and a stake"))
    }

    @Test
    fun addressesAreMonoAndCutAtTheMiddleAndTheIdentifiersCopyInFull() {
        val facts = sheet(review(choice = chosen, prepared = true)).factRows
        val mint = facts.single { it.label == "Asset mint" }
        assertEquals(USDC_MINT.take(8) + "…" + USDC_MINT.takeLast(8), mint.value)
        assertEquals(FactRowValueStyle.Mono, mint.valueStyle)
        assertEquals(USDC_MINT, mint.copyValue)
        val payer = facts.single { it.label == "Wallet · paid and signed by" }
        assertEquals(PAYER.take(8) + "…" + PAYER.takeLast(8), payer.value)
        assertEquals(
            record.proposal.key.proposalId,
            facts.single { it.label == "Proposal" }.copyValue,
        )
        assertEquals(
            record.proposal.key.serverId,
            facts.single { it.label == "Publisher" }.copyValue,
        )
    }

    @Test
    fun noRulesIsAWarningThatNamesItsRuleAndAsksForTheTick() {
        val state = sheet(review(choice = chosen, prepared = true))
        assertEquals("Outside rules · no rules set", state.verdict.heading)
        val warning = state.verdict.warnings.single()
        assertEquals(
            "This connection has no transaction rules yet. You are approving this request manually.",
            warning.message,
        )
        assertEquals("Connection rule", warning.sourceLabel)
        assertEquals(
            "I have read the warning and want to approve anyway",
            state.confirmationCheckbox,
        )
    }

    @Test
    fun theWordsArePredictionWordsNeverSwapWords() {
        val live = sheet(review(choice = chosen, prepared = true))
        assertEquals("Approve and stake", live.primaryAction.label)
        assertEquals("Dismiss", live.secondaryAction.label)
        assertEquals(
            "Dismissing keeps the decision on this phone. CopyTrading is never told either way.",
            live.footerCaption,
        )
        val sandbox =
            sheet(review(choice = chosen, prepared = true, environment = PluginEnvironment.Sandbox))
        assertEquals("Simulate the stake", sandbox.primaryAction.label)
        assertTrue(sandbox.sandboxNotice != null)
        val everything =
            listOf(live, sandbox).flatMap { state ->
                listOf(state.title, state.headline, state.subline, state.footerCaption) +
                    state.factRows.map { it.label } +
                    state.infoBlocks.map { it.body }
            }
        everything.forEach { assertFalse(it, it.contains("swap", ignoreCase = true)) }
    }

    @Test
    fun aQuoteThatRanOutCannotBeApprovedAndOffersARefresh() {
        val stale =
            sheet(
                review(
                    choice = chosen,
                    prepared = true,
                    expiresAtEpochSeconds = now.epochSecond - 1,
                )
            )
        assertFalse(stale.primaryAction.enabled)
        assertEquals("Refresh quote", stale.staleQuote?.actionLabel)
        val fresh =
            sheet(
                review(
                    choice = chosen,
                    prepared = true,
                    expiresAtEpochSeconds = now.epochSecond + 60,
                )
            )
        assertTrue(fresh.primaryAction.enabled)
        assertNull(fresh.staleQuote)
    }

    @Test
    fun theFooterIsPinnedAndApproveWaitsForTheTick() {
        var approvals = 0
        var acknowledged: Boolean? = null
        var ownerInput = 0
        var current by mutableStateOf(review(choice = chosen, prepared = true))
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                PredictionReviewScreen(
                    review = current,
                    source = PredictionReviewSource("CopyTrading"),
                    wallet = wallet,
                    now = now,
                    onOwnerInput = { ownerInput++ },
                    onPrepare = {},
                    onApprove = { approvals++ },
                    onDismiss = {},
                    onAcknowledge = {
                        acknowledged = it
                        current = current.copy(acknowledged = it)
                    },
                    onRules = {},
                    onBack = {},
                )
            }
        }
        // On an 800dp-tall phone the body is far longer than the screen, and the decision is still
        // on it without scrolling.
        compose.onNodeWithText("Approve and stake").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Dismiss").assertIsDisplayed()
        compose.onNodeWithText("Approve and stake").performClick()
        assertEquals(0, approvals)

        compose.onNodeWithText("I have read the warning and want to approve anyway").performClick()
        assertEquals(true, acknowledged)
        compose.onNodeWithText("Approve and stake").assertIsEnabled().performClick()
        assertEquals(1, approvals)

        compose.onNodeWithText("Change").performClick()
        assertEquals(1, ownerInput)
    }

    @Test
    fun theSideAndStakeAreChosenInTheStackedSheetAndHandedBackExactly() {
        var used: List<Pair<ParameterKey, ParameterValue>>? = null
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                PredictionParametersSheet(review = review(), onUse = { used = it }, onClose = {})
            }
        }
        compose.onNodeWithText("Your side and stake").assertIsDisplayed()
        compose.onNodeWithText("How much to stake", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(PredictionReviewTags.USE).assertIsNotEnabled()

        compose.onNodeWithTag(PredictionReviewTags.YES).performClick()
        compose.onNodeWithTag(PredictionReviewTags.AMOUNT).performTextInput("1")
        compose.onNodeWithTag(PredictionReviewTags.USE).performClick()
        // Below the market's floor: said in the asset's units, and nothing is handed back.
        compose.onNodeWithText("At least 5 USDC.").assertIsDisplayed()
        assertNull(used)

        compose.onNodeWithTag(PredictionReviewTags.AMOUNT).performTextInput("0")
        compose.onNodeWithTag(PredictionReviewTags.USE).performClick()
        assertEquals(
            listOf(
                PredictionParameterNames.OUTCOME to ParameterValue.Selected(PredictionOutcomes.YES),
                PredictionParameterNames.DEPOSIT to ParameterValue.Amount(10_000_000UL),
            ),
            used,
        )
    }

    private companion object {
        const val PAYER = "D3QxmK1oUkzJ8vsoWgxzuotSFNLLRe1T"
        const val ORDER = "Hut593VASqP7mq6w62JaXk2YjTx7wx3n"
        const val POSITION = "8G4K2rceeiTRV6V97zLvnYfMGstuRaR9"
    }
}
