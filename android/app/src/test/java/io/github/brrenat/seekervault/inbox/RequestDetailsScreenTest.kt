package io.github.brrenat.seekervault.inbox

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
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
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.connections.SigningOutcome
import io.github.brrenat.seekervault.inbox.PendingRequestsScreenTest.Companion.HOME
import io.github.brrenat.seekervault.inbox.PendingRequestsScreenTest.Companion.NOW
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Asset
import io.github.brrenat.seekervault.request.v1.ConfirmationLevel
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.action
import io.github.brrenat.seekervault.request.v1.asset
import io.github.brrenat.seekervault.request.v1.confirmation
import io.github.brrenat.seekervault.request.v1.outcome
import io.github.brrenat.seekervault.request.v1.signMessageAction
import io.github.brrenat.seekervault.request.v1.transferAction
import io.github.brrenat.seekervault.ui.SeekerVaultTheme
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Request details on Robolectric: the answer buttons, progress, and the stored outcome. */
@RunWith(AndroidJUnit4::class)
class RequestDetailsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val answers = mutableListOf<Answer>()
    private var sentAgain = 0
    private var approvals = 0
    private var checks = 0

    private fun show(
        request: ActionRequest = REQUEST,
        result: LocalResult? = null,
        sending: Boolean = false,
        wallet: SelectedWallet? = null,
        signingProblem: SigningProblem? = null,
        checking: Boolean = false,
    ) = compose.setContent {
        SeekerVaultTheme {
            RequestDetailsScreen(
                request = request,
                source = HOME,
                result = result,
                sending = sending,
                now = NOW,
                onAnswer = { answers += it },
                onApprove = { approvals++ },
                onSendAgain = { sentAgain++ },
                onBack = {},
                wallet = wallet,
                signingProblem = signingProblem,
                checking = checking,
                onCheckStatus = { checks++ },
            )
        }
    }

    private fun status(id: Int, vararg args: Any) =
        compose.onNodeWithTag(InboxTags.STATUS).assertTextEquals(context.getString(id, *args))

    @Test
    fun showsThePendingRequestAndOffersAcknowledgeAndReject() {
        show()
        status(R.string.status_waiting_for_you)
        compose
            .onNodeWithTag(InboxTags.field("from"))
            .assertTextContains("Home Mac (mac.tailnet.ts.net)")
        // The text's own node: its list item merges it with the field's label.
        compose.onNodeWithTag(InboxTags.MESSAGE, useUnmergedTree = true).assertTextEquals(TEXT)
        compose.onNodeWithTag(InboxTags.NOTE).assertTextContains("Nightly release")
        compose
            .onNodeWithTag(InboxTags.field("requestId"))
            .assertTextContains(REQUEST.ref.requestId)
        compose.onNodeWithTag(InboxTags.ACKNOWLEDGE).performScrollTo().performClick()
        compose.onNodeWithTag(InboxTags.REJECT).performScrollTo().performClick()
        assertEquals(listOf(Answer.Acknowledge, Answer.Reject), answers)
    }

    @Test
    fun disablesTheButtonsWhileTheAnswerIsSent() {
        show(sending = true)
        status(R.string.status_sending)
        compose.onNodeWithTag(InboxTags.SENDING).assertExists()
        compose.onNodeWithTag(InboxTags.ACKNOWLEDGE).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(InboxTags.REJECT).assertIsNotEnabled()
    }

    @Test
    fun showsTheStoredOutcomeInsteadOfTheButtons() {
        val done = REQUEST.toBuilder().setState(RequestState.REQUEST_STATE_COMPLETED).build()
        show(done, result(done, Delivery.Accepted))
        status(R.string.status_acknowledged)
        compose.onNodeWithTag(InboxTags.ACKNOWLEDGE).assertDoesNotExist()
        compose.onNodeWithTag(InboxTags.REJECT).assertDoesNotExist()
        compose.onNodeWithTag(InboxTags.SEND_AGAIN).assertDoesNotExist()
    }

    @Test
    fun offersToSendAWaitingAnswerAgain() {
        show(
            result = result(REQUEST, Delivery.Waiting).copy(lastFailure = CheckOutcome.Unreachable)
        )
        status(R.string.status_to_send_acknowledged)
        compose
            .onNodeWithText(
                context.getString(
                    R.string.status_last_failure,
                    context.getString(R.string.connection_status_unreachable),
                )
            )
            .assertExists()
        compose.onNodeWithTag(InboxTags.ACKNOWLEDGE).assertDoesNotExist()
        compose.onNodeWithTag(InboxTags.SEND_AGAIN).performScrollTo().performClick()
        assertEquals(1, sentAgain)
    }

    @Test
    fun saysWhenTheAgentCancelledFirst() {
        val cancelled = REQUEST.toBuilder().setState(RequestState.REQUEST_STATE_CANCELLED).build()
        show(cancelled, result(cancelled, Delivery.Superseded))
        status(R.string.status_superseded_cancelled)
    }

    @Test
    fun offersNothingForAnExpiredRequest() {
        show(FakeConnectionGateway.request(HOME.id, text = TEXT, expiresAt = NOW.minusSeconds(1)))
        status(R.string.status_expired)
        compose.onNodeWithTag(InboxTags.ACKNOWLEDGE).assertDoesNotExist()
    }

    @Test
    fun saysWhenAnAnswerCantBeSent() {
        show(result = result(REQUEST, Delivery.Undeliverable))
        status(R.string.status_undeliverable)
        compose.onNodeWithTag(InboxTags.SEND_AGAIN).assertDoesNotExist()
    }

    @Test
    fun showsTheWholeMessageTheWalletWouldSignAndWhoWouldSignIt() {
        show(MESSAGE, wallet = SELECTED)
        compose
            .onNodeWithTag(InboxTags.MESSAGE, useUnmergedTree = true)
            .assertTextEquals("Sign in to Example\u240D\u240A\nNonce: 7\u2409x")
        compose
            .onNodeWithTag(InboxTags.field("encoding"))
            .assertTextContains(
                context.resources.getQuantityString(R.plurals.message_text_bytes, 30, 30)
            )
        compose
            .onNodeWithTag(InboxTags.field("signsWith"))
            .assertTextContains(
                context.getString(
                    R.string.signing_wallet,
                    WALLET,
                    context.getString(R.string.wallet_network_devnet),
                )
            )
        // Invisible characters are called out, and a signature is never a payment.
        compose.onNodeWithTag(InboxTags.HIDDEN).assertExists()
        compose.onNodeWithTag(InboxTags.NOT_A_PAYMENT).assertExists()
        // Approve is the only way to the wallet, and Acknowledge doesn't apply to a message.
        compose.onNodeWithTag(InboxTags.ACKNOWLEDGE).assertDoesNotExist()
        compose.onNodeWithTag(InboxTags.APPROVE).performScrollTo().performClick()
        compose.onNodeWithTag(InboxTags.REJECT).performScrollTo().performClick()
        assertEquals(1, approvals)
        assertEquals(listOf(Answer.Reject), answers)
    }

    @Test
    fun saysToConnectAWalletBeforeApproving() {
        show(MESSAGE)
        compose.onNodeWithTag(InboxTags.SIGNING_PROBLEM).assertExists()
        compose.onNodeWithTag(InboxTags.APPROVE).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(InboxTags.REJECT).assertIsEnabled()
    }

    @Test
    fun saysWhenTheWalletChangedWhileTheOwnerWasReviewing() {
        show(MESSAGE, wallet = SELECTED, signingProblem = SigningProblem.Changed)
        compose
            .onNodeWithTag(InboxTags.SIGNING_PROBLEM)
            .assertTextEquals(context.getString(R.string.problem_wallet_changed))
    }

    private fun showApproved(outcome: SigningOutcome?) =
        show(
            MESSAGE,
            result(MESSAGE, Delivery.Waiting)
                .copy(answer = Answer.Approve, approved = true, signing = outcome),
            wallet = SELECTED,
        )

    @Test
    fun saysItIsWaitingForTheWalletAfterAnApproval() {
        showApproved(null)
        status(R.string.status_waiting_for_wallet)
        // Nothing can be approved twice.
        compose.onNodeWithTag(InboxTags.APPROVE).assertDoesNotExist()
    }

    @Test
    fun saysTheWalletSignedAndTheSignatureIsOnItsWay() {
        showApproved(SigningOutcome.Signed(ByteString.copyFrom(ByteArray(64) { 1 })))
        status(R.string.status_to_send_signed)
        compose.onNodeWithTag(InboxTags.SEND_AGAIN).performScrollTo().performClick()
        assertEquals(1, sentAgain)
    }

    @Test
    fun saysTheOwnerDeclinedInTheWallet() {
        showApproved(SigningOutcome.Declined)
        status(R.string.status_to_send_declined_in_wallet)
    }

    @Test
    fun saysNothingWasSignedAndWhy() {
        showApproved(SigningOutcome.Failed("The wallet is locked."))
        status(R.string.status_not_signed, "The wallet is locked.")
    }

    @Test
    fun saysThePhoneNeverLearnedWhatTheWalletDid() {
        showApproved(SigningOutcome.Unresolved("The app closed before the wallet answered."))
        status(R.string.status_unresolved, "The app closed before the wallet answered.")
        // It never says signed, and it offers nothing that would ask the wallet again.
        compose.onNodeWithTag(InboxTags.APPROVE).assertDoesNotExist()
    }

    // --- Following a sent transaction to the chain (SAW-022) ---

    /** A transfer the wallet sent, as the phone stores it once the server has taken the result. */
    private fun sent(state: RequestState, detail: String = "", endpoint: String = "") =
        LocalResult(
            HOME.id,
            TRANSFER.ref.requestId,
            Answer.Approve,
            NOW,
            TRANSFER.toBuilder()
                .setState(state)
                .setOutcome(
                    outcome {
                        signature = ByteString.copyFrom(ByteArray(64) { 4 })
                        this.detail = detail
                        if (endpoint.isNotEmpty()) {
                            confirmation = confirmation {
                                this.endpoint = endpoint
                                level = ConfirmationLevel.CONFIRMATION_LEVEL_FINALIZED
                                matchesApproval = true
                                this.detail = "It succeeded on chain in slot 4242."
                            }
                        }
                    }
                )
                .build(),
            Delivery.Accepted,
            approved = true,
            signing = SigningOutcome.Sent(ByteString.copyFrom(ByteArray(64) { 4 })),
        )

    @Test
    fun saysATransactionIsSentAndNotConfirmedYet() {
        show(TRANSFER, sent(RequestState.REQUEST_STATE_SUBMITTED))
        compose
            .onNodeWithTag(InboxTags.STATUS)
            .assertTextContains("not been confirmed", substring = true)
        // And the owner can ask, without anything going near the wallet.
        compose.onNodeWithTag(InboxTags.CHECK_STATUS).performScrollTo().performClick()
        assertEquals(1, checks)
    }

    @Test
    fun saysWhoCheckedAConfirmedTransferAndOffersNoFurtherCheck() {
        show(
            TRANSFER,
            sent(
                RequestState.REQUEST_STATE_CONFIRMED,
                detail = "The transfer succeeded on chain in slot 4242.",
                endpoint = "rpc.example.test",
            ),
        )
        compose
            .onNodeWithTag(InboxTags.STATUS)
            .assertTextContains("went through on the network", substring = true)
        // Whose word it is, said plainly: there is no second opinion behind it.
        compose
            .onNodeWithTag(InboxTags.CONFIRMATION)
            .performScrollTo()
            .assertTextContains("rpc.example.test", substring = true)
        compose
            .onNodeWithTag(InboxTags.CONFIRMATION)
            .assertTextContains(context.getString(R.string.confirmation_trust), substring = true)
        compose.onNodeWithTag(InboxTags.CHECK_STATUS).assertDoesNotExist()
    }

    @Test
    fun saysWhyATransactionFailedOnTheNetwork() {
        show(
            TRANSFER,
            sent(
                RequestState.REQUEST_STATE_FAILED,
                detail = "The transaction ran on chain and failed: insufficient funds.",
                endpoint = "rpc.example.test",
            ),
        )
        compose
            .onNodeWithTag(InboxTags.STATUS)
            .assertTextContains("insufficient funds", substring = true)
        compose.onNodeWithTag(InboxTags.CHECK_STATUS).assertDoesNotExist()
    }

    @Test
    fun saysTheServerHasNotLookedYetRatherThanNothing() {
        show(TRANSFER, sent(RequestState.REQUEST_STATE_SUBMITTED))
        compose
            .onNodeWithTag(InboxTags.CONFIRMATION)
            .performScrollTo()
            .assertTextContains(
                context.getString(R.string.confirmation_unchecked),
                substring = true,
            )
    }

    @Test
    fun saysPlainlyWhenAStatusCheckCouldNotBeMade() {
        show(
            TRANSFER,
            sent(RequestState.REQUEST_STATE_SUBMITTED),
            signingProblem = SigningProblem.NotChecked,
        )
        compose
            .onNodeWithTag(InboxTags.SIGNING_PROBLEM)
            .performScrollTo()
            .assertTextEquals(context.getString(R.string.problem_not_checked))
    }

    @Test
    fun disablesTheCheckWhileOneIsRunning() {
        show(TRANSFER, sent(RequestState.REQUEST_STATE_SUBMITTED), checking = true)
        compose.onNodeWithTag(InboxTags.CHECK_STATUS).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(InboxTags.SENDING).assertExists()
    }

    private fun result(request: ActionRequest, delivery: Delivery) =
        LocalResult(
            HOME.id,
            request.ref.requestId,
            Answer.Acknowledge,
            NOW,
            request,
            delivery,
        )

    private companion object {
        val TRANSFER: ActionRequest =
            FakeConnectionGateway.request(HOME.id, createdAt = NOW.minusSeconds(60))
                .toBuilder()
                .setAction(
                    action {
                        transfer = transferAction {
                            wallet = WALLET
                            network = Network.NETWORK_DEVNET
                            recipient = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
                            amount = "2500000000"
                            asset = asset { nativeSol = Asset.NativeSol.getDefaultInstance() }
                        }
                    }
                )
                .build()

        // Looks like markup and a link, and must be shown exactly as written.
        const val TEXT = "Deploy <b>finished</b> — see https://example.com [ok]"
        const val WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
        val SELECTED =
            SelectedWallet(
                address = WALLET,
                network = WalletNetwork.Devnet,
                selectedAt = Instant.parse("2026-09-11T11:00:00Z"),
            )
        // A carriage return and a tab, which the screen has to make visible.
        val MESSAGE: ActionRequest =
            FakeConnectionGateway.request(HOME.id, createdAt = NOW.minusSeconds(60))
                .toBuilder()
                .setAction(
                    action {
                        signMessage = signMessageAction {
                            wallet = WALLET
                            text = "Sign in to Example\r\nNonce: 7\tx"
                        }
                    }
                )
                .build()
        val REQUEST: ActionRequest =
            FakeConnectionGateway.request(HOME.id, text = TEXT, createdAt = NOW.minusSeconds(120))
                .toBuilder()
                .setAgentNote("Nightly release")
                .build()
    }
}
