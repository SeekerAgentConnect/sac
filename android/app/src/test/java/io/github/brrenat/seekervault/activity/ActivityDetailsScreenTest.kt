package io.github.brrenat.seekervault.activity

import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.SeekerVaultTheme
import io.github.brrenat.seekervault.request.v1.Network
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One record in full. The two things this screen must never get wrong: the cluster it names, and
 * whether the signature it shows is a payment.
 */
@RunWith(AndroidJUnit4::class)
class ActivityDetailsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<String>()

    private fun show(record: ActivityRecord, linkFailed: Boolean = false) = compose.setContent {
        SeekerVaultTheme {
            ActivityDetailsScreen(
                record = record,
                onOpenExplorer = { opened += it },
                linkFailed = linkFailed,
                onBack = {},
            )
        }
    }

    @Test
    fun showsTheAssessmentTheOwnerReadWhenTheyAnswered() {
        show(record(policy = reviewedPolicy()))
        val shown = compose.onNodeWithTag(ActivityTags.POLICY).performScrollTo()
        // The verdict, and that they went ahead past it: the record says both, in words.
        shown.assertTextContains(
            context.getString(
                R.string.activity_policy_anyway,
                context.getString(R.string.policy_verdict_restricted),
            ),
            substring = true,
        )
        shown.assertTextContains(
            context.getString(R.string.policy_reason_daily),
            substring = true,
        )
        // And what the assessment did not cover, so it is never read as covering everything.
        shown.assertTextContains(
            context.getString(R.string.policy_review_check_program),
            substring = true,
        )
    }

    @Test
    fun showsNoAssessmentForARecordWrittenBeforeThereWasOne() {
        show(record())
        compose.onNodeWithTag(ActivityTags.POLICY).assertDoesNotExist()
    }

    @Test
    fun saysSoRatherThanShowingACodeItHasNoNameFor() {
        // The record keeps codes on purpose. One from a later version is not shown as itself.
        show(record(policy = reviewedPolicy(assessment = "something_else")))
        compose
            .onNodeWithTag(ActivityTags.POLICY)
            .performScrollTo()
            .assertTextContains(
                context.getString(R.string.activity_policy_unknown),
                substring = true,
            )
    }

    @Test
    fun showsTheReviewedTransferItsClusterAndItsOutcome() {
        show(record(detail = "The transfer succeeded on chain in slot 298471553."))
        compose
            .onNodeWithTag(ActivityTags.OUTCOME)
            .assertTextContains(context.getString(R.string.activity_outcome_confirmed))
        compose
            .onNodeWithTag(ActivityTags.OPERATION)
            .assertTextContains(RECIPIENT, substring = true)
        compose
            .onNodeWithTag(ActivityTags.CLUSTER)
            .assertTextContains(
                context.getString(R.string.activity_cluster_devnet),
                substring = true,
            )
        compose.onNodeWithTag(ActivityTags.DETAIL).assertExists()
        compose
            .onNodeWithTag(ActivityTags.CHECKED_WITH)
            .assertTextContains("api.devnet.solana.com", substring = true)
    }

    @Test
    fun namesTheClusterOnEveryTransferAndNeverGuessesIt() {
        show(record(network = Network.NETWORK_MAINNET))
        compose
            .onNodeWithTag(ActivityTags.CLUSTER)
            .assertTextContains(
                context.getString(R.string.activity_cluster_mainnet),
                substring = true,
            )
    }

    @Test
    fun offersTheExplorerForASentTransactionOnlyItsOwnCluster() {
        val sent = record(network = Network.NETWORK_DEVNET)
        show(sent)
        compose.onNodeWithTag(ActivityTags.EXPLORER).performScrollTo().performClick()
        assertEquals(listOf(explorerUrl(sent)), opened)
        assertEquals(
            listOf("https://explorer.solana.com/tx/${sent.signature}?cluster=devnet"),
            opened,
        )
    }

    @Test
    fun neverCallsAMessageSignatureAPayment() {
        val signed =
            record(
                kind = ActivityKind.MessageSignature,
                outcome = ActivityOutcome.MessageSigned,
                checkedWith = null,
            )
        show(signed)
        // The signature is shown, labelled as a signature and not as a transaction ID.
        compose
            .onNodeWithTag(ActivityTags.SIGNATURE)
            .assertTextContains(
                context.getString(R.string.activity_field_message_signature),
                substring = true,
            )
        compose.onNodeWithTag(ActivityTags.NOT_A_PAYMENT).assertExists()
        // And there is nowhere to go: no explorer has it.
        compose.onNodeWithTag(ActivityTags.EXPLORER).assertDoesNotExist()
        compose.onNodeWithTag(ActivityTags.CLUSTER).assertDoesNotExist()
    }

    @Test
    fun offersNoExplorerForAnOutcomeNobodyKnows() {
        show(
            record(
                outcome = ActivityOutcome.Unknown,
                signature = null,
                checkedWith = null,
                detail = "This phone never learned whether the transaction was sent.",
            )
        )
        compose.onNodeWithTag(ActivityTags.EXPLORER).assertDoesNotExist()
        compose.onNodeWithTag(ActivityTags.SIGNATURE).assertDoesNotExist()
        compose.onNodeWithTag(ActivityTags.DETAIL).assertExists()
        // The cluster is still named: what was asked for is still part of the record.
        compose.onNodeWithTag(ActivityTags.CLUSTER).assertExists()
    }

    @Test
    fun saysSoWhenNothingOnThePhoneCanOpenTheLink() {
        show(record(), linkFailed = true)
        compose.onNodeWithText(context.getString(R.string.activity_link_failed)).assertExists()
        // And the record itself is unaffected: the signature is still there to copy.
        compose.onNodeWithTag(ActivityTags.SIGNATURE).assertExists()
    }
}
