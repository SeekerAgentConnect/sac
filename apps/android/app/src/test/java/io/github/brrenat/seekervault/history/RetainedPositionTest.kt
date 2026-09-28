package io.github.brrenat.seekervault.history

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.ReviewIdentity
import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.ActivityRecord
import io.github.brrenat.seekervault.activity.ReviewedOperation
import io.github.brrenat.seekervault.activity.ReviewedValue
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.designsystem.HistoryDetailLink
import io.github.brrenat.seekervault.designsystem.HistoryDetailTags
import io.github.brrenat.seekervault.designsystem.InboxTab
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.inbox.InboxUiState
import io.github.brrenat.seekervault.inbox.inboxScreenState
import io.github.brrenat.seekervault.jupiter.MARKET_ID
import io.github.brrenat.seekervault.jupiter.ORDER_PUBKEY
import io.github.brrenat.seekervault.jupiter.OWNER
import io.github.brrenat.seekervault.jupiter.POSITION_PUBKEY
import io.github.brrenat.seekervault.jupiter.predictionPosition
import io.github.brrenat.seekervault.jupiter.reading
import io.github.brrenat.seekervault.jupiter.wallet
import io.github.brrenat.seekervault.operations.predictionProposal
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.ProviderRegistry
import io.github.brrenat.seekervault.plugins.actions.PredictionOutcomes
import io.github.brrenat.seekervault.plugins.actions.PredictionParameterNames
import io.github.brrenat.seekervault.positions.PositionTracker
import io.github.brrenat.seekervault.positions.PositionsState
import io.github.brrenat.seekervault.positions.purchasesOf
import io.github.brrenat.seekervault.positions.storage.PositionStore
import io.github.brrenat.seekervault.proposals.ProposalExecution
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.binding
import io.github.brrenat.seekervault.proposals.proposal
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.wallet.encodeBase58
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * A position outlives the feed its signal came from (SEE-172, acceptance criteria 1 and 9): buy,
 * remove the connection — which removes the proposals it read — restart, and the original item is
 * still in History, still shows its position, and still offers the sale.
 */
@RunWith(AndroidJUnit4::class)
class RetainedPositionTest {
    @get:Rule val folder = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private val clock = HistoryDetailClock(ZoneOffset.UTC, Locale.US)
    private val now = Instant.parse("2026-09-28T08:00:00Z")
    private val key = RequestKey(CONNECTION, PROPOSAL)
    private val signature = ByteArray(64) { 4 }
    private val links =
        listOf(HistoryDetailLink("Your positions on Jupiter", "https://example.test/p"))

    private fun record(): ProposalRecord {
        val base = ProposalRecord(CONNECTION, proposal(predictionProposal(proposalId = PROPOSAL)))
        val side =
            ParameterChoice(
                mapOf(
                    PredictionParameterNames.OUTCOME to
                        ParameterValue.Selected(PredictionOutcomes.YES),
                    PredictionParameterNames.DEPOSIT to ParameterValue.Amount(5_000_000UL),
                )
            )
        return base.copy(
            execution =
                ProposalExecution(
                    binding = binding(base.proposal, side, wallet = OWNER),
                    startedAt = now.minusSeconds(600),
                    outcome = ProposalOutcome.Submitted(ByteString.copyFrom(signature)),
                    settledAt = now.minusSeconds(590),
                )
        )
    }

    private fun activity() =
        ActivityRecord(
            connectionId = CONNECTION,
            requestId = PROPOSAL,
            source = "Trader Signals",
            serverHost = "feeds.example",
            kind = ActivityKind.Operation,
            answeredAt = now.minusSeconds(600),
            recordedAt = now.minusSeconds(590),
            outcome = ActivityOutcome.Sent,
            operation =
                ReviewedOperation(
                    operation = "prediction",
                    plugin = "jupiter.prediction",
                    contract = 2,
                    revision = 1,
                    wallet = OWNER,
                    network = Network.NETWORK_MAINNET,
                    environment = PluginEnvironment.Production,
                    preparedVersion = 1,
                    references =
                        listOf(
                            ReviewedValue("order_account", ORDER_PUBKEY),
                            ReviewedValue("position_account", POSITION_PUBKEY),
                            ReviewedValue("market_id", MARKET_ID),
                        ),
                ),
            signature = encodeBase58(signature),
        )

    private fun tracker(dir: File) =
        PositionTracker(PositionStore(dir), providers = { ProviderRegistry.of() }, now = { now })

    /**
     * Bought while the feed was connected; then the connection is removed and the app restarts: a
     * fresh tracker over the same storage, and no feed record left anywhere. The position is then
     * read once, as opening the item would.
     */
    private fun afterRemovalAndRestart(): PositionsState {
        val dir = File(folder.root, "positions")
        tracker(dir).apply {
            load()
            link(purchasesOf(listOf(record()), listOf(activity())))
        }
        val restarted = tracker(dir).apply { load() }.state.value
        val holding = checkNotNull(restarted.holdings[POSITION_PUBKEY])
        return restarted.copy(
            holdings =
                mapOf(
                    POSITION_PUBKEY to
                        holding.copy(
                            snapshot = predictionPosition().reading(),
                            observedAt = now.minusSeconds(5),
                            attemptedAt = now.minusSeconds(5),
                        )
                )
        )
    }

    @Test
    fun theHoldingIsFoundByItsPurchaseAloneWithNoFeedRecord() {
        val state = afterRemovalAndRestart()
        // What the History page and the sale sheet both resolve the position through.
        assertEquals(
            POSITION_PUBKEY,
            state.holdingFor(ReviewIdentity.Signal(CONNECTION, PROPOSAL))?.held?.account,
        )
        // A private request's identity never names a position.
        assertEquals(null, state.holdingFor(ReviewIdentity.Private(CONNECTION, PROPOSAL)))

        val retained = retainedPurchases(state, emptyList(), listOf(activity())).single()
        assertEquals(key, retained.key)
        // While the feed record is still here, the item is the feed's and is not doubled.
        assertTrue(retainedPurchases(state, listOf(record()), listOf(activity())).isEmpty())

        val model = retainedHistoryDetail(retained, clock)
        assertEquals("Trader Signals", model.header.sourceName)
        assertEquals(encodeBase58(signature), model.transactions.single().signature)
        assertTrue(model.identifiers.any { it.value == MARKET_ID })

        val position = positionDetail(key, state, wallet(), links, now, clock)
        assertEquals(true, position.sell?.enabled)
        assertEquals(links, position.links)
    }

    @Test
    fun theItemStaysInHistoryAndReopensAsTheSameSignal() {
        val state = afterRemovalAndRestart()
        val retained = retainedPurchases(state, emptyList(), listOf(activity()))
        val history =
            inboxScreenState(
                    state = InboxUiState(),
                    feedRecords = emptyList(),
                    feedStanding = { ProposalStanding.Expired },
                    selectedTab = InboxTab.History,
                    now = now,
                    retained = retained,
                )
                .history
        assertEquals(listOf("feed/$CONNECTION/$PROPOSAL"), history.map { it.id })
        assertEquals("Trader Signals", history.single().model.sourceName)
    }

    @Test
    fun theReopenedItemShowsItsPositionAndSellsIt() {
        val state = afterRemovalAndRestart()
        val retained = retainedPurchases(state, emptyList(), listOf(activity()))
        val sold = mutableListOf<String>()
        val refreshed = mutableListOf<String>()
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                HistoryDetailRoute(
                    identity = ReviewIdentity.Signal(CONNECTION, PROPOSAL),
                    connections = emptyList(),
                    inboxState = InboxUiState(),
                    feedRecords = emptyList(),
                    feedStanding = { ProposalStanding.Expired },
                    signalChoice = { emptyList() },
                    onSendAgain = {},
                    onCheckStatus = {},
                    onBack = {},
                    clock = clock,
                    positions = state,
                    wallet = wallet(),
                    positionLinks = { links },
                    onRefreshPosition = { refreshed += it },
                    onSellPosition = { sold += it },
                    now = { now },
                    retained = retained,
                )
            }
        }
        compose.onNodeWithText(RetainedCopy.Note).assertExists()
        compose.onNodeWithTag(HistoryDetailTags.PositionSell).performScrollTo().performClick()
        assertEquals(listOf(POSITION_PUBKEY), sold)
        // Opening it reads the position, as for any item.
        assertTrue(POSITION_PUBKEY in refreshed)
    }

    @Test
    fun withoutAnyRetainedLinkTheItemIsGoneAsBefore() {
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                HistoryDetailRoute(
                    identity = ReviewIdentity.Signal(CONNECTION, PROPOSAL),
                    connections = emptyList(),
                    inboxState = InboxUiState(),
                    feedRecords = emptyList(),
                    feedStanding = { ProposalStanding.Expired },
                    signalChoice = { emptyList() },
                    onSendAgain = {},
                    onCheckStatus = {},
                    onBack = {},
                    positions = PositionsState(loaded = true),
                )
            }
        }
        assertNotNull(compose.onNodeWithTag(HistoryDetailRouteTags.Gone).assertExists())
    }

    private companion object {
        const val CONNECTION = "5b1f4b3a-6a5f-4f5c-9d6c-0f2f1e2d3c4b"
        const val PROPOSAL = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    }
}
