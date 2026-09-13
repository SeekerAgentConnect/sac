package io.github.brrenat.seekervault.inbox

import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.ui.SeekerVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sheet that rises while the owner's wallet is being asked (SEE-57).
 *
 * The whole point of it is to make plain that the app has stepped back and another app is deciding.
 * So the one thing it must never have is a control that looks like the wallet's own: a Sign button
 * here would do nothing, and would teach the owner that signing is confirmed in this app. It names
 * who was asked, what they were asked for, and that what happens next is theirs.
 */
@RunWith(AndroidJUnit4::class)
class WalletHandoffTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun show(signsAndSends: Boolean) = compose.setContent {
        SeekerVaultTheme { WalletHandoffSheet(signsAndSends = signsAndSends, wallet = WALLET) }
    }

    @Test
    fun saysWhoIsBeingAskedAndForWhat() {
        show(signsAndSends = true)
        compose
            .onNodeWithTag(InboxTags.HANDOFF)
            .assertTextContains(context.getString(R.string.handoff_title_send), substring = true)
            .assertTextContains(context.getString(R.string.handoff_other_app), substring = true)
            .assertTextContains(WALLET, substring = true)
        compose.onNodeWithText(context.getString(R.string.handoff_keys)).assertExists()
    }

    @Test
    fun aSignatureIsNotDescribedAsAPayment() {
        show(signsAndSends = false)
        compose
            .onNodeWithTag(InboxTags.HANDOFF)
            .assertTextContains(context.getString(R.string.handoff_title_sign), substring = true)
    }

    @Test
    fun itOffersNothingToTapBecauseTheAnswerIsGivenInTheOtherApp() {
        show(signsAndSends = true)
        assertEquals(
            0,
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size,
        )
    }

    private companion object {
        const val WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
    }
}
