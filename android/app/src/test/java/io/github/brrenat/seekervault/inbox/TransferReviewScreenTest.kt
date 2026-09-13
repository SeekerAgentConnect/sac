package io.github.brrenat.seekervault.inbox

import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.inbox.PendingRequestsScreenTest.Companion.HOME
import io.github.brrenat.seekervault.inbox.PendingRequestsScreenTest.Companion.NOW
import io.github.brrenat.seekervault.policy.PolicyAction
import io.github.brrenat.seekervault.policy.PolicyCheck
import io.github.brrenat.seekervault.policy.PolicyCheckResult
import io.github.brrenat.seekervault.policy.PolicyDecision
import io.github.brrenat.seekervault.policy.PolicyReason
import io.github.brrenat.seekervault.policy.RequestFacts
import io.github.brrenat.seekervault.policy.assess
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Asset
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.action
import io.github.brrenat.seekervault.request.v1.actionRequest
import io.github.brrenat.seekervault.request.v1.asset
import io.github.brrenat.seekervault.request.v1.preparedTransaction
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.request.v1.transferAction
import io.github.brrenat.seekervault.transactions.inspectTransfer
import io.github.brrenat.seekervault.ui.SeekerVaultTheme
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The review screen for a transfer (SAW-020, SAW-021): what the owner is shown comes from the
 * transaction, the server's numbers are labelled as the server's, the agent's note sits apart from
 * both, and Approve is offered only for a transaction this phone read whole.
 */
@RunWith(AndroidJUnit4::class)
class TransferReviewScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var prepareAgain = 0
    private var approvals = 0
    private val ticks = mutableListOf<Boolean>()

    private val cases =
        JSONObject(
                checkNotNull(javaClass.getResourceAsStream("/transactions/cases.json")).use {
                    it.readBytes().decodeToString()
                }
            )
            .getJSONArray("cases")

    private fun case(name: String) =
        (0 until cases.length())
            .map { cases.getJSONObject(it) }
            .first { it.getString("name") == name }

    private fun requestOf(case: JSONObject): ActionRequest {
        val fields = case.getJSONObject("request")
        return actionRequest {
            ref = requestRef {
                connectionId = "5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f"
                requestId = "f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19"
            }
            agentNote = if (fields.has("note")) fields.getString("note") else ""
            action = action {
                transfer = transferAction {
                    wallet = fields.getString("wallet")
                    network = Network.NETWORK_DEVNET
                    recipient = fields.getString("recipient")
                    amount = fields.getString("amount")
                    asset = asset {
                        if (fields.has("tokenMint")) tokenMint = fields.getString("tokenMint")
                        else nativeSol = Asset.NativeSol.getDefaultInstance()
                    }
                }
            }
        }
    }

    private fun readyFrom(case: JSONObject, rentLamports: Long = 0L): Preparation.Ready {
        val bytes = Base64.getDecoder().decode(case.getString("transaction"))
        val prepared = preparedTransaction {
            version = 1
            transaction = ByteString.copyFrom(bytes)
            contentHash = ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(bytes))
            feeLamports = 5_000L
            this.rentLamports = rentLamports
        }
        val wallet = walletFor(case)
        return Preparation.Ready(
            prepared,
            inspectTransfer(requestOf(case), prepared, wallet),
            wallet,
        )
    }

    private fun walletFor(case: JSONObject) =
        SelectedWallet(
            address = case.getJSONObject("request").getString("wallet"),
            network = WalletNetwork.Devnet,
            selectedAt = Instant.parse("2026-09-12T12:00:00Z"),
        )

    private fun show(
        case: JSONObject,
        preparation: Preparation?,
        wallet: SelectedWallet? = walletFor(case),
        decision: PolicyDecision? = null,
        acknowledged: Boolean = false,
    ) = compose.setContent {
        SeekerVaultTheme {
            RequestDetailsScreen(
                request = requestOf(case),
                source = HOME,
                result = null,
                sending = false,
                now = NOW,
                onAnswer = {},
                onApprove = {},
                onSendAgain = {},
                onBack = {},
                wallet = wallet,
                preparation = preparation,
                onPrepareAgain = { prepareAgain++ },
                onApproveTransfer = { approvals++ },
                assessment =
                    decision?.let {
                        RequestAssessment(
                            it,
                            RequestFacts.unread(HOME.id, PolicyAction.Transfer),
                            Instant.parse("2026-09-12T12:00:00Z"),
                        )
                    },
                acknowledged = acknowledged,
                onAcknowledge = { ticks += it },
            )
        }
    }

    /** Every check, in order, with [configured] in place of the ones nobody configured. */
    private fun checks(
        vararg configured: Pair<PolicyCheck, PolicyCheckResult>
    ): List<PolicyCheckResult> {
        val byCheck = configured.toMap()
        return PolicyCheck.entries.map { byCheck[it] ?: PolicyCheckResult.notConfigured(it) }
    }

    private fun overThreshold() =
        assess(
            checks(
                PolicyCheck.DailyLimit to
                    PolicyCheckResult.failed(
                        PolicyCheck.DailyLimit,
                        PolicyReason.OverDailyLimit,
                        "3 SOL of 2 SOL today",
                    )
            )
        )

    private fun field(name: String) = compose.onNodeWithTag(InboxTags.field(name))

    @Test
    fun showsTheAmountAndRecipientItReadOutOfTheTransaction() {
        show(case("sol_transfer"), readyFrom(case("sol_transfer")))
        compose
            .onNodeWithTag(InboxTags.TRANSFER_VERDICT)
            .assertTextEquals(context.getString(R.string.transfer_verdict_verified))
        // Both the readable amount and the base units the transaction actually carries.
        field("sends")
            .performScrollTo()
            .assertTextContains("2.5 SOL (2500000000 lamports)", substring = true)
        field("to")
            .performScrollTo()
            .assertTextContains(
                case("sol_transfer").getJSONObject("request").getString("recipient"),
                substring = true,
            )
        field("instructions").performScrollTo().assertTextContains("1 of 1", substring = true)
        compose
            .onNodeWithTag(InboxTags.TRANSFER_DERIVED)
            .performScrollTo()
            .assertTextEquals(context.getString(R.string.transfer_derived_here))
    }

    @Test
    fun showsTheTokenByItsMintAndTheAccountItGoesInto() {
        val case = case("token_transfer_creates_account")
        show(case, readyFrom(case, rentLamports = 2_039_280L))
        field("token")
            .performScrollTo()
            .assertTextContains(
                case.getJSONObject("request").getString("tokenMint"),
                substring = true,
            )
        field("tokenAccount").performScrollTo()
        field("creates")
            .performScrollTo()
            .assertTextContains(
                context.getString(R.string.transfer_creates_account),
                substring = true,
            )
        field("sends")
            .performScrollTo()
            .assertTextContains("(1500000 base units)", substring = true)
    }

    @Test
    fun keepsTheServersCostEstimateApartFromWhatItRead() {
        val case = case("token_transfer_creates_account")
        show(case, readyFrom(case, rentLamports = 2_039_280L))
        // The fee can't be read out of a transaction, so it is labelled as the server's number.
        field("estimate")
            .performScrollTo()
            .assertTextContains(
                context.getString(R.string.request_field_estimate),
                substring = true,
            )
        field("estimate").assertTextContains("0.000005", substring = true)
    }

    @Test
    fun saysPlainlyWhenTheTransactionDoesNotMatchTheRequest() {
        val case = case("changed_recipient")
        show(case, readyFrom(case))
        compose
            .onNodeWithTag(InboxTags.TRANSFER_VERDICT)
            .assertTextEquals(context.getString(R.string.transfer_verdict_invalid))
        compose.onNodeWithTag(InboxTags.TRANSFER_FINDINGS).performScrollTo()
        compose
            .onNodeWithText(context.getString(R.string.finding_recipient_mismatch))
            .assertExists()
        // And it shows where the money would really go, which is not where the request said.
        val paid = checkNotNull(readyFrom(case).inspection.facts).recipient
        assertEquals(false, paid == case.getJSONObject("request").getString("recipient"))
        field("to").performScrollTo().assertTextContains(checkNotNull(paid), substring = true)
    }

    @Test
    fun saysWhenItCouldNotReadTheWholeTransaction() {
        val case = case("unknown_program_alongside_the_transfer")
        show(case, readyFrom(case))
        compose
            .onNodeWithTag(InboxTags.TRANSFER_VERDICT)
            .assertTextEquals(context.getString(R.string.transfer_verdict_unverified))
        compose.onNodeWithTag(InboxTags.TRANSFER_FINDINGS).performScrollTo()
        compose.onNodeWithText(context.getString(R.string.finding_unrecognized)).assertExists()
        field("instructions").performScrollTo().assertTextContains("1 of 2", substring = true)
    }

    @Test
    fun keepsTheAgentsNoteApartFromTheFactsEvenWhenItNamesATicker() {
        val case = case("fake_ticker_in_the_note")
        show(case, readyFrom(case))
        // The note is shown under its own "not verified" label, and the token is a mint address.
        compose
            .onNodeWithTag(InboxTags.NOTE)
            .performScrollTo()
            .assertTextContains(context.getString(R.string.request_field_note), substring = true)
        field("token")
            .performScrollTo()
            .assertTextContains(
                case.getJSONObject("request").getString("tokenMint"),
                substring = true,
            )
    }

    @Test
    fun offersApprovalOnlyForATransactionItReadWholeAndFoundToMatch() {
        val case = case("sol_transfer")
        show(case, readyFrom(case))
        compose.onNodeWithTag(InboxTags.TRANSFER_APPROVE).performScrollTo().performClick()
        assertEquals(1, approvals)
    }

    @Test
    fun offersNoApprovalForATransactionThatDoesNotMatchTheRequest() {
        val case = case("changed_amount")
        show(case, readyFrom(case))
        // No button that would refuse: nothing this phone can't account for is put to a wallet.
        compose.onNodeWithTag(InboxTags.TRANSFER_APPROVE).assertDoesNotExist()
        compose
            .onNodeWithTag(InboxTags.TRANSFER_NOT_APPROVABLE)
            .performScrollTo()
            .assertTextEquals(context.getString(R.string.transfer_not_approvable))
    }

    @Test
    fun offersNoApprovalForATransactionItCouldNotReadWhole() {
        // Unverified, not invalid: the transfer itself matches, and an unread instruction still
        // stops it. A review with a gap in it is not a review.
        val case = case("unknown_program_alongside_the_transfer")
        show(case, readyFrom(case))
        compose
            .onNodeWithTag(InboxTags.TRANSFER_VERDICT)
            .assertTextEquals(context.getString(R.string.transfer_verdict_unverified))
        compose.onNodeWithTag(InboxTags.TRANSFER_APPROVE).assertDoesNotExist()
    }

    @Test
    fun showsEveryProgramTheTransactionCalls() {
        // What it would run, whether or not any rule was written about it: a request nothing
        // could be verified about still shows the programs it names (SAW-028).
        val case = case("token_transfer_creates_account")
        show(case, readyFrom(case, rentLamports = 2_039_280L))
        val programs = checkNotNull(readyFrom(case).inspection.facts).programs
        assertEquals(true, programs.size > 1)
        programs.forEach {
            field("programs").performScrollTo().assertTextContains(it, substring = true)
        }
    }

    @Test
    fun cannotApproveWithoutAWalletToPayWith() {
        val case = case("sol_transfer")
        show(case, readyFrom(case), wallet = null)
        compose.onNodeWithTag(InboxTags.TRANSFER_APPROVE).performScrollTo().assertIsNotEnabled()
        compose
            .onNodeWithTag(InboxTags.SIGNING_PROBLEM)
            .performScrollTo()
            .assertTextEquals(context.getString(R.string.transfer_no_wallet))
    }

    @Test
    fun aTransferOutsideTheRulesWaitsForTheOwnersWordBeforeItCanBeApproved() {
        val case = case("sol_transfer")
        show(case, readyFrom(case), decision = overThreshold())
        // The reasons are above the button, and the button says what it would be doing.
        compose
            .onNodeWithTag(InboxTags.policyCheck(PolicyCheck.DailyLimit))
            .performScrollTo()
            .assertTextContains("3 SOL of 2 SOL today", substring = true)
        compose
            .onNodeWithTag(InboxTags.TRANSFER_APPROVE)
            .performScrollTo()
            .assertIsNotEnabled()
            .assertTextEquals(context.getString(R.string.approve_and_send_despite_warnings))
        compose.onNodeWithTag(InboxTags.POLICY_ACKNOWLEDGE).performScrollTo().performClick()
        assertEquals(listOf(true), ticks)
        assertEquals(0, approvals)
    }

    @Test
    fun onceTheOwnerHasSaidSoTheTransferCanGoToTheWallet() {
        val case = case("sol_transfer")
        show(case, readyFrom(case), decision = overThreshold(), acknowledged = true)
        compose.onNodeWithTag(InboxTags.TRANSFER_APPROVE).performScrollTo().performClick()
        assertEquals(1, approvals)
    }

    @Test
    fun noRuleEverPutsBackTheApproveButtonInputValidationTookAway() {
        // A transaction that doesn't match its request is not an advisory warning to tick past:
        // there is no button, and no tick that would bring one back (SAW-020, SAW-028).
        val case = case("changed_amount")
        // The rules are as satisfied as rules get, and it makes no difference at all.
        val allowed =
            assess(
                checks(
                    PolicyCheck.Action to PolicyCheckResult.passed(PolicyCheck.Action, "transfer")
                )
            )
        show(case, readyFrom(case), decision = allowed, acknowledged = true)
        compose.onNodeWithTag(InboxTags.TRANSFER_APPROVE).assertDoesNotExist()
        compose.onNodeWithTag(InboxTags.POLICY_ACKNOWLEDGE).assertDoesNotExist()
        compose
            .onNodeWithTag(InboxTags.TRANSFER_NOT_APPROVABLE)
            .performScrollTo()
            .assertTextEquals(context.getString(R.string.transfer_not_approvable))
        // The phone's own verdict on the bytes stays what it is, and the rules stay beside it
        // rather than being read as the reason there is no button.
        compose
            .onNodeWithTag(InboxTags.TRANSFER_VERDICT)
            .performScrollTo()
            .assertTextEquals(context.getString(R.string.transfer_verdict_invalid))
        compose
            .onNodeWithTag(InboxTags.POLICY_VERDICT)
            .performScrollTo()
            .assertTextEquals(context.getString(R.string.policy_verdict_allowed))
    }

    @Test
    fun saysItIsReadingWhileThePreparationRuns() {
        show(case("sol_transfer"), Preparation.Running)
        compose
            .onNodeWithTag(InboxTags.TRANSFER_CHECKING)
            .assertTextEquals(context.getString(R.string.transfer_checking))
    }

    @Test
    fun offersToReadItAgainWhenTheTransactionCouldNotBeFetched() {
        show(case("sol_transfer"), Preparation.Failed(CheckOutcome.Unreachable))
        compose.onNodeWithTag(InboxTags.TRANSFER_FAILED).performScrollTo()
        compose.onNodeWithTag(InboxTags.TRANSFER_AGAIN).performScrollTo().performClick()
        assertEquals(1, prepareAgain)
    }
}
