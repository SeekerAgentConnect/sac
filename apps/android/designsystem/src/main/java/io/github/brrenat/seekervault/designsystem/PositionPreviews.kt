package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

/*
 * SEE-172's principal states: the original prediction purchase with its live position, a sale in
 * flight, the sold outcome, a position that can't be followed, and the sale review verified and
 * refused. Composed only of the guide's existing pieces; there is no Claude Design export for the
 * position block yet, so these are baselines of the implementation, not references.
 */

private const val PositionDarkMode = Configuration.UI_MODE_NIGHT_YES

internal object PositionFixtures {
    private val purchase =
        HistoryDetailModel(
            header =
                HistoryDetailHeader(
                    origin = HistoryDetailOrigin.Signal,
                    sourceName = "Trader Signals",
                    sourceColour = SourceColour.Sky,
                    title = "Buy Yes · Georgia vs. Ukraine",
                    environment = HistoryDetailEnvironment.Production,
                    networkText = "Mainnet",
                ),
            status =
                HistoryDetailStatusModel(
                    status = HistoryDetailStatus.Approved,
                    explanation = "You approved on this phone. Seed Vault Wallet signed it.",
                ),
            response =
                HistoryDetailResponse.Sent(
                    timestampText = "Sep 28, 7:50:12 AM",
                    rows =
                        listOf(
                            HistoryDetailRow("Decision", "Approved"),
                            HistoryDetailRow("Side", "Yes"),
                            HistoryDetailRow("Stake", "22.88 USDC"),
                        ),
                ),
            execution =
                HistoryDetailExecution(
                    state = HistoryDetailExecutionState.Confirmed,
                    title = "Confirmed on Mainnet",
                    body =
                        "The order's transaction succeeded; that doesn't mean the order filled " +
                            "or the market settled.",
                ),
            footnote = "Production · this record stays on this phone.",
        )

    private val links =
        listOf(
            HistoryDetailLink("Your positions on Jupiter", "https://example.test/portfolio"),
            HistoryDetailLink("This market on Jupiter", "https://example.test/market"),
        )

    private val order =
        listOf(
            HistoryDetailRow("Fill", "Partly filled, rest returned · 63.55 of 65.66"),
            HistoryDetailRow("Average price", "$0.36 a contract"),
            HistoryDetailRow("Reported", "8:00 AM"),
        )

    private val rows =
        listOf(
            HistoryDetailRow("Market", "Georgia vs. Ukraine · Ukraine"),
            HistoryDetailRow("Outcome", "Yes"),
            HistoryDetailRow("Contracts held", "63.55"),
            HistoryDetailRow("Value now", "$22.24"),
            HistoryDetailRow("Best bid", "$0.35 a contract"),
            HistoryDetailRow("Cost basis", "$22.87"),
            HistoryDetailRow("P&L", "−$0.63"),
            HistoryDetailRow("Market status", "open"),
        )

    private val scope =
        "Your wallet's whole position in this outcome. It includes anything bought for this side " +
            "elsewhere, not only this signal's stake."

    val live =
        purchase.copy(
            position =
                HistoryDetailPosition(
                    state = HistoryDetailPositionState.Live,
                    stateText = "Live · 8:00 AM",
                    scope = scope,
                    orderRows = order,
                    rows = rows,
                    sell = HistoryDetailSell(enabled = true),
                    links = links,
                )
        )

    val pendingSale =
        purchase.copy(
            position =
                HistoryDetailPosition(
                    state = HistoryDetailPositionState.Live,
                    stateText = "Live · 8:02 AM",
                    scope = scope,
                    orderRows = order,
                    rows = rows,
                    refreshing = true,
                    sell =
                        HistoryDetailSell(
                            enabled = false,
                            reason = "A sale of this position is still being settled.",
                        ),
                    links = links,
                    sales =
                        listOf(
                            HistoryDetailSale(
                                title = "Sale · Sep 28, 8:01:40 AM",
                                state = HistoryDetailSaleState.Pending,
                                stateText = "Sent · waiting to fill",
                                rows =
                                    listOf(
                                        HistoryDetailRow("Contracts", "63.55"),
                                        HistoryDetailRow(
                                            "Lowest price accepted",
                                            "$0.27 a contract",
                                        ),
                                        HistoryDetailRow("Proceeds", "Known after it fills"),
                                    ),
                            )
                        ),
                ),
            transactions =
                listOf(
                    HistoryDetailTransaction(
                        label = "Sell position",
                        signature =
                            "4nyh2EyM8enNQiUniwEFKv5SNcysRxr8okLP2Gy8U1nviz4UY3eWtXpbmcoTNCyq",
                        status = HistoryDetailTransactionStatus.Pending,
                        explorerLabel = "View on explorer · Mainnet",
                        explorerUrl = "https://example.test/tx",
                    )
                ),
        )

    val sold =
        purchase.copy(
            position =
                HistoryDetailPosition(
                    state = HistoryDetailPositionState.Closed,
                    stateText = "Sold",
                    orderRows = order,
                    links = links,
                    sales =
                        listOf(
                            HistoryDetailSale(
                                title = "Sale · Sep 28, 8:01:40 AM",
                                state = HistoryDetailSaleState.Done,
                                stateText = "Sold",
                                rows =
                                    listOf(
                                        HistoryDetailRow("Contracts", "63.55"),
                                        HistoryDetailRow(
                                            "Lowest price accepted",
                                            "$0.27 a contract",
                                        ),
                                        HistoryDetailRow("Sold", "63.55 contracts"),
                                        HistoryDetailRow("Proceeds", "20.7 JupUSD"),
                                        HistoryDetailRow("Fees", "0.9 JupUSD"),
                                    ),
                            )
                        ),
                )
        )

    val unavailable =
        purchase.copy(
            position =
                HistoryDetailPosition(
                    state = HistoryDetailPositionState.Unavailable,
                    stateText = "Not found yet",
                    note =
                        "Jupiter has no record of this position yet. A new fill can take a " +
                            "moment to be indexed; this doesn't mean it was sold or lost.",
                    links = links,
                )
        )

    private val saleTerms =
        listOf(
            TermsCardRow("Sells", "63.55 Yes contracts · all of them"),
            TermsCardRow("Lowest price accepted", "$0.27 a contract"),
            TermsCardRow("You receive at least", "17.1585 JupUSD before fees"),
        )

    private val saleFacts =
        listOf(
            PositionSaleFact(
                "Estimated proceeds",
                "≈ 22.2425 JupUSD",
            ),
            PositionSaleFact("Estimated fees", "≈ 2.89152 JupUSD"),
            PositionSaleFact("Paid out in", "JupUSD"),
            PositionSaleFact("Proceeds go to", "5XzL1pNX…ho9bPaXd", mono = true),
            PositionSaleFact("Wallet", "nFY4Bcnf…JDt7ith", mono = true),
            PositionSaleFact("Network", "Mainnet"),
        )

    private val saleScope =
        "This sells your wallet's entire current position in this outcome — 63.55 contracts, " +
            "including any bought through other signals or outside this app — not only this " +
            "signal's stake."

    val saleVerified =
        PositionSaleSheetState(
            headline = "Sell your Yes position",
            subline = "Georgia vs. Ukraine · Ukraine",
            scope = saleScope,
            verdict = PositionSaleVerdict.Verified,
            verdictText = "Every check on this transaction's bytes passed.",
            terms = saleTerms,
            facts = saleFacts,
            expiry =
                "This review stands until 8:02:40 AM. Estimates are the provider's and not " +
                    "guaranteed; the lowest price accepted is what the transaction enforces.",
            primaryEnabled = true,
        )

    val saleRefused =
        saleVerified.copy(
            verdict = PositionSaleVerdict.Refused,
            verdictText = "This transaction can't be approved.",
            findings =
                listOf(
                    "The lowest price this sale accepts is missing, or far below what the market " +
                        "pays now."
                ),
            primaryEnabled = false,
        )
}

@Composable
private fun PositionBodyPreview(model: HistoryDetailModel) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0, modifier = Modifier.fillMaxSize()) {
            HistoryDetailBody(
                model = model,
                callbacks = HistoryDetailCallbacks(onBack = {}),
                modifier =
                    Modifier.fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(SeekerTheme.spacing.xl),
            )
        }
    }
}

@Composable
private fun PositionSalePreview(state: PositionSaleSheetState) {
    SeekerTheme(darkTheme = true) {
        PositionSaleSheet(state = state, onSell = {}, onCancel = {}, onPrepareAgain = {})
    }
}

@DesignRef(component = "history-detail", variant = "body=position-live")
@Preview(
    name = "history-detail/body-position-live",
    widthDp = 390,
    heightDp = 1800,
    uiMode = PositionDarkMode,
)
@Composable
internal fun HistoryDetailPositionLivePreview() = PositionBodyPreview(PositionFixtures.live)

@DesignRef(component = "history-detail", variant = "body=position-pending-sale")
@Preview(
    name = "history-detail/body-position-pending-sale",
    widthDp = 390,
    heightDp = 2000,
    uiMode = PositionDarkMode,
)
@Composable
internal fun HistoryDetailPositionPendingSalePreview() =
    PositionBodyPreview(PositionFixtures.pendingSale)

@DesignRef(component = "history-detail", variant = "body=position-sold")
@Preview(
    name = "history-detail/body-position-sold",
    widthDp = 390,
    heightDp = 1400,
    uiMode = PositionDarkMode,
)
@Composable
internal fun HistoryDetailPositionSoldPreview() = PositionBodyPreview(PositionFixtures.sold)

@DesignRef(component = "history-detail", variant = "body=position-unavailable")
@Preview(
    name = "history-detail/body-position-unavailable",
    widthDp = 390,
    heightDp = 1100,
    uiMode = PositionDarkMode,
)
@Composable
internal fun HistoryDetailPositionUnavailablePreview() =
    PositionBodyPreview(PositionFixtures.unavailable)

@DesignRef(component = "screens", variant = "sheet-position-sale")
@Preview(name = "screens/sheet-position-sale", widthDp = 390, uiMode = PositionDarkMode)
@Composable
internal fun PositionSaleVerifiedPreview() = PositionSalePreview(PositionFixtures.saleVerified)

@DesignRef(component = "screens", variant = "sheet-position-sale-refused")
@Preview(name = "screens/sheet-position-sale-refused", widthDp = 390, uiMode = PositionDarkMode)
@Composable
internal fun PositionSaleRefusedPreview() = PositionSalePreview(PositionFixtures.saleRefused)
