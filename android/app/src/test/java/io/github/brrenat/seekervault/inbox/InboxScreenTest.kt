package io.github.brrenat.seekervault.inbox

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.designsystem.HistoryRowModel
import io.github.brrenat.seekervault.designsystem.HistoryRowState
import io.github.brrenat.seekervault.designsystem.InboxRowKind
import io.github.brrenat.seekervault.designsystem.InboxRowModel
import io.github.brrenat.seekervault.designsystem.InboxRowOrigin
import io.github.brrenat.seekervault.designsystem.InboxRowVerdict
import io.github.brrenat.seekervault.designsystem.InboxTab
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pendingShowsOnlyReviewAndDelegatesWithoutAnswering() {
        val actions = mutableListOf<String>()
        show(
            InboxScreenState(
                selectedTab = InboxTab.Pending,
                pending = listOf(pendingRow()),
                history = emptyList(),
            ),
            onTab = { actions += "tab/$it" },
            onReview = { actions += "review/$it" },
        )

        compose.onNodeWithText("Acknowledge a message").assertExists()
        compose.onAllNodesWithText("Review")[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Approve").assertDoesNotExist()
        compose.onNodeWithText("Reject").assertDoesNotExist()

        assertEquals(listOf("review/ack", "tab/History"), actions)
    }

    @Test
    fun pendingEmptyAndHistoryRowsUseTheirDesignedStates() {
        show(
            InboxScreenState(
                selectedTab = InboxTab.Pending,
                pending = emptyList(),
                history = emptyList(),
            )
        )
        compose.onNodeWithTag(InboxTags.EMPTY).assertTextContains(InboxCopy.EmptyPendingTitle)
        compose.onNodeWithText(InboxCopy.FooterCaption).assertDoesNotExist()
    }

    @Test
    fun historyRowsDelegateToTheExistingDetailsRoute() {
        val opened = mutableListOf<String>()
        show(
            InboxScreenState(
                selectedTab = InboxTab.History,
                pending = emptyList(),
                history =
                    listOf(
                        InboxHistoryRowState(
                            id = "private/server/request",
                            model =
                                HistoryRowModel(
                                    title = "Send 5 SOL",
                                    sourceName = "studio-mac",
                                    outcomeText = "Sent to the network",
                                    timestampText = "9:36 PM",
                                    isSignal = false,
                                ),
                            rowState = HistoryRowState.Sent,
                        )
                    ),
            ),
            onHistory = { opened += it },
        )

        compose.onNodeWithTag(InboxScreenTags.history("private/server/request")).performClick()
        assertEquals(listOf("private/server/request"), opened)
    }

    private fun show(
        state: InboxScreenState,
        onTab: (InboxTab) -> Unit = {},
        onReview: (String) -> Unit = {},
        onHistory: (String) -> Unit = {},
    ) = compose.setContent {
        SeekerTheme {
            InboxScreen(
                state = state,
                callbacks =
                    InboxScreenCallbacks(
                        onSelectTab = onTab,
                        onReview = onReview,
                        onOpenHistory = onHistory,
                        navigation = ScreenNavigationCallbacks({}, {}, {}, {}),
                    ),
            )
        }
    }

    private fun pendingRow() =
        InboxPendingRowState(
            id = "ack",
            model =
                InboxRowModel(
                    title = "Acknowledge a message",
                    supportingText = "“Still here?” · nothing is signed",
                    sourceName = "studio-mac",
                    timestampAndExpiryText = "9:39 PM · expires in 6 hours",
                    environmentText = "Production",
                    networkText = null,
                    warningCount = 0,
                ),
            kind = InboxRowKind.Acknowledgement,
            origin = InboxRowOrigin.Request,
            verdict = InboxRowVerdict.Ok,
        )
}
