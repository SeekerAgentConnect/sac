package io.github.brrenat.seekervault.operations

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.jupiter.MARKET
import io.github.brrenat.seekervault.jupiter.MARKET_ID
import io.github.brrenat.seekervault.jupiter.ORDER_ACCOUNT
import io.github.brrenat.seekervault.jupiter.ORDER_PUBKEY
import io.github.brrenat.seekervault.jupiter.POSITION_ACCOUNT
import io.github.brrenat.seekervault.jupiter.POSITION_PUBKEY
import io.github.brrenat.seekervault.jupiter.openMarket
import io.github.brrenat.seekervault.jupiter.orderTransaction
import io.github.brrenat.seekervault.jupiter.predictionOrder
import io.github.brrenat.seekervault.plugins.JUPITER_PREDICTION
import io.github.brrenat.seekervault.plugins.JUPITER_PROVIDER
import io.github.brrenat.seekervault.plugins.PREDICTION_BUY_ACTION
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.actions.PredictionOutcomes
import io.github.brrenat.seekervault.plugins.actions.PredictionParameterNames
import io.github.brrenat.seekervault.plugins.actions.PredictionTermNames
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.solana.SolanaProblem
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.SendResult
import io.github.brrenat.seekervault.wallet.WalletNetwork
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

/**
 * A market proposal, from the feed to a signature and the links after it (SEE-94).
 *
 * This is SEE-94's acceptance at the level a person experiences: the owner picks a side and a
 * stake, the order is prepared and read, the wallet is asked once, and what is left afterwards is a
 * truthful record and somewhere to continue. The same screens the swap uses, with the same review
 * order — `operations/` never learns what a prediction is.
 */
@RunWith(AndroidJUnit4::class)
class PredictionOperationTest {
    @get:Rule val folder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @After fun resetMain() = Dispatchers.resetMain()

    private var clock = Instant.parse("2026-09-17T10:00:00Z")

    private val owner = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"

    private fun phone(name: String = "phone") = Phone(File(folder.root, name)) { clock }

    /** Connects a wallet, reads the feed, and opens the market proposal on it. */
    private fun opened(
        phone: Phone,
        address: String = owner,
        network: WalletNetwork = WalletNetwork.Mainnet,
        proposal: io.github.brrenat.seekervault.proposal.v1.Proposal = predictionProposal(),
    ): OperationViewModel = runBlocking {
        // The provider builds the order it was asked for, and the chain resolves what that order
        // names — so the resolver does real work on real indexes.
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
        phone.adapter.answerConnected(address, chains = listOf(network.chain))
        phone.wallet.load()
        phone.wallet.connect(network)
        phone.feed.answers = listOf(proposal)
        val model = phone.viewModel()
        model.refresh(CONNECTION)
        model.open(CONNECTION, proposal.proposalId)
        model
    }

    private fun choose(model: OperationViewModel, yes: Boolean, stake: ULong) {
        model.choose(
            PredictionParameterNames.OUTCOME,
            ParameterValue.Selected(if (yes) PredictionOutcomes.YES else PredictionOutcomes.NO),
        )
        model.choose(PredictionParameterNames.DEPOSIT, ParameterValue.Amount(stake))
    }

    @Test
    fun theOwnerIsAskedForASideAndAStakeAndIsSuggestedNeither() {
        val model = opened(phone())

        val review = checkNotNull(model.review.value)
        assertTrue(review.served)
        assertNull(review.form.problem)
        assertEquals(
            listOf(PredictionParameterNames.OUTCOME, PredictionParameterNames.DEPOSIT),
            review.form.fields.map { it.key },
        )
        // Neither is chosen for them: not the stake, because it is their money, and not the side,
        // because suggesting one would be the app having an opinion about a market.
        assertNull(review.choice[PredictionParameterNames.OUTCOME])
        assertNull(review.choice[PredictionParameterNames.DEPOSIT])
        assertTrue(review.form.fields[0].kind is ParameterKind.Choice)
        // And there is somewhere to continue even before anything is prepared, because it comes
        // from the market the signal names rather than from an order.
        assertEquals(
            listOf("https://jup.ag/prediction/$MARKET_ID"),
            review.destinations.map { it.url },
        )
    }

    @Test
    fun theOrderIsPreparedResolvedAndReadBeforeAnythingIsOfferedToSign() = runBlocking {
        val phone = phone()
        val model = opened(phone)

        choose(model, yes = false, stake = 7_000_000UL)
        model.prepare()

        val review = checkNotNull(model.review.value)
        assertEquals(
            "${review.inspection?.findings?.map { it.code }} ${review.failure?.code}",
            Verdict.Verified,
            review.inspection?.verdict,
        )
        assertTrue(review.inspection?.approvable == true)
        // The market was read before the order was asked for, and the chain was read before
        // anything was offered to sign.
        // Twice, and both are reads. The first is `resolve`, when the review opened: what the
        // venue says about the market *now*, so a closed one is a sentence on the screen rather
        // than a preparation waiting to fail (SEE-145). The second is `prepare`'s own check, which
        // is the one the bytes are guarded by — and the order comes after both.
        assertEquals(
            listOf(
                "market $MARKET_ID",
                "market $MARKET_ID",
                "order $MARKET_ID yes=false 7000000 $owner",
            ),
            phone.markets.asked,
        )
        assertEquals(1, phone.chain.asked.size)
        // What the owner stakes, and where it provably goes: the order's own account.
        assertEquals(7_000_000UL, review.inspection?.facts?.amount)
        assertEquals(ORDER_PUBKEY, review.inspection?.facts?.recipient)
    }

    @Test
    fun theWalletIsAskedOnceAndTheRecordKeepsWhichOrderItWas() = runBlocking {
        val phone = phone()
        val model = opened(phone)
        val signature = ByteString.copyFrom(ByteArray(64) { 5 })
        phone.adapter.sendWith(signature)
        choose(model, yes = true, stake = 5_000_000UL)
        model.prepare()
        val reviewed = checkNotNull(checkNotNull(model.review.value).prepared)

        model.approve(phone.wallet.wallet.value)

        assertEquals(1, phone.adapter.sendings.size)
        assertEquals(reviewed.transaction, phone.adapter.sendings.single().first)
        val record = checkNotNull(phone.proposals.proposal(CONNECTION, PREDICTION_PROPOSAL))
        val execution = checkNotNull(record.execution)
        assertEquals(JUPITER_PROVIDER, execution.binding.provider)
        assertEquals(PREDICTION_BUY_ACTION, execution.binding.action)
        assertEquals(ProposalOutcome.Submitted(signature), execution.outcome)
        assertTrue(phone.proposals.standing(record) is ProposalStanding.Executed)

        // The owner's own history: one operation, with the side and stake they chose, the
        // assessment they read, and the identifiers the provider named — read out of the bytes.
        val history = phone.history.records.value.single()
        assertEquals(ActivityKind.Operation, history.kind)
        assertEquals(ActivityOutcome.Sent, history.outcome)
        assertEquals("prediction", history.operation?.operation)
        assertEquals(JUPITER_PREDICTION.value, history.operation?.plugin)
        assertNotNull(history.policy)
        assertEquals(
            listOf(ORDER_ACCOUNT, POSITION_ACCOUNT, MARKET),
            history.operation?.references?.map { it.key },
        )
        assertEquals(
            listOf(ORDER_PUBKEY, POSITION_PUBKEY, MARKET_ID),
            history.operation?.references?.map { it.text },
        )
        // And the record survives a restart, because that is what a record is for.
        phone.history.load()
        assertEquals(
            listOf(ORDER_PUBKEY, POSITION_PUBKEY, MARKET_ID),
            phone.history.records.value.single().operation?.references?.map { it.text },
        )
    }

    @Test
    fun whereToOpenTheOrderAppearsOnlyAfterOneIsPlacedAndOutlivesTheApp() = runBlocking {
        val phone = phone()
        val model = opened(phone)

        // Before anything is ordered there is one place to go: the market. An "Open order" with
        // nothing behind it is exactly what the ticket says not to show (SEE-157).
        assertEquals(
            listOf("https://jup.ag/prediction/$MARKET_ID"),
            checkNotNull(model.review.value).destinations.map { it.url },
        )

        phone.adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 5 }))
        choose(model, yes = true, stake = 5_000_000UL)
        model.prepare()
        model.approve(phone.wallet.wallet.value)

        // The order was placed, so now there is somewhere to open it, beside the market.
        assertEquals(
            listOf("https://jup.ag/prediction/$MARKET_ID", "https://jup.ag/prediction/portfolio"),
            checkNotNull(model.review.value).destinations.map { it.url },
        )

        // And it survives the app: the record kept *which* order — a public account, never a URL —
        // so a History entry read weeks later still builds the same destination from compiled
        // code. This is the History half of the ticket, and it is why no link is stored.
        val restarted = phone.viewModel()
        phone.history.load()
        restarted.refresh(CONNECTION)
        restarted.open(CONNECTION, PREDICTION_PROPOSAL)

        val reopened = checkNotNull(restarted.review.value)
        assertEquals(
            listOf("https://jup.ag/prediction/$MARKET_ID", "https://jup.ag/prediction/portfolio"),
            reopened.destinations.map { it.url },
        )
        // Both are the provider's own app first, because `jup.ag` is Jupiter's app's own address.
        assertEquals(
            reopened.destinations.map { it.url },
            reopened.destinations.map { it.deepLink },
        )
    }

    @Test
    fun aPublishersOwnPageIsUsedWhenItIsTheProvidersAndIgnoredWhenItIsNot() {
        val page = "https://jup.ag/prediction/fed-decision-in-october"
        val named =
            opened(
                phone(),
                proposal =
                    predictionProposal(
                        extra =
                            mapOf(
                                PredictionTermNames.PROVIDER_DEEP_LINK to page,
                                PredictionTermNames.PROVIDER_WEB_URL to page,
                            )
                    ),
            )

        assertEquals(
            listOf(page),
            checkNotNull(named.review.value).destinations.map { it.url },
        )

        // Somewhere that is not the provider's is not where the owner is sent. The adapter's own
        // address stands instead, so there is still somewhere real to go.
        val elsewhere =
            opened(
                phone("other"),
                proposal =
                    predictionProposal(
                        extra =
                            mapOf(
                                PredictionTermNames.PROVIDER_DEEP_LINK to
                                    "https://jup.ag.example.com/prediction/x",
                                PredictionTermNames.PROVIDER_WEB_URL to
                                    "https://notjup.ag/prediction/x",
                            )
                    ),
            )

        assertEquals(
            listOf("https://jup.ag/prediction/$MARKET_ID"),
            checkNotNull(elsewhere.review.value).destinations.map { it.url },
        )
    }

    @Test
    fun aClosedMarketIsSaidRatherThanPreparedAround() = runBlocking {
        val phone = phone()
        val model = opened(phone)
        phone.markets.answersMarket = { openMarket(it, status = "closed") }
        choose(model, yes = true, stake = 5_000_000UL)

        model.prepare()

        val review = checkNotNull(model.review.value)
        assertEquals("market_closed", review.failure?.code)
        assertNull(review.prepared)
        // The order was never asked for: the market is read first for exactly this reason.
        assertTrue(phone.markets.asked.none { it.startsWith("order") })
        assertEquals(
            emptyList<Triple<ByteString, SelectedWallet, String>>(),
            phone.adapter.sendings,
        )
    }

    @Test
    fun aChainThatCannotBeReadStopsTheOrderAndSaysWhy() = runBlocking {
        val phone = phone()
        val model = opened(phone)
        phone.chain.fails = SolanaProblem.NoEndpoint
        choose(model, yes = true, stake = 5_000_000UL)

        model.prepare()

        // The owner's own rule: block signing, say why, and never fall back to reviewing the
        // order's parameters (SEE-94).
        val review = checkNotNull(model.review.value)
        assertEquals(SolanaProblem.NoEndpoint.code, review.failure?.code)
        assertNull(review.prepared)
        assertNull(review.inspection)
        model.approve(phone.wallet.wallet.value)
        assertEquals(
            emptyList<Triple<ByteString, SelectedWallet, String>>(),
            phone.adapter.sendings,
        )
        assertNull(phone.proposals.proposal(CONNECTION, PREDICTION_PROPOSAL)?.execution)
    }

    @Test
    fun anOrderThatIsNotWhatWasChosenNeverReachesTheWallet() = runBlocking {
        val phone = phone()
        val model = opened(phone)
        // A provider that builds an order for the other side. There is no way to ask for one of
        // these, which is why the review has to be the thing that stops it.
        phone.markets.answersOrder = { terms, choice, wallet ->
            val built =
                orderTransaction(
                    terms = terms,
                    yes = !choice.yes,
                    deposit = choice.deposit,
                    owner = wallet,
                )
            phone.chain.tables = built.tables
            predictionOrder(built.transaction, yes = choice.yes, owner = wallet)
        }
        choose(model, yes = true, stake = 5_000_000UL)

        model.prepare()

        val review = checkNotNull(model.review.value)
        assertEquals(Verdict.Invalid, review.inspection?.verdict)
        assertTrue("outcome_mismatch" in review.inspection?.findings.orEmpty().map { it.code })
        assertFalse(review.inspection?.approvable == true)
        model.approve(phone.wallet.wallet.value)
        assertEquals(
            emptyList<Triple<ByteString, SelectedWallet, String>>(),
            phone.adapter.sendings,
        )
    }

    @Test
    fun changingTheSideThrowsAwayTheOrderPreparedForTheOtherOne() = runBlocking {
        val phone = phone()
        val model = opened(phone)
        choose(model, yes = true, stake = 5_000_000UL)
        model.prepare()
        assertNotNull(checkNotNull(model.review.value).prepared)

        model.choose(
            PredictionParameterNames.OUTCOME,
            ParameterValue.Selected(PredictionOutcomes.NO),
        )

        // An order for Yes sitting on screen beside a chosen No is how somebody comes to sign the
        // opposite of what they meant.
        val review = checkNotNull(model.review.value)
        assertNull(review.prepared)
        assertNull(review.inspection)
    }

    @Test
    fun anAnswerThatNeverArrivedIsNotCalledAFailureAndIsNotRepeated() = runBlocking {
        val phone = phone()
        val model = opened(phone)
        phone.adapter.answerSending(SendResult.Unknown("the wallet closed"))
        choose(model, yes = true, stake = 5_000_000UL)
        model.prepare()

        model.approve(phone.wallet.wallet.value)

        // A possibly dispatched order is never repeated, which is the rule that matters most here:
        // an order placed twice is a position twice the size.
        val outcome = phone.proposals.proposal(CONNECTION, PREDICTION_PROPOSAL)?.execution?.outcome
        assertTrue(outcome is ProposalOutcome.Unresolved)
        assertEquals(ActivityOutcome.Unknown, phone.history.records.value.single().outcome)
        model.prepare()
        model.approve(phone.wallet.wallet.value)
        assertEquals(1, phone.adapter.sendings.size)
    }

    @Test
    fun nothingAboutTheSideOrTheStakeReachesTheGateway() = runBlocking {
        val phone = phone()
        val model = opened(phone)
        phone.adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 2 }))
        choose(model, yes = false, stake = 7_000_000UL)
        model.prepare()
        model.approve(phone.wallet.wallet.value)

        // One read of the feed, by its channel. The gateway is not told the side, the stake, the
        // wallet or the order — and there is nowhere for it to be told, which is the point.
        assertEquals(1, phone.feed.asked.size)
        val said = phone.feed.asked.joinToString { it.first.toString() }
        assertFalse(said.contains(owner))
        assertFalse(said.contains("7000000"))
        assertFalse(said.contains(ORDER_PUBKEY))
        // The provider was told the stake and the address, because a transaction has to be built
        // for the account that signs it — and nothing about the publisher.
        assertTrue(phone.markets.asked.any { it.contains("7000000") && it.contains(owner) })
        assertTrue(phone.markets.asked.none { it.contains(PREDICTION_PROPOSAL) })
    }

    /**
     * The rule the whole prediction plugin is shaped around (SEE-94, SEE-98): once the wallet has
     * answered, this app asks nothing further. No fill, no position, no settlement, no payout —
     * continuing happens in Jupiter, and a phone that polled for an outcome would be showing the
     * owner a claim it has no evidence for.
     */
    @Test
    fun nothingFollowsTheOrderAfterTheWalletHasSentIt() = runBlocking {
        val phone = phone()
        val model = opened(phone)
        phone.adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 9 }))
        choose(model, yes = true, stake = 5_000_000UL)
        model.prepare()
        // Everything the order needed was read before it was offered to sign, so these are the
        // counts that must not move again — from before the wallet is asked, not after it, or an
        // extra read during the approval itself would be snapshotted rather than caught.
        val provider = phone.markets.asked.toList()
        val chain = phone.chain.asked.size
        val feed = phone.feed.asked.size

        model.approve(phone.wallet.wallet.value)
        assertEquals(provider, phone.markets.asked)
        assertEquals(chain, phone.chain.asked.size)
        assertEquals(feed, phone.feed.asked.size)

        // And then ten minutes of the app being open with nobody touching it.
        scheduler.advanceTimeBy(600_000L)
        scheduler.runCurrent()
        assertEquals(provider, phone.markets.asked)
        assertEquals(chain, phone.chain.asked.size)
        assertEquals(feed, phone.feed.asked.size)
        // And what the record says is still only what this phone witnessed: sent, with the
        // signature the wallet returned, and no outcome beyond it.
        val execution =
            checkNotNull(phone.proposals.proposal(CONNECTION, PREDICTION_PROPOSAL)?.execution)
        assertEquals(
            ProposalOutcome.Submitted(ByteString.copyFrom(ByteArray(64) { 9 })),
            execution.outcome,
        )
        assertEquals(ActivityOutcome.Sent, phone.history.records.value.single().outcome)
    }
}
