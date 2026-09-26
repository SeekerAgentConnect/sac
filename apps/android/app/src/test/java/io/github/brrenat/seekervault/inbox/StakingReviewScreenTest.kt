package io.github.brrenat.seekervault.inbox

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.timestamp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.inbox.PendingRequestsScreenTest.Companion.HOME
import io.github.brrenat.seekervault.inbox.PendingRequestsScreenTest.Companion.NOW
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.StakingOperation
import io.github.brrenat.seekervault.skr.ADDRESSES
import io.github.brrenat.seekervault.skr.OWNER
import io.github.brrenat.seekervault.skr.SkrReading
import io.github.brrenat.seekervault.skr.inspectStaking
import io.github.brrenat.seekervault.skr.preparedFor
import io.github.brrenat.seekervault.skr.reading
import io.github.brrenat.seekervault.skr.stakingRequest
import io.github.brrenat.seekervault.skr.stakingTransaction
import io.github.brrenat.seekervault.skr.wallet
import io.github.brrenat.seekervault.wallet.SelectedWallet
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The review screen for the four staking actions (SEE-146).
 *
 * What the owner sees comes from this phone's reading of the transaction and of the chain, never
 * from the server's account of what it built. The two cases worth the most here are the pair that
 * gets confused: starting an unstake moves nothing and begins a wait, and withdrawing is the step
 * that finally moves the SKR — the screen has to say which is which before anything is approved.
 */
@RunWith(AndroidJUnit4::class)
class StakingReviewScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var approvals = 0
    private var prepareAgain = 0

    /** Well after any cooldown these cases start, unless the case is about the cooldown. */
    private val chainNow = NOW.epochSecond

    private fun request(
        operation: StakingOperation,
        amount: String = "25000000",
    ): ActionRequest =
        stakingRequest(operation, amount = amount)
            .toBuilder()
            .setState(RequestState.REQUEST_STATE_PENDING)
            .setCreatedAt(timestamp { seconds = NOW.minusSeconds(60).epochSecond })
            .setExpiresAt(timestamp { seconds = NOW.plusSeconds(3600).epochSecond })
            .build()

    private fun ready(
        operation: StakingOperation,
        amount: String = "25000000",
        shares: BigInteger = BigInteger.valueOf(25_000_000L),
        position: SkrReading? = reading(),
        now: Long = chainNow,
        wallet: SelectedWallet? = wallet(),
    ): Preparation.Ready {
        val asked = request(operation, amount)
        val bytes =
            stakingTransaction(
                operation,
                amount = amount.toULongOrNull() ?: 0UL,
                shares = shares,
            )
        val prepared = preparedFor(bytes)
        return Preparation.Ready(
            prepared = prepared,
            inspection = null,
            wallet = wallet,
            staking = inspectStaking(asked, prepared, wallet, position, now),
        )
    }

    private fun show(
        operation: StakingOperation,
        preparation: Preparation?,
        amount: String = "25000000",
        wallet: SelectedWallet? = wallet(),
    ) = compose.setContent {
        SeekerTheme {
            RequestDetailsScreen(
                request = request(operation, amount),
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
                assessment = null,
                acknowledged = false,
                onAcknowledge = {},
                onRules = {},
            )
        }
    }

    private fun field(name: String) = compose.onNodeWithTag(InboxTags.field(name))

    @Test
    fun aStakeIsNamedAsAStakeAndOffersToStake() {
        show(
            StakingOperation.STAKING_OPERATION_STAKE,
            ready(StakingOperation.STAKING_OPERATION_STAKE),
        )
        compose.onNodeWithText(context.getString(R.string.action_staking_stake)).assertExists()
        compose
            .onNodeWithTag(InboxTags.STAKING_EFFECT)
            .assertTextEquals(context.getString(R.string.staking_effect_stake))
        compose
            .onNodeWithTag(InboxTags.APPROVE)
            .assertTextContains(context.getString(R.string.staking_approve_stake))
            .assertIsEnabled()
        // The amount comes from the bytes, shown as SKR rather than base units.
        field(InboxTags.STAKING_AMOUNT)
            .performScrollTo()
            .assertTextContains("25 SKR", substring = true)
    }

    @Test
    fun anUnstakeSaysNothingMovesYet() {
        // Half of the one distinction this screen exists to make; the withdrawal is the other.
        show(
            StakingOperation.STAKING_OPERATION_UNSTAKE,
            ready(StakingOperation.STAKING_OPERATION_UNSTAKE),
        )
        compose
            .onNodeWithTag(InboxTags.STAKING_EFFECT)
            .assertTextEquals(context.getString(R.string.staking_effect_unstake))
        compose
            .onNodeWithTag(InboxTags.APPROVE)
            .assertTextContains(context.getString(R.string.staking_approve_unstake))
    }

    @Test
    fun aWithdrawalIsTheStepThatMovesTheSkr() {
        val pending = reading(unstakingAmount = 9_000_000UL, unstakeTimestamp = chainNow - 200_000L)
        show(
            StakingOperation.STAKING_OPERATION_WITHDRAW,
            ready(StakingOperation.STAKING_OPERATION_WITHDRAW, position = pending),
        )
        compose
            .onNodeWithTag(InboxTags.STAKING_EFFECT)
            .assertTextEquals(context.getString(R.string.staking_effect_withdraw))
        compose
            .onNodeWithTag(InboxTags.APPROVE)
            .assertTextContains(context.getString(R.string.staking_approve_withdraw))
        // Its amount is the program's own record of what is waiting, not a number anybody chose.
        field(InboxTags.STAKING_AMOUNT)
            .performScrollTo()
            .assertTextContains("9 SKR", substring = true)
    }

    @Test
    fun warnsWhenUnstakingAgainWouldRestartACooldownAlreadyRunning() {
        val started = chainNow - 1_000L
        val pending = reading(unstakingAmount = 5_000_000UL, unstakeTimestamp = started)
        show(
            StakingOperation.STAKING_OPERATION_UNSTAKE,
            ready(StakingOperation.STAKING_OPERATION_UNSTAKE, position = pending),
        )
        compose
            .onNodeWithTag(InboxTags.STAKING_COOLDOWN_RESET)
            .performScrollTo()
            .assertTextEquals(context.getString(R.string.staking_cooldown_reset))
    }

    @Test
    fun aQuietUnstakeCarriesNoCooldownWarning() {
        show(
            StakingOperation.STAKING_OPERATION_UNSTAKE,
            ready(StakingOperation.STAKING_OPERATION_UNSTAKE),
        )
        assertEquals(
            0,
            compose.onAllNodesWithTag(InboxTags.STAKING_COOLDOWN_RESET).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun aCancellationSaysThePositionGoesBackToWork() {
        val pending = reading(unstakingAmount = 9_000_000UL, unstakeTimestamp = chainNow - 1_000L)
        show(
            StakingOperation.STAKING_OPERATION_CANCEL_UNSTAKE,
            ready(StakingOperation.STAKING_OPERATION_CANCEL_UNSTAKE, position = pending),
        )
        compose
            .onNodeWithTag(InboxTags.STAKING_EFFECT)
            .assertTextEquals(context.getString(R.string.staking_effect_cancel))
        compose
            .onNodeWithTag(InboxTags.APPROVE)
            .assertTextContains(context.getString(R.string.staking_approve_cancel))
    }

    @Test
    fun aRefusedReadingSaysWhyAndHasNothingToApprove() {
        // An amount the transaction does not carry: the review says so in the owner's words, and
        // the Approve button is not a judgement call they can overrule.
        val asked = request(StakingOperation.STAKING_OPERATION_STAKE, amount = "25000000")
        val bytes =
            stakingTransaction(StakingOperation.STAKING_OPERATION_STAKE, amount = 90_000_000UL)
        val prepared = preparedFor(bytes)
        val refused =
            Preparation.Ready(
                prepared = prepared,
                inspection = null,
                wallet = wallet(),
                staking = inspectStaking(asked, prepared, wallet(), reading(), chainNow),
            )
        show(StakingOperation.STAKING_OPERATION_STAKE, refused)
        compose
            .onAllNodesWithTag(InboxTags.STAKING_FINDING)
            .fetchSemanticsNodes()
            .isNotEmpty()
            .let { assertEquals(true, it) }
        compose.onNodeWithTag(InboxTags.APPROVE).assertIsNotEnabled()
        // And it offers to prepare again, which is the owner's way out of a stale preparation.
        compose.onNodeWithTag(InboxTags.TRANSFER_AGAIN).performClick()
        assertEquals(1, prepareAgain)
        assertEquals(0, approvals)
    }

    @Test
    fun aPreparationThatNeverArrivedSaysSoRatherThanShowingAnEmptyReview() {
        show(
            StakingOperation.STAKING_OPERATION_STAKE,
            Preparation.Failed(CheckOutcome.Unreachable),
        )
        compose
            .onNodeWithTag(InboxTags.STAKING_NOT_PREPARED)
            .performScrollTo()
            .assertTextEquals(context.getString(R.string.staking_not_prepared))
        compose.onNodeWithTag(InboxTags.APPROVE).assertIsNotEnabled()
    }

    @Test
    fun withNoWalletConnectedThereIsNothingToApproveWith() {
        show(
            StakingOperation.STAKING_OPERATION_STAKE,
            ready(StakingOperation.STAKING_OPERATION_STAKE, wallet = null),
            wallet = null,
        )
        compose.onNodeWithTag(InboxTags.APPROVE).assertIsNotEnabled()
        assertEquals(0, approvals)
    }

    @Test
    fun theStakeAccountAndTheProgramAreShownAsThisPhoneDerivedThem() {
        show(
            StakingOperation.STAKING_OPERATION_STAKE,
            ready(StakingOperation.STAKING_OPERATION_STAKE),
        )
        // Both are drawn shortened, so what is asserted is what an owner can actually compare.
        field(InboxTags.STAKING_WALLET)
            .performScrollTo()
            .assertTextContains(OWNER.take(9), substring = true)
        field(InboxTags.STAKING_ACCOUNT)
            .performScrollTo()
            .assertTextContains(checkNotNull(ADDRESSES.userStake(OWNER)).take(9), substring = true)
    }

    @Test
    fun approvingHandsOffToTheWalletRatherThanActingHere() {
        show(
            StakingOperation.STAKING_OPERATION_STAKE,
            ready(StakingOperation.STAKING_OPERATION_STAKE),
        )
        compose.onNodeWithTag(InboxTags.APPROVE).performClick()
        // One call to the hand-off, and this screen does nothing else: no key, no broadcast.
        assertEquals(1, approvals)
    }
}
