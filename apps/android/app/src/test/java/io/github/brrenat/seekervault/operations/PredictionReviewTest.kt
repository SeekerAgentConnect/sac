package io.github.brrenat.seekervault.operations

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.designsystem.FactRowValueStyle
import io.github.brrenat.seekervault.designsystem.NetworkChipNetwork
import io.github.brrenat.seekervault.designsystem.OwnerInputCardState
import io.github.brrenat.seekervault.designsystem.ReviewSheetHeaderChip
import io.github.brrenat.seekervault.designsystem.ReviewSheetState
import io.github.brrenat.seekervault.designsystem.ReviewSheetTags
import io.github.brrenat.seekervault.designsystem.ScopeChipSource
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
import io.github.brrenat.seekervault.plugins.PluginFailureCodes
import io.github.brrenat.seekervault.plugins.PluginFinding
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
import io.github.brrenat.seekervault.policy.PolicyReason
import io.github.brrenat.seekervault.policy.RequestFacts
import io.github.brrenat.seekervault.policy.evaluate
import io.github.brrenat.seekervault.policy.noPolicy
import io.github.brrenat.seekervault.policy.resolveEffectivePolicy
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.proposal
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.reviews.ReviewVerdict
import io.github.brrenat.seekervault.reviews.reviewVerdict
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
        failure: OperationFailure? = null,
        preparing: Boolean = false,
        inspection: ActionInspection? = if (prepared) inspection() else null,
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
            inspection = inspection,
            assessment = assessment,
            acknowledged = acknowledged,
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
    fun theHeadlineStaysTheMarketWhenASideAndStakeAreChosen() {
        val before = sheet()
        assertEquals(record.proposal.title, before.headline)
        assertEquals(OwnerInputCardState.Unchosen, before.yourPart?.state)

        val after = sheet(review(choice = chosen))
        assertEquals(record.proposal.title, after.headline)
        assertEquals("5 USDC on Yes", after.yourPart?.summary)
    }

    @Test
    fun theSublineAndTheNoteSayTimesInLocalTimeAndNeverAsIso() {
        val state = sheet()
        // Whichever space the platform's pattern puts before AM/PM, the words are these. The
        // market's identifier is metadata, kept with the technical details (SEE-180).
        assertEquals(
            "Jupiter Prediction · closes Oct 2, 10:05 PM",
            state.subline.replace('\u202F', ' '),
        )
        assertFalse(state.subline.contains(MARKET_ID))
        val note = checkNotNull(state.section(PredictionReviewSections.ABOUT).note)
        assertFalse(note.body, Regex("""\d{4}-\d{2}-\d{2}T""").containsMatchIn(note.body))
        assertTrue(note.body, note.body.replace('\u202F', ' ').contains("closing Oct 2, 10:05 PM."))
        assertEquals("The publisher’s note · not verified", note.label)
        assertEquals("Oct 2, 2026, 10:05 PM", state.expiry.replace('\u202F', ' '))
    }

    @Test
    fun noLabelIsASnakeCaseKeyAndNoAmountIsInBaseUnits() {
        val state = sheet(review(choice = chosen, prepared = true))
        val facts = state.allFacts()
        val labels = facts.map { it.label } + state.terms!!.rows.map { it.label }
        labels.forEach { assertFalse(it, it.contains('_')) }
        val values = facts.map { it.value } + state.terms!!.rows.map { it.value }
        assertFalse(values.toString(), "5000000" in values)
        assertEquals("5 USDC", facts.single { it.label == "Least deposit" }.value)
        // The market's own source, named as that rather than as the provider (SEE-180).
        assertEquals("Polymarket", facts.single { it.label == "Market source" }.value)
        assertEquals("prediction.buy", facts.single { it.label == "Action" }.value)
        assertEquals(EVENT_ID, facts.single { it.label == "Event" }.value)
        assertEquals("6", facts.single { it.label == "Asset decimals" }.value)
        // The market appears once, although the transaction names it again as a reference.
        assertEquals(1, facts.count { it.value == MARKET_ID })
        // Two roles, one account: both are shown.
        assertEquals(ORDER.middle(), facts.single { it.label == "Received by" }.value)
        assertEquals(ORDER.middle(), facts.single { it.label == "Order account" }.value)
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
        assertEquals("Choose a side and an amount to get a quote.", terms.emptyText)
        assertEquals("Quote", terms.title)
    }

    @Test
    fun addressesAreMonoAndCutAtTheMiddleAndTheIdentifiersCopyInFull() {
        val facts = sheet(review(choice = chosen, prepared = true)).allFacts()
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
        assertEquals("Outside rules · no rules set", state.verdict?.heading)
        val warning = state.verdict!!.warnings.single()
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
        assertEquals("Approve and trade", live.primaryAction.label)
        assertEquals("Dismiss", live.secondaryAction.label)
        assertEquals(
            "Dismissing keeps the decision on this phone. CopyTrading is never told either way.",
            live.footerCaption,
        )
        val sandbox =
            sheet(review(choice = chosen, prepared = true, environment = PluginEnvironment.Sandbox))
        assertEquals("Simulate the trade", sandbox.primaryAction.label)
        assertTrue(sandbox.sandboxNotice != null)
        val everything =
            listOf(live, sandbox).flatMap { state ->
                listOf(state.title, state.headline, state.subline, state.footerCaption) +
                    state.allFacts().map { it.label } +
                    state.sections.flatMap { section -> section.infoBlocks.map { it.body } }
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
                    onOpenLink = { _, _ -> },
                )
            }
        }
        // On an 800dp-tall phone the body is far longer than the screen, and the decision is still
        // on it without scrolling.
        compose.onNodeWithText("Approve and trade").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Dismiss").assertIsDisplayed()
        compose.onNodeWithText("Approve and trade").performClick()
        assertEquals(0, approvals)

        compose.onNodeWithText("I have read the warning and want to approve anyway").performClick()
        assertEquals(true, acknowledged)
        compose.onNodeWithText("Approve and trade").assertIsEnabled().performClick()
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
        compose.onNodeWithText("Your side and amount").assertIsDisplayed()
        compose.onNodeWithText("The amount is yours alone", substring = true).assertIsDisplayed()
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

    // SEE-180: preparation and verification states.

    private fun ReviewSheetState.allFacts() = factRows + sections.flatMap { it.factRows }

    private fun ReviewSheetState.section(key: String) = sections.single { it.key == key }

    /** The owner's one global rule: predictions are an allowed action. */
    private fun globalRule(
        facts: RequestFacts,
        actions: Set<PolicyAction> = setOf(PolicyAction.Prediction),
    ): RequestAssessment {
        val policy =
            resolveEffectivePolicy(
                CONNECTION,
                GlobalPolicy(actions = Allowlist(actions), updatedAt = now),
                null,
            )
        return RequestAssessment(decision = evaluate(policy, facts), facts = facts, at = now)
    }

    private val unread =
        RequestFacts.unread(CONNECTION, PolicyAction.Prediction, PREDICTION_PROPOSAL)

    private fun readFacts(fullyRead: Boolean = true) =
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
            fullyRead = fullyRead,
            preparedVersion = 1,
        )

    private val insufficient =
        OperationFailure(
            PluginFailureCodes.INSUFFICIENT_FUNDS,
            io.github.brrenat.seekervault.R.string.jupiter_failure_insufficient_funds,
            "Insufficient funds",
        )

    /** Bytes with an instruction this phone does not read. */
    private fun unreadable() =
        inspection()
            .copy(
                verdict = Verdict.Unverified,
                findings =
                    listOf(
                        PluginFinding(
                            "unrecognized_instruction",
                            io.github.brrenat.seekervault.R.string.jupiter_finding_unrecognized,
                            invalidates = false,
                        )
                    ),
            )

    @Test
    fun beforeAnythingIsPreparedThereIsNoVerificationWarningNoRuleChipAndNoTick() {
        val assessment = globalRule(unread)
        // The evaluation itself stays conservative: it is not allowed, for want of a transaction.
        assertEquals(PolicyReason.RequestUnverified, assessment.decision.reason)
        // Read for the card and for the tile alike, that is not a warning yet.
        assertEquals(ReviewVerdict.Pending, assessment.decision.reviewVerdict(prepared = false))

        for (choice in listOf(ParameterChoice(emptyMap()), chosen)) {
            val state = sheet(review(choice = choice, assessment = assessment))
            assertNull(state.verdict)
            assertNull(state.confirmationCheckbox)
            assertFalse(state.primaryAction.enabled)
            assertNull(state.preparationError)
        }
        val initial = sheet(review(assessment = assessment))
        assertEquals("Choose a side and an amount to get a quote.", initial.terms?.emptyText)
        assertEquals(
            "Daily limits not set · transaction checked once prepared",
            initial.section(PredictionReviewSections.CHECKS).summary,
        )
        // Nothing claims a read that has not happened.
        assertTrue(
            initial.section(PredictionReviewSections.CHECKS).infoBlocks.any {
                it.body.startsWith("When you get a quote")
            }
        )
    }

    @Test
    fun aGenuineRuleWarningIsShownBeforePreparingUnderItsOwnSourceWithoutATick() {
        // Predictions are not on the owner's global allowlist: that is known without any bytes.
        val assessment = globalRule(unread, actions = setOf(PolicyAction.Swap))
        val before = sheet(review(choice = chosen, assessment = assessment))
        val warning = before.verdict!!.warnings.single()
        assertEquals("Global rule", warning.sourceLabel)
        assertEquals(ScopeChipSource.Global, warning.source)
        assertNull(before.confirmationCheckbox)

        // Prepared and read in full, the same warning asks for the tick, still as the global rule.
        val after =
            sheet(
                review(
                    choice = chosen,
                    prepared = true,
                    assessment = globalRule(readFacts(), actions = setOf(PolicyAction.Swap)),
                )
            )
        assertEquals(listOf("Global rule"), after.verdict!!.warnings.map { it.sourceLabel })
        assertEquals(
            "I have read the warning and want to approve anyway",
            after.confirmationCheckbox,
        )
    }

    @Test
    fun insufficientFundsIsOneErrorThatKeepsTheInputsAndOffersToTryAgain() {
        val state =
            sheet(review(choice = chosen, failure = insufficient, assessment = globalRule(unread)))
        val error = checkNotNull(state.preparationError)
        assertEquals("Insufficient funds", error.title)
        assertEquals(
            "Could not prepare the 5 USDC order. The provider reported insufficient funds.",
            error.message,
        )
        assertEquals("Try again", error.actionLabel)
        assertEquals("5 USDC on Yes", state.yourPart?.summary)
        // No synthetic verification warning, no rule chip, no tick, no stale placeholder.
        assertNull(state.verdict)
        assertNull(state.confirmationCheckbox)
        assertNull(state.terms)
        assertFalse(state.primaryAction.enabled)
        assertTrue(state.statusBlocks.isEmpty())
        // What the provider said, in full, under the technical details: in a block that wraps,
        // not a one-line row.
        val technical = state.section(PredictionReviewSections.TECHNICAL)
        assertEquals(
            "Insufficient funds",
            technical.infoBlocks.single { it.title == "The provider said" }.body,
        )
        assertTrue(technical.factRows.none { it.label == "The provider said" })
        assertEquals(
            PluginFailureCodes.INSUFFICIENT_FUNDS,
            technical.factRows.single { it.label == "Preparation error" }.value,
        )
    }

    @Test
    fun aRefreshShowsThatItIsFetchingAndNeverTheQuoteItIsReplacing() {
        // Refresh quote on an expired one, and Get a new quote on one this phone refused: the
        // provider is still working, and the old bytes are still held until it answers.
        val retained =
            listOf(
                review(
                    choice = chosen,
                    prepared = true,
                    expiresAtEpochSeconds = now.epochSecond - 1,
                    assessment = globalRule(readFacts()),
                    preparing = true,
                ),
                review(
                    choice = chosen,
                    prepared = true,
                    inspection = unreadable(),
                    assessment = globalRule(readFacts(fullyRead = false)),
                    preparing = true,
                ),
            )
        for (refreshing in retained) {
            val state = sheet(refreshing)
            assertEquals(emptyList<Any>(), state.terms?.rows)
            assertEquals("Getting a quote and checking the transaction…", state.terms?.emptyText)
            // Neither the old verdict, nor the old blocker, nor the old staleness is current.
            assertNull(state.verdict)
            assertNull(state.preparationError)
            assertNull(state.staleQuote)
            assertNull(state.confirmationCheckbox)
            assertFalse(state.primaryAction.enabled)
            // Nothing read out of the old bytes is shown as read.
            val technical = state.section(PredictionReviewSections.TECHNICAL).factRows
            assertTrue(technical.none { it.copyValue == ORDER })
            assertEquals(
                "Daily limits not set · transaction checked once prepared",
                state.section(PredictionReviewSections.CHECKS).summary,
            )
        }
        // An assessment left over from read bytes is not "within the rules" for unprepared ones.
        assertEquals(
            ReviewVerdict.Pending,
            globalRule(readFacts()).decision.reviewVerdict(prepared = false),
        )
    }

    @Test
    fun anOtherRefusalIsSaidInTheProvidersOwnExplanation() {
        val state =
            sheet(
                review(
                    choice = chosen,
                    failure =
                        OperationFailure(
                            "market_closed",
                            io.github.brrenat.seekervault.R.string.jupiter_failure_market_closed,
                        ),
                )
            )
        assertEquals("Nothing was prepared", state.preparationError?.title)
        assertEquals(
            context.getString(io.github.brrenat.seekervault.R.string.jupiter_failure_market_closed),
            state.preparationError?.message,
        )
    }

    @Test
    fun preparingSaysSoAndApprovalWaits() {
        val state = sheet(review(choice = chosen, preparing = true))
        assertEquals("Getting a quote and checking the transaction…", state.terms?.emptyText)
        assertFalse(state.primaryAction.enabled)
        assertNull(state.confirmationCheckbox)
    }

    @Test
    fun chosenInputsWithNothingPreparedAskForAQuoteRatherThanForAChoice() {
        // What a wallet change or a cleared preparation leaves: inputs, and no bytes for them.
        val state = sheet(review(choice = chosen))
        assertEquals("Get quote", state.staleQuote?.actionLabel)
        assertTrue(state.staleQuote!!.message.contains("5 USDC on Yes"))
        assertNull(state.terms)
        assertFalse(state.primaryAction.enabled)
    }

    @Test
    fun aPreparedTransactionReadInFullIsWithinTheRulesAndNeedsNoTick() {
        val state =
            sheet(review(choice = chosen, prepared = true, assessment = globalRule(readFacts())))
        assertEquals(emptyList<Any>(), state.verdict?.warnings)
        assertNull(state.confirmationCheckbox)
        assertTrue(state.primaryAction.enabled)
        assertEquals(
            "Daily limits not set · transaction read in full",
            state.section(PredictionReviewSections.CHECKS).summary,
        )
        // The quote is concise; the supporting figures are technical details.
        val quote = state.terms!!.rows.map { it.label }
        assertTrue(quote.toString(), "You spend" in quote && "This order costs" in quote)
    }

    @Test
    fun anUnreadableTransactionIsABlockerFromTheTransactionCheckThatNoTickGetsPast() {
        val state =
            sheet(
                review(
                    choice = chosen,
                    prepared = true,
                    inspection = unreadable(),
                    assessment = globalRule(readFacts(fullyRead = false)),
                    acknowledged = true,
                )
            )
        assertEquals("This transaction can’t be approved", state.preparationError?.title)
        assertEquals(
            "This transaction contains an instruction this phone does not read.",
            state.preparationError?.message,
        )
        assertFalse(state.primaryAction.enabled)
        assertNull(state.confirmationCheckbox)
        // The finding is the phone's own reading, never a rule the owner wrote.
        val warning = state.verdict!!.warnings.single()
        assertTrue(warning.message.startsWith("This phone could not account for the whole"))
        assertEquals("Transaction check", warning.sourceLabel)
        assertEquals(ScopeChipSource.Verification, warning.source)
        assertEquals(
            "Daily limits not set · transaction not read in full",
            state.section(PredictionReviewSections.CHECKS).summary,
        )
    }

    @Test
    fun theReviewIsFourCollapsedSectionsAndEverythingMovedIsStillThere() {
        val state = sheet(review(choice = chosen, prepared = true))
        assertEquals(
            listOf(
                "Limits and checks",
                "About this order and risks",
                "Jupiter and terms",
                "Technical details",
            ),
            state.sections.map { it.title },
        )
        // Only the wallet is a loose row; the long list of cards and identifiers is gone.
        assertEquals(listOf("Wallet · paid and signed by"), state.factRows.map { it.label })
        assertTrue(state.infoBlocks.isEmpty())
        assertNull(state.dailySpend)
        assertNull(state.note)
        val technical = state.section(PredictionReviewSections.TECHNICAL).factRows
        assertEquals(USDC_MINT, technical.single { it.label == "Asset mint" }.copyValue)
        assertEquals(MARKET_ID, technical.single { it.label == "Market" }.copyValue)
        assertEquals(
            "From",
            state.section(PredictionReviewSections.PROVIDER).factRows.first().label,
        )
    }

    @Test
    fun aSectionOpensInPlaceSaysItIsOpenAndStaysOpenAcrossAQuoteUpdate() {
        var current by mutableStateOf(review(choice = chosen, preparing = true))
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                PredictionReviewScreen(
                    review = current,
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
        val technical = ReviewSheetTags.section(PredictionReviewSections.TECHNICAL)
        compose.onNodeWithText("Asset mint").assertDoesNotExist()
        compose
            .onNodeWithTag(technical)
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .performClick()
        compose
            .onNodeWithTag(technical)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        compose.onNodeWithText("Asset mint").assertExists()

        // The quote arrives: the sheet recomposes and the section is still open.
        current = review(choice = chosen, prepared = true)
        compose.waitForIdle()
        compose.onNodeWithText("Asset mint").assertExists()
    }

    @Test
    fun tryingAgainAfterAFailurePreparesAgain() {
        var prepares = 0
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                PredictionReviewScreen(
                    review = review(choice = chosen, failure = insufficient),
                    source = PredictionReviewSource("CopyTrading"),
                    wallet = wallet,
                    now = now,
                    onOwnerInput = {},
                    onPrepare = { prepares++ },
                    onApprove = {},
                    onDismiss = {},
                    onAcknowledge = {},
                    onRules = {},
                    onBack = {},
                    onOpenLink = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Insufficient funds").assertIsDisplayed()
        compose.onNodeWithText("I have read the warning", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Approve and trade").assertIsNotEnabled()
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        assertEquals(1, prepares)
    }

    @Test
    fun theMinimumIsSaidNextToTheAmount() {
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                PredictionParametersSheet(review = review(), onUse = {}, onClose = {})
            }
        }
        compose.onNodeWithText("Minimum 5 USDC", substring = true).assertIsDisplayed()
    }

    private companion object {
        const val PAYER = "D3QxmK1oUkzJ8vsoWgxzuotSFNLLRe1T"
        const val ORDER = "Hut593VASqP7mq6w62JaXk2YjTx7wx3n"
        const val POSITION = "8G4K2rceeiTRV6V97zLvnYfMGstuRaR9"
    }
}
