package io.github.brrenat.seekervault.activity

import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.SeekerVaultTheme
import io.github.brrenat.seekervault.connections.RequestKey
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Activity list on Robolectric: what it shows, and what it does when it can't be read. */
@RunWith(AndroidJUnit4::class)
class ActivityScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<RequestKey>()
    private var refreshes = 0
    private var cleared = 0

    private fun show(state: ActivityUiState) = compose.setContent {
        SeekerVaultTheme {
            ActivityScreen(
                state = state,
                onOpen = { opened += it },
                onRefresh = { refreshes++ },
                onClear = { cleared++ },
                onBack = {},
            )
        }
    }

    @Test
    fun showsEachRecordWithItsSourceOperationAndOutcome() {
        val confirmed = record()
        show(ActivityUiState(listOf(confirmed), loaded = true))
        compose
            .onNodeWithTag(ActivityTags.item(confirmed))
            .assertTextContains("Hermes")
            .assertTextContains(RECIPIENT, substring = true)
            .assertTextContains(
                context.getString(R.string.activity_outcome_confirmed),
                substring = true,
            )
        compose.onNodeWithTag(ActivityTags.item(confirmed)).performClick()
        assertEquals(listOf(confirmed.key), opened)
    }

    @Test
    fun saysSoWhenThereIsNothingYet() {
        show(ActivityUiState(emptyList(), loaded = true))
        compose.onNodeWithTag(ActivityTags.EMPTY).assertExists()
        // With nothing to clear, there is nothing to offer clearing.
        compose.onNodeWithTag(ActivityTags.CLEAR).assertDoesNotExist()
    }

    @Test
    fun staysUsableWhenTheHistoryCannotBeRead() {
        val known = record()
        show(ActivityUiState(listOf(known), loaded = true, unreadable = true))
        compose.onNodeWithTag(ActivityTags.UNREADABLE).assertExists()
        // The records it has are still there, and still open.
        compose.onNodeWithTag(ActivityTags.item(known)).performClick()
        assertEquals(listOf(known.key), opened)
        compose.onNodeWithTag(ActivityTags.REFRESH).performClick()
        assertEquals(1, refreshes)
    }

    @Test
    fun clearsOnlyAfterTheOwnerConfirms() {
        show(ActivityUiState(listOf(record()), loaded = true))
        compose.onNodeWithTag(ActivityTags.CLEAR).performClick()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertEquals(0, cleared)
        compose.onNodeWithTag(ActivityTags.CLEAR).performClick()
        compose.onNodeWithTag(ActivityTags.CONFIRM_CLEAR).performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun keepsTheNewestFirstAsGiven() {
        val older = record(answeredAt = Instant.parse("2026-09-10T09:00:00Z"))
        val newer =
            record(
                requestId = OTHER_REQUEST,
                answeredAt = Instant.parse("2026-09-12T09:00:00Z"),
            )
        show(ActivityUiState(listOf(newer, older), loaded = true))
        compose.onNodeWithTag(ActivityTags.item(newer)).assertExists()
        compose.onNodeWithTag(ActivityTags.item(older)).assertExists()
    }
}
