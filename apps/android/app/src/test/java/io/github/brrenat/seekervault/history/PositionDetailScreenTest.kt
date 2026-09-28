package io.github.brrenat.seekervault.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.activity.connection
import io.github.brrenat.seekervault.activity.result
import io.github.brrenat.seekervault.activity.transferRequest
import io.github.brrenat.seekervault.designsystem.HistoryDetailCallbacks
import io.github.brrenat.seekervault.designsystem.HistoryDetailLink
import io.github.brrenat.seekervault.designsystem.HistoryDetailPosition
import io.github.brrenat.seekervault.designsystem.HistoryDetailPositionState
import io.github.brrenat.seekervault.designsystem.HistoryDetailRow
import io.github.brrenat.seekervault.designsystem.HistoryDetailScreen
import io.github.brrenat.seekervault.designsystem.HistoryDetailSell
import io.github.brrenat.seekervault.designsystem.HistoryDetailTags
import io.github.brrenat.seekervault.designsystem.PositionSaleSheet
import io.github.brrenat.seekervault.designsystem.PositionSaleSheetState
import io.github.brrenat.seekervault.designsystem.PositionSaleSheetTags
import io.github.brrenat.seekervault.designsystem.PositionSaleVerdict
import io.github.brrenat.seekervault.designsystem.TermsCardRow
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The position block's actions and the sale sheet's one decision (SEE-172). */
@RunWith(AndroidJUnit4::class)
class PositionDetailScreenTest {
    @get:Rule val compose = createComposeRule()

    private val base =
        privateHistoryDetail(
            result(transferRequest()),
            connection("Trader Signals"),
            HistoryDetailClock(ZoneOffset.UTC, Locale.US),
        )

    private fun position(sell: HistoryDetailSell?) =
        HistoryDetailPosition(
            state = HistoryDetailPositionState.Live,
            stateText = "Live · 8:00 AM",
            scope = PositionCopy.Scope,
            rows = listOf(HistoryDetailRow("Contracts held", "63.55")),
            sell = sell,
            links =
                listOf(HistoryDetailLink("Your positions on Jupiter", "https://example.test/p")),
        )

    @Test
    fun refreshSellAndTheProviderLinkAreTheBlocksOnlyActions() {
        val taps = mutableListOf<String>()
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                HistoryDetailScreen(
                    model = base.copy(position = position(HistoryDetailSell(enabled = true))),
                    callbacks =
                        HistoryDetailCallbacks(
                            onBack = {},
                            onRefreshPosition = { taps += "refresh" },
                            onSellPosition = { taps += "sell" },
                            onOpenProvider = { taps += "open $it" },
                        ),
                )
            }
        }
        compose.onNodeWithTag(HistoryDetailTags.PositionRefresh).performScrollTo().performClick()
        compose.onNodeWithTag(HistoryDetailTags.PositionSell).performScrollTo().performClick()
        compose.onNodeWithTag(HistoryDetailTags.positionLink(0)).performScrollTo().performClick()
        assertEquals(listOf("refresh", "sell", "open https://example.test/p"), taps)
        compose.onNodeWithText(PositionCopy.Scope).assertExists()
    }

    @Test
    fun anUnavailableSaleSaysWhyAndCannotBeTapped() {
        val taps = mutableListOf<String>()
        val reason = "Connect the wallet that bought this position to sell it."
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                HistoryDetailScreen(
                    model = base.copy(position = position(HistoryDetailSell(false, reason))),
                    callbacks =
                        HistoryDetailCallbacks(onBack = {}, onSellPosition = { taps += "sell" }),
                )
            }
        }
        compose.onNodeWithTag(HistoryDetailTags.PositionSell).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(HistoryDetailTags.PositionSell).performClick()
        compose.onNodeWithTag(HistoryDetailTags.PositionSellReason).assertExists()
        compose.onNodeWithText(reason).assertExists()
        assertEquals(emptyList<String>(), taps)
    }

    @Test
    fun theSheetSellsOnlyAVerifiedReview() {
        val taps = mutableListOf<String>()
        var sheet by mutableStateOf(sheet(PositionSaleVerdict.Refused, enabled = false))
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                PositionSaleSheet(
                    state = sheet,
                    onSell = { taps += "sell" },
                    onCancel = { taps += "cancel" },
                    onPrepareAgain = { taps += "again" },
                )
            }
        }
        compose.onNodeWithTag(PositionSaleSheetTags.Sell).assertIsNotEnabled()
        compose.onNodeWithTag(PositionSaleSheetTags.Sell).performClick()
        compose.onNodeWithTag(PositionSaleSheetTags.Findings).assertExists()
        assertEquals(emptyList<String>(), taps)

        sheet = sheet(PositionSaleVerdict.Verified, enabled = true)
        compose.onNodeWithTag(PositionSaleSheetTags.Scope).assertExists()
        compose.onNodeWithTag(PositionSaleSheetTags.Sell).performClick()
        compose.onNodeWithTag(PositionSaleSheetTags.Cancel).performClick()
        assertEquals(listOf("sell", "cancel"), taps)
    }

    private fun sheet(verdict: PositionSaleVerdict, enabled: Boolean) =
        PositionSaleSheetState(
            headline = "Sell your Yes position",
            subline = "Georgia vs. Ukraine · Ukraine",
            scope = "This sells your wallet's entire current position in this outcome.",
            verdict = verdict,
            verdictText =
                if (enabled) "Every check passed." else "This transaction can't be approved.",
            terms = listOf(TermsCardRow("Sells", "63.55 Yes contracts · all of them")),
            findings =
                if (enabled) emptyList() else listOf("The lowest price is far below the bid."),
            primaryEnabled = enabled,
        )
}
