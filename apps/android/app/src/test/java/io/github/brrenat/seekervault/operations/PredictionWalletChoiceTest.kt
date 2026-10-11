package io.github.brrenat.seekervault.operations

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.designsystem.ReviewSheetTags
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.jupiter.orderTransaction
import io.github.brrenat.seekervault.jupiter.predictionOrder
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.actions.PredictionOutcomes
import io.github.brrenat.seekervault.plugins.actions.PredictionParameterNames
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.WalletProfile
import io.github.brrenat.seekervault.wallet.WalletTags
import io.github.brrenat.seekervault.wallet.WalletUiState
import io.github.brrenat.seekervault.wallet.walletNetworkOf
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Changing the wallet a prediction is placed with after one is already chosen (SEE-185, review of
 * PR #110).
 *
 * The prediction review used to reach the wallet picker only while no wallet was chosen: once one
 * was, tapping "your part" opened the side and stake instead, so an owner with two authorized
 * Mainnet profiles who chose A could never switch to B. The review now offers **Choose wallet**
 * whenever the signal is open, and choosing another wallet drops what was prepared for the old one.
 *
 * The sheets are the app's own, over the real view model, the real Jupiter provider and the real
 * wallet repository; only the venue, the chain and the wallet app are stood in for. The host below
 * stacks the picker over the review the way `SeekerVaultApp` does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w393dp-h3200dp-xxhdpi")
class PredictionWalletChoiceTest {
    @get:Rule val folder = TemporaryFolder()

    @get:Rule val compose = createComposeRule()

    private val scheduler = TestCoroutineScheduler()

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @After fun resetMain() = Dispatchers.resetMain()

    private val clock = Instant.parse("2026-09-17T10:00:00Z")

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Two authorized Mainnet profiles and no remembered choice: none is used until one is chosen.
     */
    private fun twoMainnetWallets(phone: Phone): Pair<WalletProfile, WalletProfile> = runBlocking {
        phone.wallet.load()
        phone.adapter.answerConnected(FIRST_OWNER, chains = listOf(WalletNetwork.Mainnet.chain))
        val first = phone.wallet.connectProfiles(WalletNetwork.Mainnet).profiles.single()
        phone.adapter.answerConnected(SECOND_OWNER, chains = listOf(WalletNetwork.Mainnet.chain))
        val second =
            phone.wallet.connectProfiles(WalletNetwork.Mainnet).profiles.single {
                it.id != first.id
            }
        first to second
    }

    private fun opened(phone: Phone): OperationViewModel = runBlocking {
        // The venue builds the order for whichever wallet it is asked about, and the chain resolves
        // what that order names, so a quote for A is visibly not a quote for B.
        phone.markets.answersOrder = { terms, choice, wallet ->
            val built =
                orderTransaction(
                    terms = terms,
                    yes = choice.yes,
                    deposit = choice.deposit,
                    owner = wallet,
                )
            phone.chain.tables = built.tables
            predictionOrder(built.transaction, yes = choice.yes, owner = wallet)
        }
        phone.feed.answers = listOf(predictionProposal())
        val model = phone.viewModel()
        model.refresh(CONNECTION)
        model.open(CONNECTION, PREDICTION_PROPOSAL)
        model
    }

    @Composable
    private fun Host(model: OperationViewModel, phone: Phone) {
        val review by model.review.collectAsState()
        val profiles by phone.wallet.profiles.collectAsState()
        var picking by remember { mutableStateOf(false) }
        val open = review ?: return
        SeekerTheme {
            PredictionReviewScreen(
                review = open,
                source = PredictionReviewSource("CopyTrading"),
                wallet = open.wallet,
                now = clock,
                onOwnerInput = {},
                onPrepare = model::prepare,
                onApprove = { model.approve(open.wallet) },
                onDismiss = {},
                onAcknowledge = model::acknowledge,
                onRules = {},
                onBack = {},
                onOpenLink = { _, _ -> },
                onChooseWallet = { picking = true },
            )
            if (picking) {
                OperationWalletPicker(
                    network = checkNotNull(open.network?.let(::walletNetworkOf)),
                    current = open.wallet?.profileId,
                    wallet = WalletUiState(profiles = profiles, loaded = true),
                    onUse = {
                        model.chooseWallet(it)
                        picking = false
                    },
                    onAdd = {},
                    onDismiss = { picking = false },
                )
            }
        }
    }

    private fun pick(profile: WalletProfile) {
        compose.onNodeWithTag(ReviewSheetTags.WALLET_ACTION).performScrollTo().performClick()
        compose.onNodeWithTag(WalletTags.PICKER).assertExists()
        compose.onNodeWithTag(WalletTags.choice(profile.id)).performClick()
        compose.onNodeWithTag(WalletTags.PICKER_USE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(WalletTags.PICKER).assertDoesNotExist()
    }

    /**
     * Whether the sheet offers Approve for [review], read at the test's clock. The screen itself
     * re-reads a quote's expiry against the wall clock, which this fixed clock is far behind, so
     * the decision is read from the state the sheet draws rather than by tapping it.
     */
    private fun approveOffered(review: OperationReview): Boolean =
        review
            .toPredictionSheet(
                context.resources,
                PredictionReviewSource("CopyTrading"),
                review.wallet,
                clock,
            )
            .primaryAction
            .enabled

    @Test
    fun aWalletAlreadyChosenCanBeChangedAndTheOrderMustBePreparedAgainForTheNewOne() {
        val phone = Phone(File(folder.root, "phone")) { clock }
        val (first, second) = twoMainnetWallets(phone)
        phone.adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 5 }))
        val model = opened(phone)
        compose.setContent { Host(model, phone) }

        // Nobody chose yet, so nothing signs; the review says how to choose.
        assertNull(checkNotNull(model.review.value).wallet)
        compose
            .onNodeWithText(context.getString(R.string.operation_connect_wallet, "Mainnet"))
            .assertExists()
        pick(first)
        assertEquals(FIRST_OWNER, checkNotNull(model.review.value).wallet?.address)

        model.choose(
            PredictionParameterNames.OUTCOME,
            ParameterValue.Selected(PredictionOutcomes.YES),
        )
        model.choose(PredictionParameterNames.DEPOSIT, ParameterValue.Amount(5_000_000UL))
        model.prepare()
        val forFirst = checkNotNull(model.review.value)
        assertNotNull(forFirst.prepared)
        assertEquals(FIRST_OWNER, forFirst.preparedFor?.address)
        assertTrue(approveOffered(forFirst))

        // The action is still there with A chosen and prepared for: this is the route the review
        // did not have.
        compose
            .onNodeWithText(context.getString(R.string.operation_choose_wallet))
            .performScrollTo()
            .assertExists()
        pick(second)

        // B is the wallet now, and A's order went with the acknowledgement beside it.
        val switched = checkNotNull(model.review.value)
        assertEquals(SECOND_OWNER, switched.wallet?.address)
        assertNull(switched.prepared)
        assertNull(switched.inspection)
        assertEquals(OperationProblem.WalletChanged, switched.problem)
        // Approve offers nothing to sign until B's own order is prepared, and the review that was
        // approvable for A reaches no wallet.
        assertFalse(approveOffered(switched))
        model.approve(forFirst.wallet)
        model.approve(switched.wallet)
        assertTrue(phone.adapter.sendings.isEmpty())

        model.prepare()
        val forSecond = checkNotNull(model.review.value)
        assertEquals(SECOND_OWNER, forSecond.preparedFor?.address)
        assertTrue(phone.markets.asked.last().endsWith(SECOND_OWNER))
        assertTrue(approveOffered(forSecond))
        model.approve(forSecond.wallet)
        val sent = phone.adapter.sendings.single()
        assertEquals(checkNotNull(forSecond.prepared).transaction, sent.first)
        assertEquals(SECOND_OWNER, sent.second.address)
    }

    private companion object {
        const val FIRST_OWNER = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        const val SECOND_OWNER = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
    }
}
